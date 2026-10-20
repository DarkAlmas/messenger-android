package com.darkcompany.messenger;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Сообщения, когда приложение свёрнуто или закрыто (FCM ещё не подключён): служба переднего плана опрашивает list_chats раз в 15 с,
 *  для чатов с новым входящим берёт новые сообщения, расшифровывает (кэш E2E — тот же, что у экрана чата) и показывает уведомление.
 *  Не уведомляет: о своих сообщениях, об открытом сейчас чате, о чатах «без звука», при выключенных уведомлениях. */
public class NotifyService extends Service {
    static final int FG_ID = 0x4D53;
    private ScheduledExecutorService ex;

    static void start(Context c) {
        if (!Notify.enabled(c) || !Api.get(c).hasSession()) return;
        try {
            Intent i = new Intent(c, NotifyService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception ignored) { /* Android 12+: запуск из фона запрещён — запустим при следующем открытии */ }
    }
    static void stop(Context c) { c.stopService(new Intent(c, NotifyService.class)); }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Notify.channels(this);
        PendingIntent open = PendingIntent.getActivity(this, 2, new Intent(this, ChatsActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, Notify.CH_BG).setSmallIcon(R.drawable.ic_stat_msg)
                .setContentTitle("Мессенджер на связи").setContentText("Новые сообщения придут, даже когда приложение закрыто")
                .setContentIntent(open).setOngoing(true).setShowWhen(false).build();
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(FG_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING);
            else startForeground(FG_ID, n);
        } catch (Exception e) { stopSelf(); return START_NOT_STICKY; }
        if (!Notify.enabled(this) || !Api.get(this).hasSession()) { stopSelf(); return START_NOT_STICKY; }
        if (ex == null) { ex = Executors.newSingleThreadScheduledExecutor(); ex.scheduleWithFixedDelay(this::poll, 0, 15, TimeUnit.SECONDS); }
        return START_STICKY;
    }
    @Override public void onDestroy() { if (ex != null) ex.shutdownNow(); ex = null; super.onDestroy(); }
    @Override public IBinder onBind(Intent i) { return null; }

    private void poll() {
        try { check(this); }
        catch (Api.AuthException ae) { stopSelf(); }
        catch (Throwable ignored) { }
    }

    /** Один проход: что нового с прошлого раза. Общий для службы и экрана «Чаты». */
    static synchronized void check(Context c) throws Exception {
        Api api = Api.get(c); if (!api.hasSession()) return;
        String me = api.uid();
        android.content.SharedPreferences seen = c.getSharedPreferences("notif_seen", 0);
        boolean first = !seen.getBoolean("_init", false);
        String r = api.rpc("list_chats", new JSONObject()).trim();
        JSONArray arr = r.isEmpty() ? new JSONArray() : new JSONArray(r);
        java.util.Map<String, String> muted = new java.util.HashMap<>();
        try {
            JSONArray pr = api.select("chat_members?select=chat_id,muted_until&user_id=eq." + me);
            for (int i = 0; i < pr.length(); i++) { JSONObject p = pr.getJSONObject(i); if (!p.isNull("muted_until")) muted.put(p.getString("chat_id"), p.getString("muted_until")); }
        } catch (Api.AuthException ae) { throw ae; } catch (Exception ignored) { }
        android.content.SharedPreferences.Editor ed = seen.edit();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            if (o.isNull("last_at")) continue;
            String id = o.getString("chat_id"), at = o.getString("last_at"), prev = seen.getString(id, null);
            if (at.equals(prev)) continue;
            ed.putString(id, at);
            if (first || prev == null) continue;
            if (me.equals(o.optString("last_sender"))) continue;
            if (id.equals(ChatActivity.openChat)) continue; // чат открыт на экране (openChat живёт между onStart и onStop)
            String mu = muted.get(id); if (mu != null && Ui.instant(mu) > System.currentTimeMillis()) continue;
            if (!Notify.enabled(c)) continue;
            notifyChat(c, api, me, o, prev);
        }
        ed.putBoolean("_init", true).apply();
    }

    private static void notifyChat(Context c, Api api, String me, JSONObject o, String prev) throws Exception {
        String id = o.getString("chat_id"), kind = o.optString("kind", ""), peer = o.isNull("peer_id") ? null : o.optString("peer_id");
        String title = o.optString("title", "Мессенджер");
        if (peer != null) { Avatars.refresh(c, java.util.Collections.singleton(peer), 600000); String dn = Avatars.displayName(Avatars.merged(c, peer)); if (!"?".equals(dn)) title = dn; }
        JSONArray ms = api.select("messages?select=id,sender_id,sender_device,key_epoch,body,ciphertext,created_at&chat_id=eq." + id
                + "&created_at=gt." + java.net.URLEncoder.encode(prev, "UTF-8") + "&order=created_at.asc&limit=10");
        for (int i = 0; i < ms.length(); i++) {
            JSONObject m = ms.getJSONObject(i);
            if (m.isNull("sender_id") || me.equals(m.optString("sender_id"))) continue;
            String text = "Новое сообщение";
            if (Notify.showText(c)) {
                try {
                    if (m.isNull("ciphertext")) text = m.optString("body", text);
                    else if (!"group".equals(kind) && E2e.get(c).notForMe(m.getJSONObject("ciphertext"))) text = ChatActivity.OTHER_DEV;
                    else {
                        String p = "group".equals(kind)
                                ? E2e.group(c).decrypt(id, m.getString("id"), m.optString("sender_id"), m.isNull("sender_device") ? null : m.optString("sender_device"), m.optInt("key_epoch", -1), m.getJSONObject("ciphertext"))
                                : E2e.get(c).decrypt(m.getString("id"), m.optString("sender_id"), m.getJSONObject("ciphertext"));
                        if (p != null) text = Notify.text(new JSONObject(p));
                    }
                } catch (Exception ignored) { /* не расшифровалось сейчас — без текста, как на сайте */ }
            }
            String who = "";
            Avatars.refresh(c, java.util.Collections.singleton(m.getString("sender_id")), 600000);
            who = Avatars.displayName(Avatars.merged(c, m.getString("sender_id"))); if ("?".equals(who)) who = "";
            if ("direct".equals(kind) || who.isEmpty()) who = title;
            Notify.message(c, id, title, kind, peer, m.getString("sender_id"), who, text, m.optString("created_at", null));
        }
    }
}
