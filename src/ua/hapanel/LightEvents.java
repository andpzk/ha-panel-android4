package ua.hapanel;

import android.os.Build;
import java.net.URI;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLParameters;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class LightEvents extends WebSocketClient {
    private static final int SUBSCRIPTION = 1;

    interface Listener {
        void states(LightEvents source, Map<String, String> states, boolean initial);
        void pong(LightEvents source);
        void disconnected(LightEvents source, String message, boolean retry);
    }

    private final String token;
    private final JSONArray entities;
    private final Listener listener;
    private boolean initial = true;
    private int nextId = SUBSCRIPTION + 1;

    LightEvents(String address, String token, List<String> entities, Listener listener) {
        super(URI.create("ws" + address.substring(4) + "/api/websocket"), new Draft_6455(), null, 5000);
        this.token = token;
        this.entities = new JSONArray(entities);
        this.listener = listener;
        setConnectionLostTimeout(15);
    }

    @Override protected void onSetSSLParameters(SSLParameters parameters) {
        // Android 4.1 checks the HTTPS hostname below, before sending the token.
        if (Build.VERSION.SDK_INT >= 24) super.onSetSSLParameters(parameters);
    }

    @Override public void onOpen(ServerHandshake handshake) {
        if (Build.VERSION.SDK_INT < 24 && "wss".equals(getURI().getScheme())
                && !HttpsURLConnection.getDefaultHostnameVerifier().verify(getURI().getHost(), getSSLSession())) {
            listener.disconnected(this, "Сертифікат Home Assistant не відповідає адресі", false);
            close();
        }
    }

    @Override public void onMessage(String text) {
        if (!isOpen()) return;
        try {
            JSONObject message = new JSONObject(text);
            switch (message.getString("type")) {
                case "auth_required":
                    send(new JSONObject().put("type", "auth").put("access_token", token).toString());
                    break;
                case "auth_ok":
                    send(new JSONObject().put("id", SUBSCRIPTION).put("type", "subscribe_entities")
                        .put("entity_ids", entities).toString());
                    break;
                case "auth_invalid":
                    listener.disconnected(this, "Home Assistant відхилив доступ. Перевірте токен.", false);
                    break;
                case "result":
                    if (message.optInt("id") == SUBSCRIPTION && !message.optBoolean("success")) {
                        listener.disconnected(this, "Home Assistant відхилив підписку на стани", false);
                    }
                    break;
                case "event":
                    if (message.optInt("id") == SUBSCRIPTION) {
                        Map<String, String> changes = statesFrom(message.getJSONObject("event"));
                        if (initial || !changes.isEmpty()) listener.states(this, changes, initial);
                        initial = false;
                    }
                    break;
                case "pong":
                    listener.pong(this);
                    break;
            }
        } catch (JSONException | IllegalStateException failure) {
            onError(failure);
        }
    }

    void pingHomeAssistant() {
        send("{\"id\":" + nextId++ + ",\"type\":\"ping\"}");
    }

    // Home Assistant sends a full initial state and then compact per-entity changes.
    static Map<String, String> statesFrom(JSONObject event) {
        Map<String, String> changes = new HashMap<>();
        JSONObject added = event.optJSONObject("a");
        if (added != null) {
            for (Iterator<?> keys = added.keys(); keys.hasNext();) {
                String entity = (String) keys.next();
                JSONObject state = added.optJSONObject(entity);
                if (state != null) changes.put(entity, state.optString("s", ""));
            }
        }
        JSONObject changed = event.optJSONObject("c");
        if (changed != null) {
            for (Iterator<?> keys = changed.keys(); keys.hasNext();) {
                String entity = (String) keys.next();
                JSONObject diff = changed.optJSONObject(entity);
                JSONObject state = diff == null ? null : diff.optJSONObject("+");
                if (state != null && state.has("s")) changes.put(entity, state.optString("s", ""));
            }
        }
        JSONArray removed = event.optJSONArray("r");
        if (removed != null) {
            for (int i = 0; i < removed.length(); i++) changes.put(removed.optString(i), "");
        }
        return changes;
    }

    @Override public void onClose(int code, String reason, boolean remote) {
        listener.disconnected(this, "Зв’язок із Home Assistant втрачено", true);
    }

    @Override public void onError(Exception failure) {
        listener.disconnected(this, "Зв’язок із Home Assistant втрачено", true);
    }
}
