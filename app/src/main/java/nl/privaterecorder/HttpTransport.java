package nl.privaterecorder;

import java.io.ByteArrayInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Streaming HTTP boundary, injectable for protocol tests. Never follows redirects implicitly. */
interface HttpTransport {
    interface Body {
        long length();
        void writeTo(OutputStream output) throws IOException;
    }

    Response execute(String method, String url, Map<String, String> headers, Body body) throws IOException;

    final class Response implements Closeable {
        final int status;
        final Map<String, List<String>> headers;
        final InputStream body;
        private final Runnable disconnect;

        Response(int status, Map<String, List<String>> headers, InputStream body, Runnable disconnect) {
            this.status = status;
            this.headers = headers == null ? Collections.emptyMap() : headers;
            this.body = body == null ? new ByteArrayInputStream(new byte[0]) : body;
            this.disconnect = disconnect;
        }

        String header(String key) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (key.equalsIgnoreCase(entry.getKey()) && entry.getValue() != null
                        && !entry.getValue().isEmpty()) return entry.getValue().get(0);
            }
            return "";
        }

        @Override public void close() throws IOException {
            try { body.close(); }
            finally { if (disconnect != null) disconnect.run(); }
        }
    }

    final class UrlConnection implements HttpTransport {
        private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "cloud-http-deadlines");
            thread.setDaemon(true);
            return thread;
        });

        @Override public Response execute(String method, String url, Map<String, String> headers, Body body)
                throws IOException {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            // Read/connect timeouts alone do not bound a stalled upload write. Keep the watchdog alive
            // through response consumption as well, so a trickling peer cannot hold a worker forever.
            ScheduledFuture<?> deadline = DEADLINES.schedule(connection::disconnect, 120, TimeUnit.SECONDS);
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(20_000);
                connection.setReadTimeout(30_000);
                connection.setUseCaches(false);
                connection.setRequestMethod(method);
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    connection.setRequestProperty(entry.getKey(), entry.getValue());
                }
                if (body != null) {
                    connection.setDoOutput(true);
                    connection.setFixedLengthStreamingMode(body.length());
                    try (OutputStream output = connection.getOutputStream()) { body.writeTo(output); }
                }
                int status = connection.getResponseCode();
                InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                return new Response(status, connection.getHeaderFields(), input, () -> {
                    deadline.cancel(false);
                    connection.disconnect();
                });
            } catch (IOException | RuntimeException error) {
                deadline.cancel(false);
                connection.disconnect();
                // In particular, do not surface signed download URLs from low-level exception messages.
                throw new IOException("Cloud network request failed; the next sync will check the server before retrying.");
            }
        }
    }
}
