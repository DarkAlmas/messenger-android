package com.darkcompany.messenger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/** Подписка Supabase Realtime (Phoenix protocol) на изменения messages/reactions одного чата. */
final class Realtime extends WebSocketListener {
    private final Api api;
    private final String chatId;
    private final Runnable onChange;
    private final OkHttpClient http = new OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build();
    private final ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor();
    private volatile WebSocket ws;
    private volatile boolean stopped = false, joined = false;
    private int ref = 0, backoff = 1;
    private volatile java.util.concurrent.ScheduledFuture<?> hb;

    private final boolean group;
    private final Runnable onKeys;
    /** id этого устройства: SKDM-события Realtime эхом приходят и отправителю — берём только адресованные нам. */
    volatile String myDevice;
    Realtime(Api api, String chatId, Runnable onChange) { this(api, chatId, false, onChange, null); }
    /** group=true: ещё и ключи группы (sender_key_distributions — RLS отдаёт только свои; group_key_epochs — смена эпохи). */
    Realtime(Api api, String chatId, boolean group, Runnable onChange, Runnable onKeys) {
        this.api = api; this.chatId = chatId; this.group = group; this.onChange = onChange; this.onKeys = onKeys;
    }

    boolean isJoined() { return joined; }
    /** broadcast-события канала chat-<id> (как на сайте: «typing» {u, stop?}). */
    volatile java.util.function.BiConsumer<String, JSONObject> onBroadcast;
    void broadcast(String event, JSONObject payload) {
        WebSocket w = ws; if (w == null || !joined) return;
        ex.execute(() -> { try { send(w, topic(), "broadcast", new JSONObject().put("type", "broadcast").put("event", event).put("payload", payload)); } catch (Exception ignored) { } });
    }

    void start() { ex.execute(this::connect); }

    void stop() {
        stopped = true; joined = false;
        WebSocket w = ws; if (w != null) w.close(1000, "bye");
        ex.shutdownNow();
    }

    private String topic() { return "realtime:chat-" + chatId; }

    private void connect() {
        if (stopped) return;
        try {
            String token = api.accessToken();
            Request r = new Request.Builder().url(Api.URL.replace("https://", "wss://") + "/realtime/v1/websocket?apikey=" + Api.KEY + "&vsn=1.0.0").build();
            ws = http.newWebSocket(r, new WebSocketListener() {
                @Override public void onOpen(WebSocket w, Response resp) {
                    try {
                        JSONArray pc = new JSONArray();
                        String[] tables = group ? new String[]{"messages", "reactions", "sender_key_distributions", "group_key_epochs", "chat_members"} : new String[]{"messages", "reactions", "chat_members"}; // chat_members: прочтения (✓✓)
                        for (String t : tables) {
                            JSONObject o = new JSONObject().put("event", t.equals("sender_key_distributions") || t.equals("group_key_epochs") ? "INSERT" : "*")
                                    .put("schema", "public").put("table", t);
                            if (!t.equals("reactions")) o.put("filter", "chat_id=eq." + chatId);
                            pc.put(o);
                        }
                        JSONObject cfg = new JSONObject().put("postgres_changes", pc)
                                .put("broadcast", new JSONObject().put("self", false)).put("private", true).put("presence", new JSONObject().put("key", ""));
                        send(w, topic(), "phx_join", new JSONObject().put("config", cfg).put("access_token", token));
                    } catch (Exception ignored) { }
                    if (hb != null) hb.cancel(false);
                    hb = ex.scheduleAtFixedRate(() -> { try { send(w, "phoenix", "heartbeat", new JSONObject()); } catch (Exception ignored) { } }, 25, 25, TimeUnit.SECONDS);
                }
                @Override public void onMessage(WebSocket w, String text) {
                    try {
                        JSONObject m = new JSONObject(text);
                        String ev = m.optString("event");
                        if ("phx_reply".equals(ev) && topic().equals(m.optString("topic"))) {
                            String st = m.optJSONObject("payload") != null ? m.getJSONObject("payload").optString("status") : "";
                            if ("ok".equals(st)) { joined = true; backoff = 1; }
                        } else if ("postgres_changes".equals(ev)) {
                            JSONObject data = m.optJSONObject("payload") == null ? null : m.getJSONObject("payload").optJSONObject("data");
                            String table = data == null ? "" : data.optString("table");
                            if (table.equals("sender_key_distributions")) {
                                JSONObject rec = data.optJSONObject("record");
                                String rd = rec == null ? null : rec.optString("recipient_device", null);
                                if (rd == null || !rd.equals(myDevice)) return; // эхо своей раздачи или чужое
                            }
                            if (onKeys != null && (table.equals("sender_key_distributions") || table.equals("group_key_epochs"))) onKeys.run();
                            onChange.run();
                        } else if ("broadcast".equals(ev) && topic().equals(m.optString("topic"))) {
                            JSONObject pl = m.optJSONObject("payload");
                            if (pl != null && onBroadcast != null) onBroadcast.accept(pl.optString("event"), pl.optJSONObject("payload"));
                        } else if ("phx_error".equals(ev) || "phx_close".equals(ev)) {
                            joined = false;
                        }
                    } catch (Exception ignored) { }
                }
                @Override public void onClosed(WebSocket w, int code, String reason) { reconnect(); }
                @Override public void onFailure(WebSocket w, Throwable t, Response resp) { reconnect(); }
            });
        } catch (Exception e) { reconnect(); }
    }

    private void reconnect() {
        joined = false;
        if (hb != null) hb.cancel(false);
        if (stopped) return;
        int d = Math.min(backoff, 60); backoff = Math.min(backoff * 2, 60);
        try { ex.schedule(this::connect, d, TimeUnit.SECONDS); } catch (Exception ignored) { }
    }

    private synchronized void send(WebSocket w, String topic, String event, JSONObject payload) throws Exception {
        w.send(new JSONObject().put("topic", topic).put("event", event).put("payload", payload).put("ref", String.valueOf(++ref)).toString());
    }
}
