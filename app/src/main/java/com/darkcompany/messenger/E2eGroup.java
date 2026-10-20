package com.darkcompany.messenger;

import org.json.JSONArray;
import org.json.JSONObject;
import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.SessionCipher;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.groups.GroupCipher;
import org.signal.libsignal.protocol.groups.GroupSessionBuilder;
import org.signal.libsignal.protocol.message.CiphertextMessage;
import org.signal.libsignal.protocol.message.PreKeySignalMessage;
import org.signal.libsignal.protocol.message.SenderKeyDistributionMessage;
import org.signal.libsignal.protocol.message.SenderKeyMessage;
import org.signal.libsignal.protocol.message.SignalMessage;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Сквозное шифрование групп: Sender Keys из libsignal.
 * Ключ отправителя (SenderKey) свой на каждую эпоху группы: distributionId = UUIDv3("msgr-group:" + chat + ":" + epoch),
 * поэтому старым ключом своей эпохи шифровать невозможно, а чужие старые ключи остаются для истории.
 * SKDM доставляется каждому устройству участника через 1:1-сессию Signal (таблица sender_key_distributions).
 */
public final class E2eGroup {
    /** Ключ отправителя этого сообщения ещё не получен (SKDM в пути) — повторить позже, не кэшировать. */
    public static class NoKeyException extends Exception { public NoKeyException(String m) { super(m); } }

    public static final String STALE = "stale key epoch";
    private final E2eCore c;
    private final E2eStore store;
    private final E2eCore.Net net;

    public E2eGroup(E2eCore core, E2eCore.Net net) { this.c = core; this.store = core.store; this.net = net; }

    public static UUID distId(String chat, int epoch) {
        return UUID.nameUUIDFromBytes(("msgr-group:" + chat + ":" + epoch).getBytes(StandardCharsets.UTF_8));
    }
    private static SignalProtocolAddress addr(String dev) { return new SignalProtocolAddress(dev, 1); }
    private JSONObject jget(String k) {
        String s = store.kv.get(k);
        try { return s == null ? new JSONObject() : new JSONObject(s); } catch (Exception e) { return new JSONObject(); }
    }

    // --- предупреждения (показываются в баннере группы) ---
    public synchronized List<String> warnings(String chat) {
        List<String> out = new ArrayList<>();
        try { JSONArray a = new JSONArray(store.kv.get("gwarn:" + chat)); for (int i = 0; i < a.length(); i++) out.add(a.getString(i)); } catch (Exception ignored) { }
        return out;
    }
    public synchronized void clearWarnings(String chat) { store.kv.remove("gwarn:" + chat); }
    private void warn(String chat, String w) {
        List<String> l = warnings(chat);
        if (l.contains(w)) return;
        l.add(w); while (l.size() > 10) l.remove(0);
        store.kv.put("gwarn:" + chat, new JSONArray(l).toString());
    }

    /** Текущая эпоха группы (из group_devices или chats.key_epoch, если других устройств нет). */
    private int epochOf(String chat, JSONArray devs) throws Exception {
        if (devs.length() > 0) return devs.getJSONObject(0).getInt("key_epoch");
        String r = net.call("GET", "/rest/v1/chats?select=key_epoch&id=eq." + chat, null, false);
        return new JSONArray(r).getJSONObject(0).getInt("key_epoch");
    }

    /**
     * Создаёт (если нужно) свой SenderKey текущей эпохи и раздаёт SKDM всем устройствам участников,
     * которым он ещё не отправлен (в т.ч. новым участникам). Возвращает текущую эпоху.
     */
    public synchronized int distribute(String chat) throws Exception {
        String me = c.deviceId();
        if (me == null) throw new IllegalStateException("ключи устройства ещё не готовы");
        String r = net.call("POST", "/rest/v1/rpc/group_devices", new JSONObject().put("p_chat", chat).toString(), false).trim();
        JSONArray devs = r.isEmpty() ? new JSONArray() : new JSONArray(r);
        int epoch = epochOf(chat, devs);
        UUID did = distId(chat, epoch);
        SenderKeyDistributionMessage skdm = new GroupSessionBuilder(store).create(addr(me), did);
        String doneKey = "skd:" + chat + ":" + epoch;
        JSONObject done = jget(doneKey);
        Map<String, Map<String, String>> byUser = new LinkedHashMap<>(); // user -> dev -> identity_pub
        for (int i = 0; i < devs.length(); i++) {
            JSONObject o = devs.getJSONObject(i);
            String dev = o.getString("device_id");
            if (dev.equals(me) || done.has(dev)) continue;
            byUser.computeIfAbsent(o.getString("user_id"), k -> new HashMap<>()).put(dev, o.getString("identity_pub"));
        }
        JSONArray out = new JSONArray();
        List<String> sent = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> u : byUser.entrySet()) {
            String user = u.getKey();
            List<String> ok;
            try {
                ok = c.devicesFor(user);
                boolean missing = false;
                for (String d : u.getValue().keySet()) if (!ok.contains(d)) missing = true;
                if (missing) { c.forgetClaims(user); ok = c.devicesFor(user); } // новое устройство: обновить список
            } catch (E2eCore.KeyChangeException k) {
                warn(chat, "Ключ безопасности участника изменился — ключ группы ему не отправлен, пока вы не сверите код в личном чате.");
                continue;
            }
            JSONObject trust = jget("trust:" + user);
            for (Map.Entry<String, String> d : u.getValue().entrySet()) {
                String dev = d.getKey();
                if (!ok.contains(dev)) continue; // нет доверенной сессии (старый клиент/плохая подпись)
                if (!d.getValue().equals(trust.optString(dev, null))) { warn(chat, "Сервер прислал для участника другой ключ устройства — ключ группы не отправлен."); continue; }
                CiphertextMessage m = new SessionCipher(store, addr(dev)).encrypt(skdm.serialize());
                out.put(new JSONObject().put("chat_id", chat).put("epoch", epoch).put("sender_user", net.uid()).put("sender_device", me)
                        .put("recipient_device", dev).put("ciphertext", new JSONObject().put("t", m.getType()).put("b", E2eCore.b64(m.serialize())).toString()));
                sent.add(dev);
            }
        }
        if (out.length() > 0) {
            upload(out);
            for (String d : sent) done.put(d, 1);
            store.kv.put(doneKey, done.toString());
        }
        store.kv.put("gepoch:" + chat, String.valueOf(epoch));
        return epoch;
    }

    /**
     * Upsert с ignore-duplicates. Сейчас сервер отвергает ON CONFLICT DO NOTHING по RLS (SELECT-политика видна только получателю),
     * поэтому при ошибке RLS — обычный INSERT, а при дубликате — по одной строке, пропуская уже существующие.
     */
    /** Для тестов/диагностики: каким путём ушла последняя раздача. */
    public volatile String lastUploadMode;
    private void upload(JSONArray out) throws Exception {
        try {
            net.callPrefer("POST", "/rest/v1/sender_key_distributions?on_conflict=chat_id,epoch,sender_device,recipient_device",
                    out.toString(), "resolution=ignore-duplicates,return=minimal");
            lastUploadMode = "upsert";
            return;
        } catch (Exception e) {
            if (!String.valueOf(e.getMessage()).contains("row-level security")) throw e;
        }
        try {
            net.call("POST", "/rest/v1/sender_key_distributions", out.toString(), true);
            lastUploadMode = "insert";
        } catch (Exception e) {
            if (!isDup(e)) throw e;
            for (int i = 0; i < out.length(); i++) {
                try { net.call("POST", "/rest/v1/sender_key_distributions", out.getJSONObject(i).toString(), true); }
                catch (Exception e1) { if (!isDup(e1)) throw e1; }
            }
            lastUploadMode = "insert-per-row";
        }
    }
    private static boolean isDup(Exception e) { String m = String.valueOf(e.getMessage()); return m.contains("duplicate") || m.contains("23505") || m.contains("409"); }

    /** Шифрует своим ключом указанной (текущей) эпохи; distribute() должен быть вызван для неё раньше. */
    public synchronized JSONObject encrypt(String chat, int epoch, String plaintext) throws Exception {
        CiphertextMessage m = new GroupCipher(store, addr(c.deviceId())).encrypt(distId(chat, epoch), plaintext.getBytes(StandardCharsets.UTF_8));
        return new JSONObject().put("sk", E2eCore.b64(m.serialize()));
    }

    private boolean accept(String chat, String user, String sdev, int epoch, byte[] pt) throws Exception {
        SenderKeyDistributionMessage m = new SenderKeyDistributionMessage(pt);
        if (!m.getDistributionId().equals(distId(chat, epoch))) { warn(chat, "Получен ключ группы от другой группы или эпохи — отклонён."); return false; }
        new GroupSessionBuilder(store).process(addr(sdev), m);
        store.kv.put("gdev:" + sdev, user);
        return true;
    }

    /** Отложенные SKDM: принимаются, когда ключ устройства отправителя стал доверенным (пользователь подтвердил смену). */
    private int acceptWaiting(String chat) {
        int n = 0;
        for (String k : new ArrayList<>(store.kv.keys())) {
            if (!k.startsWith("skdmwait:" + chat + ":")) continue;
            String sdev = k.substring(("skdmwait:" + chat + ":").length());
            try {
                JSONObject w = new JSONObject(store.kv.get(k));
                String user = w.getString("u");
                if (store.kv.get("pending:" + user) != null) continue; // ещё не подтверждено
                if (w.getString("ik").equals(jget("trust:" + user).optString(sdev, null)) && accept(chat, user, sdev, w.getInt("e"), E2eCore.unb64(w.getString("m")))) n++;
                store.kv.remove(k); // подтверждён другой ключ или устройство ушло — выбрасываем
            } catch (Exception e) { store.kv.remove(k); }
        }
        return n;
    }

    /** Забирает адресованные этому устройству SKDM, проверяет ключ отправителя (TOFU) и удаляет строки. Возвращает число принятых. */
    public synchronized int receive(String chat) throws Exception {
        JSONArray rows = new JSONArray(net.call("GET", "/rest/v1/sender_key_distributions?select=id,epoch,sender_user,sender_device,ciphertext&chat_id=eq."
                + chat + "&order=created_at.asc", null, false));
        int n = acceptWaiting(chat);
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            String user = row.getString("sender_user"), sdev = row.getString("sender_device");
            // Список устройств отправителя обновляем ДО расшифровки: расшифровать SKDM можно только один раз,
            // поэтому при сетевой ошибке строку не трогаем и повторим позже.
            try {
                if (!jget("trust:" + user).has(sdev)) {
                    if (store.kv.get("trust:" + user) != null) c.forgetClaims(user);
                    try { c.devicesFor(user); } catch (E2eCore.KeyChangeException ignored) { }
                }
            } catch (Exception netErr) { continue; }
            try {
                JSONObject e = new JSONObject(row.getString("ciphertext"));
                byte[] body = E2eCore.unb64(e.getString("b"));
                SessionCipher sc = new SessionCipher(store, addr(sdev));
                byte[] pt = e.getInt("t") == CiphertextMessage.PREKEY_TYPE ? sc.decrypt(new PreKeySignalMessage(body)) : sc.decrypt(new SignalMessage(body));
                IdentityKey ik = store.getIdentity(addr(sdev));
                String ikb = ik == null ? null : E2eCore.b64(ik.serialize());
                String trusted = jget("trust:" + user).optString(sdev, null);
                if (ikb == null || !ikb.equals(trusted)) {
                    warn(chat, "Получен ключ группы с незнакомым или изменившимся ключом устройства участника — он не принят. Сверьте код безопасности в личном чате.");
                    if (ikb != null && store.kv.get("trust:" + user) != null) { JSONObject p = jget("pending:" + user); p.put(sdev, ikb); store.kv.put("pending:" + user, p.toString()); }
                    // SKDM нельзя расшифровать повторно — откладываем локально; примем, только если пользователь подтвердит именно этот ключ
                    if (ikb != null) store.kv.put("skdmwait:" + chat + ":" + sdev, new JSONObject().put("u", user).put("e", row.getInt("epoch"))
                            .put("ik", ikb).put("m", E2eCore.b64(pt)).toString());
                } else if (accept(chat, user, sdev, row.getInt("epoch"), pt)) n++;
            } catch (Exception ex) {
                warn(chat, "Не удалось расшифровать ключ группы от участника (" + ex.getClass().getSimpleName() + ").");
            }
            // SKDM нельзя расшифровать дважды (Double Ratchet) — строку удаляем в любом случае
            try { net.call("DELETE", "/rest/v1/sender_key_distributions?id=eq." + row.getString("id"), null, true); } catch (Exception ignored) { }
        }
        return n;
    }

    /** Расшифровка группового сообщения ключом точного sender_device из строки. */
    public synchronized String decrypt(String chat, String msgId, String senderUser, String senderDevice, int epoch, JSONObject ct) throws Exception {
        String own = store.kv.get("pts:" + msgId);
        String sk = ct.getString("sk");
        String ck = "ptg:" + msgId + ":" + E2eCore.sha256hex(sk.getBytes(StandardCharsets.UTF_8)).substring(0, 16);
        String cached = store.kv.get(ck);
        if (cached != null) return cached;
        if (senderDevice == null) throw new SecurityException("нет sender_device");
        byte[] b = E2eCore.unb64(sk);
        SenderKeyMessage skm = new SenderKeyMessage(b);
        if (!skm.getDistributionId().equals(distId(chat, epoch))) throw new SecurityException("ключ не этой группы/эпохи");
        if (senderDevice.equals(c.deviceId())) { if (own != null) return own; throw new NoKeyException("своё сообщение без копии"); }
        String du = store.kv.get("gdev:" + senderDevice);
        if (du == null || store.loadSenderKey(addr(senderDevice), skm.getDistributionId()) == null) throw new NoKeyException("ключ отправителя ещё не получен");
        if (!du.equals(senderUser)) throw new SecurityException("устройство не принадлежит отправителю");
        byte[] pt = new GroupCipher(store, addr(senderDevice)).decrypt(b);
        String s = new String(pt, StandardCharsets.UTF_8);
        store.kv.put(ck, s);
        return s;
    }

    /** Свой отправленный текст (себе Sender Key не расшифровывает). */
    public void rememberOwn(String msgId, String ct, String plaintext) throws Exception {
        store.kv.put("pts:" + msgId, plaintext);
        store.kv.put("ptg:" + msgId + ":" + E2eCore.sha256hex(ct.getBytes(StandardCharsets.UTF_8)).substring(0, 16), plaintext);
    }
}
