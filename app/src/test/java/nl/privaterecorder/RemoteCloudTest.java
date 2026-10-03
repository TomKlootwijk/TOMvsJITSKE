package nl.privaterecorder;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;

public final class RemoteCloudTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private static final String HASH_ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    private static final String GOOGLE_FOLDER = "{\"id\":\"bundle\",\"name\":\"bundle_1\",\"mimeType\":\"application/vnd.google-apps.folder\"}";
    private static final String GRAPH_FOLDER = "{\"id\":\"bundle\",\"name\":\"bundle_1\",\"folder\":{}}";
    private static final String GOOGLE_FILE = "{\"id\":\"audio\",\"name\":\"audio.aac\",\"size\":\"3\",\"mimeType\":\"application/octet-stream\"}";
    private static final String GRAPH_FILE = "{\"id\":\"audio\",\"name\":\"audio.aac\",\"size\":3,\"file\":{}}";

    @Test public void googleCreatesScopedFolderAndCapturesStableAccountIdentity() throws Exception {
        Script http = new Script();
        http.add("GET", "/about?", 200, "{\"user\":{\"permissionId\":\"user-id\",\"emailAddress\":\"person@example.test\"}}");
        http.add("GET", "/files?q=", 200, "{\"files\":[]}");
        http.add("POST", "/files?fields=", 200, GOOGLE_FOLDER);
        RemoteCloud.Folder result = client("google", http).ensureFolder();
        assertEquals("person@example.test", result.accountEmail);
        assertEquals("google/user-id/bundle", result.scope());
        String lookup = java.net.URLDecoder.decode(http.calls.get(1).url, "UTF-8");
        assertTrue(lookup.contains("'root' in parents"));
        assertTrue(lookup.contains("appProperties has { key='recorder' and value='private-recorder' }"));
        JSONObject metadata = new JSONObject(http.calls.get(2).utf8Body());
        assertEquals("Private Recorder Evidence", metadata.getString("name"));
        assertEquals("private-recorder", metadata.getJSONObject("appProperties").getString("recorder"));
        http.done();
    }

    @Test public void onedriveUsesOnlyTheApplicationFolderAndStableDriveIdentity() throws Exception {
        Script http = new Script();
        http.add("GET", "/me/drive/special/approot?", 200,
                "{\"id\":\"app-root\",\"folder\":{},\"parentReference\":{\"driveId\":\"drive-id\"}}");
        http.add("GET", "/drives/drive-id/items/app-root:/Private%20Recorder%20Evidence?", 404, "{}");
        http.add("POST", "/drives/drive-id/items/app-root/children", 201,
                "{\"id\":\"evidence-root\",\"name\":\"Private Recorder Evidence\",\"folder\":{}}");
        RemoteCloud.Folder result = client("onedrive", http).ensureFolder();
        assertEquals("onedrive/drive-id/evidence-root", result.scope());
        assertEquals("fail", new JSONObject(http.calls.get(2).utf8Body()).getString("@microsoft.graph.conflictBehavior"));
        http.done();
    }

    @Test public void googleMultipartUploadStreamsBytesAndReturnsOnlyReadbackVerifiedReceipt() throws Exception {
        Script http = googleBundle();
        http.add("GET", "/files?q=", 200, "{\"files\":[]}");
        http.add("POST", "/upload/drive/v3/files?uploadType=multipart", 200, GOOGLE_FILE);
        http.add("GET", "/files/audio?alt=media", 200, "abc");
        JSONObject receipt = client("google", http).uploadVerified(folder("google"), "bundle_1", Arrays.asList(file("audio.aac", "abc")));
        JSONObject verified = receipt.getJSONArray("files").getJSONObject(0);
        assertEquals(HASH_ABC, verified.getString("sha256"));
        assertEquals("authenticated-download-sha256", verified.getString("verification"));
        Call upload = http.calls.get(2);
        assertTrue(upload.headers.get("Content-Type").startsWith("multipart/related; boundary="));
        assertTrue(upload.utf8Body().contains("\r\n\r\nabc\r\n--pr_"));
        assertTrue(upload.utf8Body().contains("\"parents\":[\"bundle\"]"));
        http.done();
    }

    @Test public void repeatedGoogleUploadFindsAndVerifiesExistingFileWithoutAnyMutation() throws Exception {
        Script http = googleBundle();
        http.add("GET", "/files?q=", 200, "{\"files\":[" + GOOGLE_FILE + "]}");
        http.add("GET", "/files/audio?alt=media", 200, "abc");
        client("google", http).uploadVerified(folder("google"), "bundle_1", Arrays.asList(file("audio.aac", "abc")));
        assertTrue(http.calls.stream().allMatch(call -> call.method.equals("GET")));
        http.done();
    }

    @Test public void ambiguousUploadSuccessIsNotReplayedAndNextAttemptSearchesBeforeUploading() throws Exception {
        Script http = googleBundle();
        http.add("GET", "/files?q=", 200, "{\"files\":[]}");
        http.steps.add(call -> { assertEquals("POST", call.method); throw new IOException("connection lost after server commit"); });
        RemoteCloud cloud = client("google", http);
        File audio = file("audio.aac", "abc");
        assertThrows(IOException.class, () -> cloud.uploadVerified(folder("google"), "bundle_1", Arrays.asList(audio)));
        assertEquals(3, http.calls.size());
        http.add("GET", "/files?q=", 200, "{\"files\":[" + GOOGLE_FOLDER + "]}");
        http.add("GET", "/files?q=", 200, "{\"files\":[" + GOOGLE_FILE + "]}");
        http.add("GET", "/files/audio?alt=media", 200, "abc");
        cloud.uploadVerified(folder("google"), "bundle_1", Arrays.asList(audio));
        assertEquals(1, http.calls.stream().filter(call -> call.method.equals("POST")).count());
        http.done();
    }

    @Test public void existingDifferentBytesAreNeverOverwrittenOrAcknowledged() throws Exception {
        Script http = googleBundle();
        http.add("GET", "/files?q=", 200, "{\"files\":[" + GOOGLE_FILE + "]}");
        http.add("GET", "/files/audio?alt=media", 200, "xyz");
        IOException error = assertThrows(IOException.class, () -> client("google", http)
                .uploadVerified(folder("google"), "bundle_1", Arrays.asList(file("audio.aac", "abc"))));
        assertTrue(error.getMessage().contains("SHA-256"));
        assertTrue(http.calls.stream().allMatch(call -> call.method.equals("GET")));
        http.done();
    }

    @Test public void shortCloudReadbackCannotReceiveSuccessfulReceipt() throws Exception {
        Script http = googleBundle();
        http.add("GET", "/files?q=", 200, "{\"files\":[" + GOOGLE_FILE + "]}");
        http.add("GET", "/files/audio?alt=media", 200, "ab");
        IOException error = assertThrows(IOException.class, () -> client("google", http)
                .uploadVerified(folder("google"), "bundle_1", Arrays.asList(file("audio.aac", "abc"))));
        assertTrue(error.getMessage().contains("truncated"));
        http.done();
    }

    @Test public void duplicateCloudFoldersStopSyncBeforeAnyUpload() throws Exception {
        Script http = new Script();
        http.add("GET", "/files?q=", 200, "{\"files\":[" + GOOGLE_FOLDER + "," + GOOGLE_FOLDER + "]}");
        IOException error = assertThrows(IOException.class, () -> client("google", http)
                .uploadVerified(folder("google"), "bundle_1", Arrays.asList(file("audio.aac", "abc"))));
        assertTrue(error.getMessage().contains("Multiple matching"));
        http.done();
    }

    @Test public void incompleteGoogleListingCannotCreateDuplicateObjects() throws Exception {
        Script http = new Script();
        http.add("GET", "/files?q=", 200, "{\"files\":[],\"incompleteSearch\":true}");
        assertThrows(IOException.class, () -> client("google", http)
                .uploadVerified(folder("google"), "bundle_1", Arrays.asList(file("audio.aac", "abc"))));
        http.done();
    }

    @Test public void graphConflictFailAndCdnRedirectNeverForwardBearerToken() throws Exception {
        Script http = graphBundle();
        http.add("GET", "/items/bundle:/audio.aac?", 404, "{}");
        http.add("PUT", "/items/bundle:/audio.aac:/content?@microsoft.graph.conflictBehavior=fail", 201, GRAPH_FILE);
        http.steps.add(call -> {
            assertTrue(call.url.startsWith("https://graph.microsoft.com/"));
            assertEquals("Bearer token", call.headers.get("Authorization"));
            return response(302, "", Collections.singletonMap("Location", Arrays.asList("https://files.1drv.com/signed-private-url")));
        });
        http.steps.add(call -> {
            assertEquals("https://files.1drv.com/signed-private-url", call.url);
            assertFalse(call.headers.containsKey("Authorization"));
            return response(200, "abc", null);
        });
        client("onedrive", http).uploadVerified(folder("onedrive"), "bundle_1", Arrays.asList(file("audio.aac", "abc")));
        assertEquals("abc", http.calls.get(2).utf8Body());
        http.done();
    }

    @Test public void graphConflictChecksExistingBytesWithoutReplacingThem() throws Exception {
        Script http = graphBundle();
        http.add("GET", "/items/bundle:/audio.aac?", 404, "{}");
        http.add("PUT", "conflictBehavior=fail", 409, "{\"error\":\"nameAlreadyExists\"}");
        http.add("GET", "/items/bundle:/audio.aac?", 200, GRAPH_FILE);
        http.add("GET", "/items/audio/content", 200, "abc");
        client("onedrive", http).uploadVerified(folder("onedrive"), "bundle_1", Arrays.asList(file("audio.aac", "abc")));
        assertEquals(1, http.calls.stream().filter(call -> call.method.equals("PUT")).count());
        http.done();
    }

    @Test public void insecureCdnRedirectIsRejectedBeforeRequest() throws Exception {
        Script http = graphBundle();
        http.add("GET", "/items/bundle:/audio.aac?", 200, GRAPH_FILE);
        http.steps.add(call -> response(302, "", Collections.singletonMap("Location", Arrays.asList("http://files.1drv.com/private"))));
        assertThrows(IOException.class, () -> client("onedrive", http)
                .uploadVerified(folder("onedrive"), "bundle_1", Arrays.asList(file("audio.aac", "abc"))));
        assertEquals(3, http.calls.size());
        http.done();
    }

    @Test public void expiredTokenIsRefreshedOnceAndRepeatedUnauthorizedIsBoundedAndRedacted() throws Exception {
        Script http = new Script();
        http.add("GET", "/about?", 401, "{\"secret\":\"token-leak\"}");
        http.add("GET", "/about?", 401, "{\"secret\":\"token-leak\"}");
        Tokens tokens = new Tokens();
        RemoteCloud cloud = new RemoteCloud("google", tokens, http);
        RemoteCloud.CloudException error = assertThrows(RemoteCloud.CloudException.class, cloud::ensureFolder);
        assertEquals(401, error.status);
        assertEquals(1, tokens.invalidations);
        assertEquals("Bearer token", http.calls.get(0).headers.get("Authorization"));
        assertEquals("Bearer refreshed", http.calls.get(1).headers.get("Authorization"));
        assertFalse(error.getMessage().contains("token-leak"));
        http.done();
    }

    @Test public void metadataRedirectCannotForwardAnAccessTokenToAnotherHost() throws Exception {
        Script http = new Script();
        http.steps.add(call -> response(302, "", Collections.singletonMap("Location", Arrays.asList("https://attacker.example/"))));
        assertThrows(RemoteCloud.CloudException.class, () -> client("google", http).ensureFolder());
        assertEquals(1, http.calls.size());
        http.done();
    }

    @Test public void throttlingIsReturnedToSchedulerWithoutTightRetryLoop() throws Exception {
        Script http = new Script();
        http.steps.add(call -> response(429, "", Collections.singletonMap("Retry-After", Arrays.asList("120"))));
        RemoteCloud.CloudException error = assertThrows(RemoteCloud.CloudException.class, () -> client("google", http).ensureFolder());
        assertEquals(120, error.retryAfterSeconds);
        assertEquals(1, http.calls.size());
        http.done();
    }

    @Test public void unsafeNamesAndDuplicateCaseInsensitiveNamesFailBeforeRemoteMutation() throws Exception {
        Script http = new Script();
        RemoteCloud cloud = client("google", http);
        File audio = file("audio.aac", "abc");
        for (String name : Arrays.asList("../escape", "a/b", "a\\b", "x?overwrite", "a..b", ".hidden", "end.")) {
            assertThrows(IllegalArgumentException.class, () -> cloud.uploadVerified(folder("google"), name, Arrays.asList(audio)));
        }
        assertThrows(IOException.class, () -> cloud.uploadVerified(folder("google"), "bundle_1", Arrays.asList(audio, audio)));
        assertTrue(http.calls.isEmpty());
    }

    private File file(String name, String text) throws IOException {
        File file = temp.newFile(name);
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    private static RemoteCloud.Folder folder(String provider) {
        return new RemoteCloud.Folder(provider, "root", "account", "", "Private Recorder Evidence", "");
    }

    private static RemoteCloud client(String provider, Script http) { return new RemoteCloud(provider, new Tokens(), http); }

    private static Script googleBundle() {
        Script http = new Script();
        http.add("GET", "/files?q=", 200, "{\"files\":[" + GOOGLE_FOLDER + "]}");
        return http;
    }

    private static Script graphBundle() {
        Script http = new Script();
        http.add("GET", "/items/root:/bundle_1?", 200, GRAPH_FOLDER);
        return http;
    }

    private static final class Tokens implements RemoteCloud.TokenSource {
        int invalidations;
        @Override public String get() { return invalidations == 0 ? "token" : "refreshed"; }
        @Override public void invalidate(String badToken) { assertEquals("token", badToken); invalidations++; }
    }

    private static HttpTransport.Response response(int status, String text, Map<String, List<String>> headers) {
        return new HttpTransport.Response(status, headers,
                new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), null);
    }

    private interface Step { HttpTransport.Response run(Call call) throws IOException; }

    private static final class Call {
        final String method, url;
        final Map<String, String> headers;
        final byte[] body;
        Call(String method, String url, Map<String, String> headers, byte[] body) {
            this.method = method; this.url = url; this.headers = headers; this.body = body;
        }
        String utf8Body() { return new String(body, StandardCharsets.UTF_8); }
    }

    private static final class Script implements HttpTransport {
        final Deque<Step> steps = new ArrayDeque<>();
        final List<Call> calls = new ArrayList<>();

        void add(String method, String urlPart, int status, String text) {
            steps.add(call -> {
                assertEquals(method, call.method);
                assertTrue("Unexpected URL: " + call.url, call.url.contains(urlPart));
                return response(status, text, null);
            });
        }

        @Override public Response execute(String method, String url, Map<String, String> headers, Body body) throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (body != null) {
                body.writeTo(output);
                assertEquals(body.length(), output.size());
            }
            Call call = new Call(method, url, headers, output.toByteArray());
            calls.add(call);
            if (steps.isEmpty()) fail("Unexpected HTTP request: " + method + " " + url);
            return steps.remove().run(call);
        }

        void done() { assertTrue("Unconsumed HTTP responses: " + steps.size(), steps.isEmpty()); }
    }
}
