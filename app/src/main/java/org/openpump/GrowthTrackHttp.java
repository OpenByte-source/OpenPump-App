package org.openpump;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;

/** Fixed-origin HTTPS only, with no redirect forwarding, cookies, caches or payload logging. */
final class GrowthTrackHttp implements GrowthTrackClient.Transport {
    private volatile HttpsURLConnection active;
    private android.net.Network network;
    synchronized void useNetwork(android.net.Network value) { network = value; }
    private long generation, operationGeneration;
    synchronized long ticket() { return generation; }
    synchronized void enter(long ticket) throws IOException {
        if (ticket != generation) throw new IOException("GrowthTrack operation was cancelled before it started");
        operationGeneration = ticket;
    }
    synchronized void cancel() { generation++; HttpsURLConnection connection = active; if (connection != null) connection.disconnect(); }
    @Override public GrowthTrackClient.Response request(String method, String path, Map<String, String> headers, String body) throws IOException {
        if (!(path.equals("/oauth-token") || path.equals("/partner-ingest") || path.matches("/partner-routines(?:\\?id=[0-9a-fA-F-]{36})?")))
            throw new IOException("Unsupported GrowthTrack endpoint");
        if (!("GET".equals(method) || "POST".equals(method))) throw new IOException("Unsupported GrowthTrack operation");
        HttpsURLConnection connection;
        synchronized (this) {
            // A queued Send may enter approve() after a Back/Cancel. Its old operation
            // ticket still cannot open HTTP, even if the client's approval resets a flag.
            if (operationGeneration != generation) throw new IOException("GrowthTrack operation was cancelled");
            URL endpoint = new URL(GrowthTrackProtocol.API + path);
            connection = (HttpsURLConnection) (network == null ? endpoint.openConnection() : network.openConnection(endpoint)); active = connection;
        }
        try {
            connection.setInstanceFollowRedirects(false); connection.setUseCaches(false); connection.setConnectTimeout(15_000); connection.setReadTimeout(20_000);
            connection.setRequestMethod(method);
            for (Map.Entry<String, String> h : headers.entrySet()) connection.setRequestProperty(h.getKey(), h.getValue());
            if (body != null) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                if (bytes.length > GrowthTrackProtocol.MAX_BYTES) throw new IOException("GrowthTrack request exceeds its byte limit");
                connection.setDoOutput(true); connection.setFixedLengthStreamingMode(bytes.length);
                try (java.io.OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            }
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) throw new IOException("GrowthTrack unexpectedly redirected the request");
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String response = "";
            if (stream != null) try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = in.read(buffer)) != -1) { if (out.size() + n > 2_000_000) throw new IOException("GrowthTrack response exceeds its byte limit"); out.write(buffer, 0, n); }
                response = new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
            return new GrowthTrackClient.Response(status, response, connection.getHeaderField("Retry-After"));
        } catch (IOException e) { throw new IOException("GrowthTrack network request failed"); }
        finally { connection.disconnect(); if (active == connection) active = null; }
    }
}
