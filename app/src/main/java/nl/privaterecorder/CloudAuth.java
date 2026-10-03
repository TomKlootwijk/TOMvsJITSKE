package nl.privaterecorder;

import android.accounts.Account;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;

import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.ClearTokenRequest;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.Tasks;

import net.openid.appauth.AppAuthConfiguration;
import net.openid.appauth.AuthState;
import net.openid.appauth.AuthorizationException;
import net.openid.appauth.AuthorizationResponse;
import net.openid.appauth.AuthorizationService;
import net.openid.appauth.AuthorizationServiceConfiguration;
import net.openid.appauth.ResponseTypeValues;

import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.GeneralSecurityException;
import java.util.Collections;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Public-client authorization for direct phone-to-cloud uploads. No app secrets or
 * desktop connector credentials. token()/invalidateToken() must run off the UI thread.
 * Google consent results are routed through REQUEST_GOOGLE. Microsoft completes in
 * OAuthRedirectActivity and opens CloudSettingsActivity with the EXTRA_AUTH_* values.
 */
public final class CloudAuth {
    public static final String GOOGLE = "google";
    public static final String ONEDRIVE = "onedrive";
    public static final int REQUEST_GOOGLE = 801;
    public static final String EXTRA_AUTH_PROVIDER = "cloud_auth_provider";
    public static final String EXTRA_AUTH_ERROR = "cloud_auth_error";
    public static final String MICROSOFT_REDIRECT = "nl.privaterecorder://oauth/microsoft";
    public static final String GOOGLE_SCOPE = "https://www.googleapis.com/auth/drive.file";
    public static final String MICROSOFT_SCOPE = "https://graph.microsoft.com/Files.ReadWrite.AppFolder";
    private static final String MS_AUTHORIZE = "https://login.microsoftonline.com/common/oauth2/v2.0/authorize";
    private static final String MS_TOKEN = "https://login.microsoftonline.com/common/oauth2/v2.0/token";
    private static final String EXTRA_ATTEMPT = "private_recorder_auth_attempt";
    private static final String EXTRA_CANCEL = "private_recorder_auth_cancel";
    private static final String EXTRA_GOOGLE_ATTEMPT = "private_recorder_google_attempt";
    private static final String PENDING_GOOGLE = "pending-google";
    private static final String PENDING_MS = "pending-microsoft";
    private static final String MS_SLOT = "microsoft";
    private static final long ATTEMPT_MS = 10 * 60 * 1000L;
    private static final Object STATE_LOCK = new Object();
    private static final Object MS_TOKEN_LOCK = new Object();

    private CloudAuth() { }
    public interface Callback { void done(String provider, Exception failure); }

    public static void connect(Activity activity, String provider, Callback callback) {
        requireMainThread();
        try {
            validateProvider(provider);
            if (GOOGLE.equals(provider)) connectGoogle(activity, callback);
            else connectMicrosoft(activity);
        } catch (Exception error) {
            callback.done(provider, error);
        }
    }

    public static void handleGoogleResult(Activity activity, int resultCode, Intent data, Callback callback) {
        requireMainThread();
        String attempt = activity.getIntent().getStringExtra(EXTRA_GOOGLE_ATTEMPT);
        try {
            if (resultCode != Activity.RESULT_OK || data == null) {
                clearPending(activity, PENDING_GOOGLE, attempt);
                throw new IOException("Google Drive sign-in was cancelled. Existing recordings remain on this phone.");
            }
            AuthorizationResult result = Identity.getAuthorizationClient(activity)
                    .getAuthorizationResultFromIntent(data);
            finishGoogle(activity, result, attempt);
            activity.getIntent().removeExtra(EXTRA_GOOGLE_ATTEMPT);
            callback.done(GOOGLE, null);
        } catch (Exception error) {
            clearPendingQuietly(activity, PENDING_GOOGLE, attempt);
            callback.done(GOOGLE, googleError(error));
        }
    }

    public static String token(Context context, String provider) throws Exception {
        requireWorkerThread();
        validateProvider(provider);
        if (GOOGLE.equals(provider)) return googleToken(context);
        synchronized (MS_TOKEN_LOCK) { return microsoftToken(context); }
    }

    /** Invalidate a rejected token before one transport retry. Never loops on a 401. */
    public static void invalidateToken(Context context, String provider, String rejectedToken) throws Exception {
        requireWorkerThread();
        validateProvider(provider);
        if (GOOGLE.equals(provider)) {
            Tasks.await(Identity.getAuthorizationClient(context.getApplicationContext())
                    .clearToken(ClearTokenRequest.builder().setToken(rejectedToken).build()), 30, TimeUnit.SECONDS);
            synchronized (STATE_LOCK) {
                JSONObject record = record(context, GOOGLE);
                if (record != null && rejectedToken.equals(record.optString("initial_token"))) {
                    record.remove("initial_token");
                    write(context, GOOGLE, record);
                }
            }
        } else {
            synchronized (STATE_LOCK) {
                JSONObject record = record(context, MS_SLOT);
                if (record != null) {
                    AuthState state = AuthState.jsonDeserialize(record.getString("state"));
                    if (rejectedToken.equals(state.getAccessToken())) {
                        record.put("force_refresh", true);
                        write(context, MS_SLOT, record);
                    }
                }
            }
        }
    }

    public static void configureMicrosoft(Context context, String clientId) throws Exception {
        String normalized = normalizeMicrosoftClientId(clientId);
        synchronized (STATE_LOCK) {
            if (!normalized.equals(microsoftClientId(context))) {
                disconnect(context, ONEDRIVE);
                if (!context.getSharedPreferences("cloud-auth-public", Context.MODE_PRIVATE).edit()
                        .putString("microsoft_client_id", normalized).commit()) {
                    throw new IOException("Could not save the Microsoft application ID.");
                }
            }
        }
    }

    public static String microsoftClientId(Context context) {
        return context.getSharedPreferences("cloud-auth-public", Context.MODE_PRIVATE)
                .getString("microsoft_client_id", "");
    }

    /** A saved connection may still require re-consent; token() reports that explicitly. */
    public static boolean connected(Context context, String provider) {
        validateProvider(provider);
        synchronized (STATE_LOCK) {
            try {
                JSONObject record = record(context, slot(provider));
                return record != null && !record.optString("generation").isEmpty();
            } catch (Exception error) { return false; }
        }
    }

    /** Receipt namespace; changes on a new connection, or when Google identity is first pinned. */
    public static String accountKey(Context context, String provider) {
        validateProvider(provider);
        synchronized (STATE_LOCK) {
            try {
                JSONObject record = record(context, slot(provider));
                if (record == null) return "";
                String identity = GOOGLE.equals(provider) ? record.optString("permission_id") : "";
                return identity.isEmpty() ? record.optString("generation") : identity;
            } catch (Exception error) { return ""; }
        }
    }

    public static void setGoogleAccount(Context context, String email, String permissionId) throws Exception {
        setGoogleAccount(context, email, permissionId, null);
    }

    /** Call after Drive about.get on the token returned for expectedAccountKey. */
    public static void setGoogleAccount(Context context, String email, String permissionId,
                                        String expectedAccountKey) throws Exception {
        if (email == null || !email.contains("@") || email.length() > 320 || hasControl(email)
                || permissionId == null || permissionId.isEmpty() || permissionId.length() > 256
                || hasControl(permissionId)) throw new IOException("Google Drive did not return a usable account identity.");
        synchronized (STATE_LOCK) {
            JSONObject record = requireRecord(context, GOOGLE);
            if (expectedAccountKey != null && !expectedAccountKey.equals(accountKey(context, GOOGLE))) {
                throw accountChanged();
            }
            String oldId = record.optString("permission_id");
            if (!oldId.isEmpty() && !oldId.equals(permissionId)) throw accountChanged();
            record.put("email", email).put("permission_id", permissionId);
            write(context, GOOGLE, record);
        }
    }

    /** Local disconnection. Provider permissions can separately be revoked in account settings. */
    public static void disconnect(Context context, String provider) throws Exception {
        validateProvider(provider);
        synchronized (STATE_LOCK) {
            TokenVault.write(context, slot(provider), null);
            TokenVault.write(context, GOOGLE.equals(provider) ? PENDING_GOOGLE : PENDING_MS, null);
        }
    }

    private static void connectGoogle(Activity activity, Callback callback) throws Exception {
        final String attempt = UUID.randomUUID().toString();
        synchronized (STATE_LOCK) {
            JSONObject existing = record(activity, PENDING_GOOGLE);
            if (existing != null && freshPending(activity, existing)) {
                throw new IOException("Google sign-in is already open. Finish or cancel that sign-in before trying again.");
            }
            write(activity, PENDING_GOOGLE, pending(activity, attempt));
            activity.getIntent().putExtra(EXTRA_GOOGLE_ATTEMPT, attempt);
        }
        AuthorizationRequest request = AuthorizationRequest.builder()
                .setRequestedScopes(Collections.singletonList(new Scope(GOOGLE_SCOPE)))
                .setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT).build();
        Identity.getAuthorizationClient(activity).authorize(request)
                .addOnSuccessListener(result -> {
                    try {
                        synchronized (STATE_LOCK) { requirePending(activity, PENDING_GOOGLE, attempt); }
                        if (result.hasResolution()) {
                            if (result.getPendingIntent() == null) throw new IOException("Google Drive did not provide a sign-in screen.");
                            activity.startIntentSenderForResult(result.getPendingIntent().getIntentSender(),
                                    REQUEST_GOOGLE, null, 0, 0, 0);
                        } else {
                            finishGoogle(activity, result, attempt);
                            activity.getIntent().removeExtra(EXTRA_GOOGLE_ATTEMPT);
                            callback.done(GOOGLE, null);
                        }
                    } catch (Exception error) {
                        clearPendingQuietly(activity, PENDING_GOOGLE, attempt);
                        callback.done(GOOGLE, googleError(error));
                    }
                })
                .addOnFailureListener(error -> {
                    clearPendingQuietly(activity, PENDING_GOOGLE, attempt);
                    callback.done(GOOGLE, googleError(error));
                });
    }

    private static void finishGoogle(Context context, AuthorizationResult result, String attempt) throws Exception {
        if (result.hasResolution() || result.getAccessToken() == null || result.getAccessToken().isEmpty()
                || !result.getGrantedScopes().contains(GOOGLE_SCOPE)) {
            throw new IOException("Google Drive file permission was not granted. Reconnect Google Drive and allow file access.");
        }
        synchronized (STATE_LOCK) {
            JSONObject pending = requirePending(context, PENDING_GOOGLE, attempt);
            JSONObject record = new JSONObject().put("generation", pending.getString("attempt"))
                    .put("initial_token", result.getAccessToken())
                    .put("issued_wall", System.currentTimeMillis())
                    .put("issued_elapsed", SystemClock.elapsedRealtime())
                    .put("boot_count", bootCount(context));
            write(context, GOOGLE, record);
            TokenVault.write(context, PENDING_GOOGLE, null);
        }
    }

    private static String googleToken(Context context) throws Exception {
        final JSONObject saved;
        synchronized (STATE_LOCK) { saved = requireRecord(context, GOOGLE); }
        String email = saved.optString("email");
        if (email.isEmpty()) {
            if (saved.optInt("boot_count", -1) == bootCount(context)
                    && isFresh(saved.optLong("issued_wall"), saved.optLong("issued_elapsed"),
                    System.currentTimeMillis(), SystemClock.elapsedRealtime(), 50 * 60 * 1000L)
                    && !saved.optString("initial_token").isEmpty()) return saved.getString("initial_token");
            throw new IOException("Reconnect Google Drive to finish identifying the backup account.");
        }
        AuthorizationRequest request = AuthorizationRequest.builder()
                .setRequestedScopes(Collections.singletonList(new Scope(GOOGLE_SCOPE)))
                .setAccount(new Account(email, "com.google")).build();
        final AuthorizationResult result;
        try {
            result = Tasks.await(Identity.getAuthorizationClient(context.getApplicationContext())
                    .authorize(request), 55, TimeUnit.SECONDS);
        } catch (Exception error) { throw googleError(error); }
        if (result.hasResolution() || result.getAccessToken() == null || result.getAccessToken().isEmpty()
                || !result.getGrantedScopes().contains(GOOGLE_SCOPE)) {
            throw new IOException("Google Drive needs your sign-in or permission again. Open Cloud backup and reconnect.");
        }
        synchronized (STATE_LOCK) { requireSameGeneration(context, GOOGLE, saved.getString("generation")); }
        return result.getAccessToken();
    }

    private static AuthorizationService microsoftService(Context context) {
        // Only the fixed HTTPS Microsoft token endpoint may receive a refresh token.
        AppAuthConfiguration configuration = new AppAuthConfiguration.Builder()
                .setConnectionBuilder(uri -> {
                    if (!MS_TOKEN.equals(uri.toString())) throw new IOException("Unexpected Microsoft token endpoint.");
                    java.net.HttpURLConnection connection = (java.net.HttpURLConnection) new java.net.URL(uri.toString()).openConnection();
                    connection.setInstanceFollowRedirects(false);
                    connection.setConnectTimeout(15000);
                    connection.setReadTimeout(30000);
                    return connection;
                }).build();
        return new AuthorizationService(context.getApplicationContext(), configuration);
    }

    private static void connectMicrosoft(Activity activity) throws Exception {
        String clientId = microsoftClientId(activity);
        if (clientId.isEmpty()) throw new IOException("Enter the Microsoft application (client) ID first. This public ID comes from your app registration; never enter a client secret.");
        clientId = normalizeMicrosoftClientId(clientId);
        String attempt = UUID.randomUUID().toString();
        net.openid.appauth.AuthorizationRequest request = new net.openid.appauth.AuthorizationRequest.Builder(
                new AuthorizationServiceConfiguration(Uri.parse(MS_AUTHORIZE), Uri.parse(MS_TOKEN)),
                clientId, ResponseTypeValues.CODE, Uri.parse(MICROSOFT_REDIRECT))
                .setScope("offline_access " + MICROSOFT_SCOPE)
                .setPrompt("select_account").build();
        // AppAuth generates a cryptographically random state and an S256 PKCE verifier.
        synchronized (STATE_LOCK) {
            write(activity, PENDING_MS, pending(activity, attempt).put("request", request.jsonSerializeString()));
        }
        int requestCode = attempt.hashCode() & 0x7fffffff;
        Intent completed = new Intent(activity, OAuthRedirectActivity.class)
                .setAction("nl.privaterecorder.AUTH_COMPLETE." + attempt).putExtra(EXTRA_ATTEMPT, attempt);
        Intent cancelled = new Intent(activity, OAuthRedirectActivity.class)
                .setAction("nl.privaterecorder.AUTH_CANCEL." + attempt).putExtra(EXTRA_ATTEMPT, attempt)
                .putExtra(EXTRA_CANCEL, true);
        int flags = PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_MUTABLE;
        AuthorizationService service = microsoftService(activity);
        try {
            service.performAuthorizationRequest(request,
                    PendingIntent.getActivity(activity, requestCode, completed, flags),
                    PendingIntent.getActivity(activity, requestCode, cancelled, flags));
        } catch (Exception error) {
            clearPending(activity, PENDING_MS, attempt);
            throw new IOException("Could not open Microsoft sign-in. Install or enable a browser and try again.");
        } finally { service.dispose(); }
    }

    static void finishMicrosoft(Activity activity, Intent data, Callback callback) {
        requireMainThread();
        final String attempt = data == null ? null : data.getStringExtra(EXTRA_ATTEMPT);
        AuthorizationService service = null;
        try {
            if (data == null || attempt == null) throw new IOException("This Microsoft sign-in response has no pending request.");
            if (data.getBooleanExtra(EXTRA_CANCEL, false)) {
                clearPending(activity, PENDING_MS, attempt);
                throw new IOException("OneDrive sign-in was cancelled. Existing recordings remain on this phone.");
            }
            final net.openid.appauth.AuthorizationRequest trustedRequest;
            synchronized (STATE_LOCK) {
                JSONObject pending = requirePending(activity, PENDING_MS, attempt);
                if (pending.optBoolean("consumed")) throw new IOException("Microsoft sign-in is already being completed. If it does not finish, reconnect OneDrive.");
                trustedRequest = net.openid.appauth.AuthorizationRequest.jsonDeserialize(pending.getString("request"));
            }
            if (data.getData() == null || !validMicrosoftRedirect(data.getData().toString())) {
                throw new IOException("Microsoft sign-in returned an unexpected redirect address.");
            }
            if (!sameSecret(trustedRequest.state, data.getData().getQueryParameter("state"))) {
                throw new IOException("Microsoft sign-in did not match this request. Reconnect OneDrive.");
            }
            AuthorizationException authorizationError = AuthorizationException.fromIntent(data);
            if (authorizationError != null) throw microsoftError(authorizationError);
            AuthorizationResponse response = AuthorizationResponse.fromIntent(data);
            if (response == null || response.authorizationCode == null || response.authorizationCode.isEmpty()
                    || !sameSecret(trustedRequest.state, response.state)
                    || !MICROSOFT_REDIRECT.equals(trustedRequest.redirectUri.toString())
                    || !MS_TOKEN.equals(trustedRequest.configuration.tokenEndpoint.toString())
                    || !trustedRequest.clientId.equals(microsoftClientId(activity))) {
                throw new IOException("Microsoft sign-in returned an invalid response. Reconnect OneDrive.");
            }
            AuthorizationResponse trustedResponse = new AuthorizationResponse.Builder(trustedRequest)
                    .setAuthorizationCode(response.authorizationCode).setState(response.state).build();
            AuthState state = new AuthState(trustedResponse, null);
            synchronized (STATE_LOCK) {
                JSONObject pending = requirePending(activity, PENDING_MS, attempt);
                pending.put("consumed", true);
                write(activity, PENDING_MS, pending);
            }
            service = microsoftService(activity);
            final AuthorizationService activeService = service;
            final Context application = activity.getApplicationContext();
            service.performTokenRequest(trustedResponse.createTokenExchangeRequest(), (tokens, error) -> {
                Exception failure = null;
                try {
                    if (error != null) throw microsoftError(error);
                    if (tokens == null || tokens.accessToken == null || tokens.refreshToken == null) {
                        throw new IOException("Microsoft did not grant ongoing access. Reconnect OneDrive and allow access to the app folder.");
                    }
                    state.update(tokens, null);
                    if (!hasMicrosoftScope(state.getScope())) throw new IOException("Microsoft did not grant access to the app folder.");
                    synchronized (STATE_LOCK) {
                        requirePending(application, PENDING_MS, attempt);
                        if (!trustedRequest.clientId.equals(microsoftClientId(application))) throw accountChanged();
                        write(application, MS_SLOT, new JSONObject().put("generation", attempt)
                                .put("state", state.jsonSerializeString()));
                        TokenVault.write(application, PENDING_MS, null);
                    }
                } catch (Exception problem) { failure = problem; }
                finally { activeService.dispose(); }
                callback.done(ONEDRIVE, failure);
            });
        } catch (Exception error) {
            if (service != null) service.dispose();
            callback.done(ONEDRIVE, error);
        }
    }

    private static String microsoftToken(Context context) throws Exception {
        JSONObject saved;
        synchronized (STATE_LOCK) { saved = requireRecord(context, MS_SLOT); }
        String generation = saved.getString("generation");
        AuthState state = AuthState.jsonDeserialize(saved.getString("state"));
        if (state.getLastAuthorizationResponse() == null
                || !microsoftClientId(context).equals(state.getLastAuthorizationResponse().request.clientId)
                || state.getRefreshToken() == null) throw new IOException("Reconnect OneDrive to restore automatic backup.");
        if (state.getAuthorizationException() != null) throw microsoftError(state.getAuthorizationException());
        if (saved.optBoolean("force_refresh")) state.setNeedsTokenRefresh(true);
        AuthorizationService service = microsoftService(context);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> token = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        try {
            state.performActionWithFreshTokens(service, (accessToken, idToken, error) -> {
                try {
                    synchronized (STATE_LOCK) {
                        JSONObject current = requireSameGeneration(context, MS_SLOT, generation);
                        current.put("state", state.jsonSerializeString());
                        // Keep the rejected-token marker if the refresh failed.
                        if (error == null) current.remove("force_refresh");
                        write(context, MS_SLOT, current);
                    }
                    if (error != null) throw microsoftError(error);
                    if (accessToken == null || accessToken.isEmpty()) throw new IOException("Reconnect OneDrive to restore automatic backup.");
                    token.set(accessToken);
                } catch (Exception problem) { failure.set(problem); }
                finally { done.countDown(); }
            });
            if (!done.await(55, TimeUnit.SECONDS)) throw new IOException("Microsoft sign-in refresh timed out. Backup will retry when the connection is available.");
            if (failure.get() != null) throw failure.get();
            return token.get();
        } finally { service.dispose(); }
    }

    private static JSONObject pending(Context context, String attempt) throws Exception {
        return new JSONObject().put("attempt", attempt).put("created_wall", System.currentTimeMillis())
                .put("created_elapsed", SystemClock.elapsedRealtime()).put("boot_count", bootCount(context));
    }

    private static JSONObject requirePending(Context context, String slot, String attempt) throws Exception {
        JSONObject pending = record(context, slot);
        if (pending == null || (attempt != null && !attempt.equals(pending.optString("attempt")))
                || !freshPending(context, pending)) {
            throw new IOException("This sign-in request expired or was cancelled. Start Connect again.");
        }
        return pending;
    }

    private static boolean freshPending(Context context, JSONObject pending) {
        return pending.optInt("boot_count", -1) == bootCount(context)
                && isFresh(pending.optLong("created_wall"), pending.optLong("created_elapsed"),
                System.currentTimeMillis(), SystemClock.elapsedRealtime(), ATTEMPT_MS);
    }

    private static int bootCount(Context context) {
        return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
    }

    private static void clearPending(Context context, String slot, String attempt) throws Exception {
        synchronized (STATE_LOCK) {
            JSONObject pending = record(context, slot);
            if (pending != null && (attempt == null || attempt.equals(pending.optString("attempt")))) {
                TokenVault.write(context, slot, null);
            }
        }
    }

    private static void clearPendingQuietly(Context context, String slot, String attempt) {
        try { clearPending(context, slot, attempt); }
        catch (Exception ignored) { /* The bounded attempt still expires; never log OAuth state. */ }
    }

    private static JSONObject record(Context context, String slot) throws Exception {
        final String json;
        try { json = TokenVault.read(context, slot); }
        catch (GeneralSecurityException error) {
            throw new IOException("Saved cloud sign-in could not be unlocked on this phone. Reconnect the cloud account.");
        }
        return json == null ? null : new JSONObject(json);
    }

    private static JSONObject requireRecord(Context context, String slot) throws Exception {
        JSONObject record = record(context, slot);
        if (record == null || record.optString("generation").isEmpty()) {
            throw new IOException("Connect " + (GOOGLE.equals(slot) ? "Google Drive" : "OneDrive") + " in Cloud backup first.");
        }
        return record;
    }

    private static JSONObject requireSameGeneration(Context context, String slot, String generation) throws Exception {
        JSONObject record = record(context, slot);
        if (record == null || !generation.equals(record.optString("generation"))) throw accountChanged();
        return record;
    }

    private static void write(Context context, String slot, JSONObject data) throws Exception {
        TokenVault.write(context, slot, data.toString());
    }

    private static IOException accountChanged() {
        return new IOException("The backup account changed during this request. Backup will retry for the current account.");
    }

    private static Exception googleError(Exception error) {
        Throwable actual = error instanceof ExecutionException ? error.getCause() : error;
        if (actual instanceof ApiException) {
            int status = ((ApiException) actual).getStatusCode();
            if (status == 10) return new IOException("Google Drive sign-in is not configured for this APK. Register its package and signing SHA-1 in Google Cloud, enable Drive API, and try again.");
            return new IOException("Google Drive sign-in could not finish (Google status " + status + "). Check the connection and reconnect Google Drive.");
        }
        if (actual instanceof TimeoutException) return new IOException("Google Drive sign-in timed out. Check the connection and try again.");
        if (actual instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return new IOException("Google Drive sign-in was interrupted; backup remains queued.");
        }
        return error;
    }

    private static IOException microsoftError(AuthorizationException error) {
        String code = error.error;
        if (code == null || !code.matches("[a-zA-Z0-9_-]{1,80}")) code = "authorization_error";
        if ("invalid_client".equals(code) || "unauthorized_client".equals(code)) {
            return new IOException("Microsoft app registration is not ready (" + code + "). Check the client ID, personal/work account support and mobile redirect URI.");
        }
        if (error.type == AuthorizationException.TYPE_GENERAL_ERROR) {
            return new IOException("Microsoft sign-in is temporarily unavailable. Check the connection and try again.");
        }
        return new IOException("Microsoft needs sign-in again (" + code + "). Reconnect OneDrive in Cloud backup.");
    }

    private static String slot(String provider) { return GOOGLE.equals(provider) ? GOOGLE : MS_SLOT; }
    private static void validateProvider(String provider) {
        if (!GOOGLE.equals(provider) && !ONEDRIVE.equals(provider)) throw new IllegalArgumentException("Unknown cloud provider");
    }
    private static void requireMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Connect must run on the UI thread");
    }
    private static void requireWorkerThread() {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Cloud token requests must run on a worker thread");
    }

    static String normalizeMicrosoftClientId(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (!trimmed.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                || "00000000-0000-0000-0000-000000000000".equals(trimmed)) {
            throw new IllegalArgumentException("Enter the Application (client) ID from Microsoft app registration, in UUID format.");
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }
    static boolean validMicrosoftRedirect(String value) {
        try {
            URI uri = URI.create(value);
            return "nl.privaterecorder".equals(uri.getScheme()) && "oauth".equals(uri.getRawAuthority())
                    && "/microsoft".equals(uri.getRawPath()) && uri.getRawFragment() == null;
        } catch (RuntimeException error) { return false; }
    }
    static boolean isFresh(long createdWall, long createdElapsed, long wallNow, long elapsedNow, long maxAge) {
        return createdWall > 0 && createdElapsed >= 0 && wallNow >= createdWall && elapsedNow >= createdElapsed
                && wallNow - createdWall <= maxAge && elapsedNow - createdElapsed <= maxAge;
    }
    static boolean sameSecret(String expected, String actual) {
        return expected != null && actual != null && !expected.isEmpty()
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }
    static boolean hasMicrosoftScope(String scopes) {
        if (scopes == null) return false;
        for (String scope : scopes.split(" +")) {
            if (MICROSOFT_SCOPE.equalsIgnoreCase(scope) || "Files.ReadWrite.AppFolder".equalsIgnoreCase(scope)) return true;
        }
        return false;
    }
    private static boolean hasControl(String value) {
        for (int index = 0; index < value.length(); index++) if (Character.isISOControl(value.charAt(index))) return true;
        return false;
    }
}
