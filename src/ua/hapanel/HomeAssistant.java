package ua.hapanel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

final class HomeAssistant {
    private final String address;
    private final String token;

    HomeAssistant(String address, String token) {
        this.address = address;
        this.token = token;
    }

    static String normalizeAddress(String text) {
        URI uri = URI.create(text.trim());
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))
                || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) {
            throw new IllegalArgumentException();
        }
        return uri.getScheme().toLowerCase(java.util.Locale.US) + "://" + uri.getRawAuthority();
    }

    JSONObject state(String entity) throws Exception {
        return new JSONObject(new String(request("/api/states/" + entity, null, 65536, 4000), "UTF-8"));
    }

    JSONArray calendar(String entity, String start, String end) throws Exception {
        return array("/api/calendars/" + entity + "?start=" + encode(start) + "&end=" + encode(end));
    }

    JSONArray history(String entity, String start, String end) throws Exception {
        return array("/api/history/period/" + encode(start) + "?filter_entity_id=" + entity
            + "&end_time=" + encode(end) + "&no_attributes&skip_initial_state");
    }

    byte[] camera(String entity) throws Exception {
        return request("/api/camera_proxy/" + entity + "?width=640&height=360", null, 1048576, 13000);
    }

    private JSONArray array(String path) throws Exception {
        return new JSONArray(new String(request(path, null, 524288, 4000), "UTF-8"));
    }

    private static String encode(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8");
    }

    void callControls(List<String> entities, String service) throws Exception {
        JSONObject body = new JSONObject().put("entity_id", new JSONArray(entities));
        request("/api/services/homeassistant/" + service, body, 0, 4000);
    }

    private byte[] request(String path, JSONObject body, int limit, int readTimeout) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address + path).openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(readTimeout);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                byte[] payload = body.toString().getBytes("UTF-8");
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setFixedLengthStreamingMode(payload.length);
                OutputStream stream = connection.getOutputStream();
                try {
                    stream.write(payload);
                } finally {
                    stream.close();
                }
            }
            int code;
            try {
                code = connection.getResponseCode();
            } catch (IOException failure) {
                // Jelly Bean throws on a 401 response without WWW-Authenticate.
                if (!"No authentication challenges found".equals(failure.getMessage())) throw failure;
                code = 401;
            }
            if (code < 200 || code >= 300) {
                throw new HttpError(code);
            }
            if (body != null) return null;
            InputStream stream = connection.getInputStream();
            try {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    if (bytes.size() + count > limit) throw new IOException();
                    bytes.write(buffer, 0, count);
                }
                return bytes.toByteArray();
            } finally {
                stream.close();
            }
        } finally {
            connection.disconnect();
        }
    }

    static final class HttpError extends IOException {
        final int code;

        HttpError(int code) {
            this.code = code;
        }
    }
}
