package nl.privaterecorder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Native Drive/Graph backup. All files remain local; this client never overwrites or deletes evidence. */
public final class RemoteCloud {
    private static final String GOOGLE = "https://www.googleapis.com/drive/v3";
    private static final String GRAPH = "https://graph.microsoft.com/v1.0";
    private static final String FOLDER_MIME = "application/vnd.google-apps.folder";
    private static final String ROOT_NAME = "Private Recorder Evidence";
    private static final String MARKER = "private-recorder";
    private static final String GOOGLE_FIELDS = "id,name,mimeType,size,webViewLink";
    private static final String GRAPH_FIELDS = "id,name,size,folder,file,parentReference,webUrl";
    private static final long MAX_FILE_BYTES = 240L * 1024 * 1024;

    public interface TokenSource {
        String get() throws Exception;
        void invalidate(String badToken) throws Exception;
    }

    public static final class Folder {
        public final String provider, id, accountId, accountEmail, label, webUrl;

        Folder(String provider, String id, String accountId, String accountEmail, String label, String webUrl) {
            this.provider = provider;
            this.id = id;
            this.accountId = accountId;
            this.accountEmail = accountEmail;
            this.label = label;
            this.webUrl = webUrl;
        }

        /** Changing account or destination invalidates old local upload receipts. */
        public String scope() { return provider + "/" + encode(accountId) + "/" + encode(id); }
    }

    public static final class CloudException extends IOException {
        public final int status;
        public final long retryAfterSeconds;

        CloudException(int status, long retryAfterSeconds) {
            super("Cloud request returned HTTP " + status + ". " +
                    (status == 401 || status == 403 ? "Reconnect this cloud account if the problem persists."
                            : "Files remain on the phone; sync will retry."));
            this.status = status;
            this.retryAfterSeconds = retryAfterSeconds;
        }
    }

    private final String provider;
    private final TokenSource tokens;
    private final HttpTransport transport;

    public RemoteCloud(String provider, TokenSource tokens) {
        this(provider, tokens, new HttpTransport.UrlConnection());
    }

    RemoteCloud(String provider, TokenSource tokens, HttpTransport transport) {
        if (!"google".equals(provider) && !"onedrive".equals(provider)) {
            throw new IllegalArgumentException("Unknown cloud provider");
        }
        if (tokens == null || transport == null) throw new IllegalArgumentException("Missing cloud client dependency");
        this.provider = provider;
        this.tokens = tokens;
        this.transport = transport;
    }

    public Folder ensureFolder() throws Exception {
        if ("google".equals(provider)) {
            JSONObject user = json("GET", GOOGLE + "/about?fields=" + encode("user(permissionId,emailAddress,displayName)"), null)
                    .getJSONObject("user");
            String accountId = required(user, "permissionId");
            String email = required(user, "emailAddress");
            JSONObject root = googleFolder("root", ROOT_NAME);
            return new Folder(provider, required(root, "id"), accountId, email, ROOT_NAME,
                    root.optString("webViewLink", ""));
        }
        // Files.ReadWrite.AppFolder limits access to this application's own OneDrive folder.
        JSONObject appRoot = json("GET", GRAPH + "/me/drive/special/approot?$select=" + encode(GRAPH_FIELDS), null);
        requireFolder(appRoot);
        String drive = required(appRoot.getJSONObject("parentReference"), "driveId");
        JSONObject root = graphFolder(drive, required(appRoot, "id"), ROOT_NAME);
        return new Folder(provider, required(root, "id"), drive, "", ROOT_NAME, root.optString("webUrl", ""));
    }

    /** Uploads immutable snapshots, then independently downloads and SHA-256 verifies every byte. */
    public JSONObject uploadVerified(Folder folder, String bundleKey, List<File> files) throws Exception {
        if (folder == null || !provider.equals(folder.provider)) throw new IllegalArgumentException("Wrong cloud folder");
        validateName(bundleKey);
        if (files == null || files.isEmpty()) throw new IllegalArgumentException("Empty evidence bundle");
        List<Snapshot> snapshots = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (File file : files) {
            if (file == null) throw new IllegalArgumentException("Missing local file");
            validateName(file.getName());
            if (!names.add(file.getName().toLowerCase(Locale.ROOT))) throw new IOException("Duplicate evidence filenames");
            snapshots.add(new Snapshot(file));
        }
        JSONObject bundle = "google".equals(provider) ? googleFolder(folder.id, bundleKey)
                : graphFolder(folder.accountId, folder.id, bundleKey);
        String parent = required(bundle, "id");
        JSONArray receipts = new JSONArray();
        for (Snapshot snapshot : snapshots) {
            checkCancelled();
            JSONObject remote = "google".equals(provider) ? googleFile(parent, snapshot)
                    : graphFile(folder.accountId, parent, snapshot);
            if (isFolder(remote)) throw new IOException("A cloud folder conflicts with an evidence filename; nothing was overwritten.");
            String remoteId = required(remote, "id");
            if (remote.has("size") && remote.getLong("size") != snapshot.size) {
                throw new IOException("Cloud file size differs from the local evidence; nothing was overwritten.");
            }
            String downloadUrl = "google".equals(provider) ? GOOGLE + "/files/" + encode(remoteId) + "?alt=media"
                    : graphItem(folder.accountId, remoteId) + "/content";
            String downloadedHash = downloadHash(downloadUrl, snapshot.size);
            if (!snapshot.sha256.equals(downloadedHash)) {
                throw new IOException("Cloud SHA-256 verification failed; conflicting data was not overwritten.");
            }
            snapshot.verifyUnchanged();
            receipts.put(new JSONObject().put("name", snapshot.file.getName()).put("remoteId", remoteId)
                    .put("bytes", snapshot.size).put("sha256", snapshot.sha256)
                    .put("verification", "authenticated-download-sha256"));
        }
        return new JSONObject().put("provider", provider).put("scope", folder.scope())
                .put("accountId", folder.accountId).put("rootFolderId", folder.id)
                .put("bundleKey", bundleKey).put("bundleFolderId", parent)
                .put("verifiedAtUtcMillis", System.currentTimeMillis()).put("files", receipts);
    }

    private JSONObject googleFolder(String parent, String name) throws Exception {
        JSONObject existing = googleFind(parent, name, true);
        if (existing != null) { requireFolder(existing); return existing; }
        JSONObject metadata = googleMetadata(parent, name).put("mimeType", FOLDER_MIME);
        JSONObject created = json("POST", GOOGLE + "/files?fields=" + encode(GOOGLE_FIELDS), bytes(metadata));
        requireFolder(created);
        return created;
    }

    private JSONObject googleFind(String parent, String name, boolean markedFolder) throws Exception {
        String query = "trashed = false and '" + escapeQuery(parent) + "' in parents and name = '" + escapeQuery(name) + "'";
        if (markedFolder) query += " and appProperties has { key='recorder' and value='" + MARKER + "' }";
        String url = GOOGLE + "/files?q=" + encode(query) + "&spaces=drive&pageSize=100&fields="
                + encode("nextPageToken,incompleteSearch,files(" + GOOGLE_FIELDS + ")");
        JSONObject result = json("GET", url, null);
        JSONArray items = result.getJSONArray("files");
        if (result.optBoolean("incompleteSearch", false) || !result.optString("nextPageToken", "").isEmpty()) {
            throw new IOException("Cloud lookup was incomplete; refusing to create a possible duplicate.");
        }
        if (items.length() > 1) throw new IOException("Multiple matching Google Drive items exist; resolve duplicates before syncing.");
        return items.length() == 0 ? null : items.getJSONObject(0);
    }

    private JSONObject googleFile(String parent, Snapshot snapshot) throws Exception {
        JSONObject existing = googleFind(parent, snapshot.file.getName(), false);
        if (existing != null) return existing;
        String boundary = "pr_" + UUID.randomUUID().toString().replace("-", "");
        byte[] prefix = ("--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n"
                + googleMetadata(parent, snapshot.file.getName()) + "\r\n--" + boundary
                + "\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        HttpTransport.Body body = new HttpTransport.Body() {
            @Override public long length() { return prefix.length + snapshot.size + suffix.length; }
            @Override public void writeTo(OutputStream output) throws IOException {
                output.write(prefix); snapshot.writeTo(output); output.write(suffix);
            }
        };
        return json("POST", "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields="
                + encode(GOOGLE_FIELDS), body, "multipart/related; boundary=" + boundary, false);
    }

    private JSONObject googleMetadata(String parent, String name) throws Exception {
        return new JSONObject().put("name", name).put("parents", new JSONArray().put(parent))
                .put("appProperties", new JSONObject().put("recorder", MARKER));
    }

    private JSONObject graphFolder(String drive, String parent, String name) throws Exception {
        JSONObject existing = graphFind(drive, parent, name);
        if (existing != null) { requireFolder(existing); return existing; }
        JSONObject metadata = new JSONObject().put("name", name).put("folder", new JSONObject())
                .put("@microsoft.graph.conflictBehavior", "fail");
        try {
            JSONObject created = json("POST", graphItem(drive, parent) + "/children", bytes(metadata));
            requireFolder(created);
            return created;
        } catch (CloudException failure) {
            if (failure.status != 409) throw failure;
            existing = graphFind(drive, parent, name);
            if (existing == null) throw failure;
            requireFolder(existing);
            return existing;
        }
    }

    private JSONObject graphFind(String drive, String parent, String name) throws Exception {
        JSONObject item = json("GET", graphItem(drive, parent) + ":/" + encode(name)
                + "?$select=" + encode(GRAPH_FIELDS), null, "application/json", true);
        if (item != null && !name.equals(item.optString("name"))) {
            throw new IOException("A cloud item has a conflicting filename; nothing was overwritten.");
        }
        return item;
    }

    private JSONObject graphFile(String drive, String parent, Snapshot snapshot) throws Exception {
        JSONObject existing = graphFind(drive, parent, snapshot.file.getName());
        if (existing != null) return existing;
        try {
            // Without this query option Graph's PUT default is REPLACE, which is never appropriate for evidence.
            return json("PUT", graphItem(drive, parent) + ":/" + encode(snapshot.file.getName())
                    + ":/content?@microsoft.graph.conflictBehavior=fail", snapshot, "application/octet-stream", false);
        } catch (CloudException failure) {
            if (failure.status != 409 && failure.status != 412) throw failure;
            existing = graphFind(drive, parent, snapshot.file.getName());
            if (existing == null) throw failure;
            return existing;
        }
    }

    private static String graphItem(String drive, String item) {
        return GRAPH + "/drives/" + encode(drive) + "/items/" + encode(item);
    }

    private boolean isFolder(JSONObject item) {
        return "google".equals(provider) ? FOLDER_MIME.equals(item.optString("mimeType"))
                : item.optJSONObject("folder") != null;
    }

    private void requireFolder(JSONObject item) throws IOException {
        if (!isFolder(item)) throw new IOException("An existing cloud item conflicts with the backup folder.");
    }

    private JSONObject json(String method, String url, HttpTransport.Body body) throws Exception {
        return json(method, url, body, "application/json; charset=UTF-8", false);
    }

    private JSONObject json(String method, String url, HttpTransport.Body body, String contentType, boolean allowMissing)
            throws Exception {
        try (HttpTransport.Response response = authorized(method, url, body, contentType)) {
            if (allowMissing && response.status == 404) return null;
            requireSuccess(response);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = response.body.read(buffer)) != -1) {
                checkCancelled();
                if (output.size() + count > 2 * 1024 * 1024) throw new IOException("Cloud metadata response too large.");
                output.write(buffer, 0, count);
            }
            try { return new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8)); }
            catch (Exception invalid) { throw new IOException("Cloud returned invalid metadata."); }
        }
    }

    private HttpTransport.Response authorized(String method, String url, HttpTransport.Body body, String contentType)
            throws Exception {
        requireHttps(url, "google".equals(provider) ? "www.googleapis.com" : "graph.microsoft.com");
        for (int attempt = 0; attempt < 2; attempt++) {
            checkCancelled();
            String token = tokens.get();
            if (token == null || token.isEmpty() || token.indexOf('\r') >= 0 || token.indexOf('\n') >= 0) {
                throw new IOException("Reconnect the cloud account before syncing.");
            }
            Map<String, String> headers = new HashMap<>();
            headers.put("Authorization", "Bearer " + token);
            headers.put("Accept", "application/json");
            headers.put("Accept-Encoding", "identity");
            if (body != null) headers.put("Content-Type", contentType);
            HttpTransport.Response response = transport.execute(method, url, headers, body);
            if (response.status != 401 || attempt == 1) return response;
            response.close();
            tokens.invalidate(token);
        }
        throw new IOException("Cloud authentication retry exhausted.");
    }

    private String downloadHash(String initialUrl, long expectedBytes) throws Exception {
        String url = initialUrl;
        for (int redirects = 0; redirects <= 4; redirects++) {
            checkCancelled();
            HttpTransport.Response opened;
            if (redirects == 0) opened = authorized("GET", url, null, "application/octet-stream");
            else {
                requireHttps(url, null);
                Map<String, String> headers = new HashMap<>();
                headers.put("Accept-Encoding", "identity");
                opened = transport.execute("GET", url, headers, null);
            }
            try (HttpTransport.Response response = opened) {
                if (response.status == 301 || response.status == 302 || response.status == 303
                        || response.status == 307 || response.status == 308) {
                    // Graph returns a short-lived preauthenticated CDN URL; never forward our bearer token.
                    String location = response.header("Location");
                    if (location.isEmpty()) throw new IOException("Cloud download redirect is missing a destination.");
                    try { url = new URI(url).resolve(location).toString(); }
                    catch (Exception invalid) { throw new IOException("Cloud download redirect is invalid."); }
                    requireHttps(url, null);
                    continue;
                }
                if (response.status != 200) throw cloudError(response);
                return digest(response.body, expectedBytes, null);
            }
        }
        throw new IOException("Cloud download redirected too many times.");
    }

    private static void requireHttps(String url, String expectedHost) throws IOException {
        try {
            URI uri = new URI(url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getFragment() != null
                    || (expectedHost != null && !expectedHost.equalsIgnoreCase(uri.getHost()))) {
                throw new IOException("Cloud URL did not match the secure destination policy.");
            }
        } catch (java.net.URISyntaxException invalid) { throw new IOException("Cloud URL is invalid."); }
    }

    private static void requireSuccess(HttpTransport.Response response) throws CloudException {
        if (response.status < 200 || response.status >= 300) throw cloudError(response);
    }

    private static CloudException cloudError(HttpTransport.Response response) {
        long retry = 0;
        try { retry = Math.max(0, Math.min(86_400, Long.parseLong(response.header("Retry-After")))); }
        catch (NumberFormatException ignored) { }
        return new CloudException(response.status, retry);
    }

    private static HttpTransport.Body bytes(JSONObject object) {
        byte[] data = object.toString().getBytes(StandardCharsets.UTF_8);
        return new HttpTransport.Body() {
            @Override public long length() { return data.length; }
            @Override public void writeTo(OutputStream output) throws IOException { output.write(data); }
        };
    }

    private static String required(JSONObject object, String key) throws IOException {
        String value = object.optString(key, "");
        if (value.isEmpty() || value.length() > 2048) throw new IOException("Cloud metadata is missing a required identifier.");
        return value;
    }

    private static String encode(String value) {
        try { return URLEncoder.encode(value, "UTF-8").replace("+", "%20"); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }

    private static String escapeQuery(String value) { return value.replace("\\", "\\\\").replace("'", "\\'"); }

    private static void validateName(String name) {
        if (name == null || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,179}") || name.contains("..") || name.endsWith(".")) {
            throw new IllegalArgumentException("Unsafe evidence bundle or file name");
        }
    }

    private static void checkCancelled() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Cloud sync was interrupted; local files are safe.");
    }

    private static String digest(InputStream input, long expectedBytes, OutputStream copy) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int count;
            while ((count = input.read(buffer)) != -1) {
                checkCancelled();
                total += count;
                if (total > expectedBytes) throw new IOException("Evidence size changed during transfer.");
                digest.update(buffer, 0, count);
                if (copy != null) copy.write(buffer, 0, count);
            }
            if (total != expectedBytes) throw new IOException("Evidence transfer was truncated.");
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static final class Snapshot implements HttpTransport.Body {
        final File file;
        final long size;
        final String sha256;

        Snapshot(File file) throws IOException {
            this.file = file;
            if (!file.isFile()) throw new IOException("Local evidence file is missing.");
            size = file.length();
            if (size > MAX_FILE_BYTES) throw new IOException("Evidence file exceeds the cloud upload limit.");
            try (InputStream input = new FileInputStream(file)) { sha256 = digest(input, size, null); }
        }

        @Override public long length() { return size; }

        @Override public void writeTo(OutputStream output) throws IOException {
            try (InputStream input = new FileInputStream(file)) {
                if (!sha256.equals(digest(input, size, output))) throw new IOException("Local evidence changed during upload.");
            }
        }

        void verifyUnchanged() throws IOException {
            try (InputStream input = new FileInputStream(file)) {
                if (!sha256.equals(digest(input, size, null))) throw new IOException("Local evidence changed during cloud verification.");
            }
        }
    }
}
