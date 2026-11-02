package com.shipu.assistant;
import android.content.Context;
import android.net.Uri;
import android.webkit.WebResourceResponse;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Strictly APK-local resources; no remote request is forwarded. */
final class LocalAssetHost {
    static final String ASSET_HOST = "appassets.androidplatform.net";
    static final String ORIGIN = "https://" + ASSET_HOST;
    private final Context context;
    private final LocalInferenceRpc inferenceRpc;
    private final ArrayDeque<String> events = new ArrayDeque<String>();
    LocalAssetHost(Context context, LocalInferenceRpc rpc) { this.context = context; inferenceRpc = rpc; }
    private synchronized void trace(String path, String status) {
        if (!path.startsWith("local-omr/")) return;
        if (events.size() >= 40) events.removeFirst();
        events.addLast(status + " " + path);
    }
    synchronized org.json.JSONArray events() { return new org.json.JSONArray(events); }
    /** All web resources come from the APK. Unknown hosts never reach the network. */
    WebResourceResponse response(Uri uri, String method) {
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("Cross-Origin-Opener-Policy", "same-origin");
        headers.put("Cross-Origin-Embedder-Policy", "require-corp");
        headers.put("Cross-Origin-Resource-Policy", "same-origin");
        headers.put("X-Content-Type-Options", "nosniff");
        headers.put("Cache-Control", "no-cache");
        try {
            if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || !ASSET_HOST.equalsIgnoreCase(uri.getHost())
                || (uri.getPort() != -1 && uri.getPort() != 443) || uri.getUserInfo() != null)
                return assetError(403, "Forbidden", headers);
            if (!"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method))
                return assetError(405, "Method Not Allowed", headers);
            String path = uri.getPath();
            if (path == null || path.isEmpty() || path.equals("/")) path = "/index.html";
            if (path.startsWith("/assets/")) path = path.substring("/assets".length());
            if (!path.startsWith("/") || path.indexOf('\\') >= 0 || path.indexOf('\0') >= 0)
                return assetError(403, "Forbidden", headers);
            for (String component : path.split("/", -1))
                if (component.equals("..") || component.equals(".")) return assetError(403, "Forbidden", headers);
            if (path.startsWith("/__omr_rpc__/")) {
                headers.put("Cache-Control", "no-store");
                if (!"GET".equalsIgnoreCase(method) || uri.getQuery() != null || uri.getFragment() != null)
                    return assetError(400, "Bad Request", headers);
                String[] components = path.split("/", -1);
                if (components.length != 4) return assetError(400, "Bad Request", headers);
                try {
                    // Return immediately: only the response stream waits for model output.
                    if (inferenceRpc == null) return assetError(410, "Gone", headers);
                    InputStream response = inferenceRpc.openResponse(components[2], components[3]);
                    return new WebResourceResponse("application/octet-stream", null, 200, "OK", headers, response);
                } catch (LocalInferenceRpc.StaleRunException stale) { return assetError(410, "Gone", headers); }
                catch (IOException rejected) { return assetError(400, "Bad Request", headers); }
            }
            String filename = path.substring(1);
            if (filename.isEmpty() || filename.endsWith("/")) return assetError(404, "Not Found", headers);
            String mime = assetMimeType(filename);
            InputStream input;
            try { input = context.getAssets().open(filename, android.content.res.AssetManager.ACCESS_STREAMING); }
            catch (IOException missing) { trace(filename, "missing"); throw missing; }
            trace(filename, "served");
            if ("HEAD".equalsIgnoreCase(method)) { input.close(); input = new ByteArrayInputStream(new byte[0]); }
            boolean text = mime.startsWith("text/") || mime.equals("application/json") || mime.equals("image/svg+xml") || mime.equals("application/xml");
            return new WebResourceResponse(mime, text ? "UTF-8" : null, 200, "OK", headers, input);
        } catch (IOException missing) { return assetError(404, "Not Found", headers); }
        catch (Exception invalid) { return assetError(403, "Forbidden", headers); }
    }

    private static WebResourceResponse assetError(int status, String reason, Map<String, String> headers) {
        return new WebResourceResponse("text/plain", "UTF-8", status, reason, headers,
            new ByteArrayInputStream(reason.getBytes(StandardCharsets.UTF_8)));
    }

    private static String assetMimeType(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".html")) return "text/html";
        if (lower.endsWith(".js") || lower.endsWith(".mjs")) return "text/javascript";
        if (lower.endsWith(".css")) return "text/css";
        if (lower.endsWith(".wasm")) return "application/wasm";
        if (lower.endsWith(".json") || lower.endsWith(".map")) return "application/json";
        if (lower.endsWith(".whl") || lower.endsWith(".zip")) return "application/zip";
        if (lower.endsWith(".gz")) return "application/gzip";
        if (lower.endsWith(".tar")) return "application/x-tar";
        if (lower.endsWith(".py")) return "text/x-python";
        if (lower.endsWith(".xml") || lower.endsWith(".musicxml")) return "application/xml";
        if (lower.endsWith(".svg")) return "image/svg+xml";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        if (lower.endsWith(".wav")) return "audio/wav";
        if (lower.endsWith(".woff2")) return "font/woff2";
        if (lower.endsWith(".woff")) return "font/woff";
        if (lower.endsWith(".ttf")) return "font/ttf";
        if (lower.endsWith(".otf")) return "font/otf";
        if (lower.endsWith(".txt") || lower.endsWith(".md")) return "text/plain";
        return "application/octet-stream";
    }

}
