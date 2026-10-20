package com.darkcompany.messenger;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import org.json.JSONObject;

/** Кнопки уведомления (как actions у PWA): «Ответить» — inline reply, шифруем и отправляем в фоне; «Прочитано» — mark_read и снять уведомление. */
public class NotifyActions extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        final String chat = i.getStringExtra("chat_id"); if (chat == null) return;
        final Context app = c.getApplicationContext();
        final PendingResult pr = goAsync();
        if (Notify.ACT_READ.equals(i.getAction())) {
            final String upto; synchronized (Notify.class) { upto = Notify.upto.get(chat); }
            Notify.cancel(app, chat);
            new Thread(() -> { try { markRead(app, chat, upto); } catch (Exception ignored) { } finally { done(pr); } }).start();
            return;
        }
        if (!Notify.ACT_REPLY.equals(i.getAction())) { done(pr); return; }
        Bundle ri = android.app.RemoteInput.getResultsFromIntent(i);
        CharSequence cs = ri == null ? null : ri.getCharSequence(Notify.KEY_REPLY);
        final String text = cs == null ? "" : cs.toString().trim();
        final String kind = i.getStringExtra("kind"), peer = i.getStringExtra("peer_id"), title = i.getStringExtra("title");
        if (text.isEmpty()) { done(pr); return; }
        new Thread(() -> {
            try {
                send(app, chat, kind, peer, text);
                String upto; synchronized (Notify.class) { upto = Notify.upto.get(chat); }
                try { markRead(app, chat, upto); } catch (Exception ignored) { }
                Notify.replied(app, chat, title, kind, peer, text, null);
            } catch (Exception e) {
                Notify.replied(app, chat, title, kind, peer, text, "Не отправлено: " + (e.getMessage() == null ? "ошибка сети" : e.getMessage()));
            } finally { done(pr); }
        }).start();
    }

    private static void done(PendingResult pr) { if (pr != null) try { pr.finish(); } catch (Exception ignored) { } }

    static void markRead(Context c, String chat, String upto) throws Exception {
        JSONObject a = new JSONObject().put("p_chat", chat);
        a.put("p_upto", upto == null ? java.time.Instant.now().toString() : upto);
        Api.get(c).rpc("mark_read", a);
    }

    /** Та же отправка, что в ChatActivity.post(): в личном — только шифротекст на все устройства собеседника, в группе — ключом эпохи. */
    static String send(Context c, String chat, String kind, String peer, String text) throws Exception {
        Api api = Api.get(c);
        JSONObject payload = new JSONObject().put("v", 1).put("t", text);
        String id = java.util.UUID.randomUUID().toString();
        if ("group".equals(kind)) {
            E2eGroup g = E2e.group(c); String dev = E2e.get(c).deviceId(); Exception last = null;
            for (int attempt = 0; attempt < 3; attempt++) {
                int epoch = g.distribute(chat);
                JSONObject ct = g.encrypt(chat, epoch, payload.toString());
                JSONObject row = new JSONObject().put("id", id).put("chat_id", chat).put("sender_id", api.uid()).put("body", "")
                        .put("ciphertext", ct).put("key_epoch", epoch).put("sender_device", dev);
                try { api.insertReturningId("messages", row); g.rememberOwn(id, ct.getString("sk"), payload.toString()); return id; }
                catch (Api.HttpError e) { if (e.getMessage() == null || !e.getMessage().contains(E2eGroup.STALE)) throw e; last = e; }
            }
            throw new Exception("ключ группы меняется, попробуйте из чата", last);
        }
        if (peer == null) throw new Exception("нет собеседника");
        E2eCore core = E2e.get(c);
        JSONObject ct;
        try { ct = core.encrypt(peer, payload.toString()); }
        catch (E2eCore.KeyChangeException k) { throw new Exception("ключ безопасности изменился, откройте чат"); }
        if (ct == null) throw new Exception("у собеседника нет ключей шифрования");
        JSONObject row = new JSONObject().put("id", id).put("chat_id", chat).put("sender_id", api.uid()).put("body", "").put("ciphertext", ct);
        api.insertReturningId("messages", row);
        core.rememberOwn(id, payload.toString());
        return id;
    }
}
