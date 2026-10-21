package com.darkcompany.messenger;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * v16: резервная копия как на сайте (app.js createBackup/restoreBackup): Storage backups/{uid}/backup.bin + key_backups (salt, kdf).
 * Шифруется на устройстве (BackupFile = web/backup.js), на сервер уходит только шифротекст, пароль не хранится и не отправляется.
 * Автокопия: хранится только ключ Argon2id (+ salt той же строки key_backups), обёрнутый AES-ключом Android Keystore
 * (StrongBox, иначе TEE). Стирается при выходе и при выключении автокопии.
 */
final class CloudBackup {
    private static final String BUCKET = "backups", KS_ALIAS = "msgr_backup_wrap", PREF = "backup_auto";
    static final long DAY = 24L * 3600 * 1000, AFTER_CHAT_MIN = 10L * 60 * 1000;

    static SharedPreferences sp(Context c) { return c.getApplicationContext().getSharedPreferences(PREF, 0); }
    private static String path(String uid) { return uid + "/backup.bin"; }

    /** Строка key_backups или null. */
    static JSONObject row(Context c) throws Exception {
        Api api = Api.get(c);
        JSONArray r = api.select("key_backups?select=*&user_id=eq." + api.uid());
        return r.length() == 0 ? null : r.getJSONObject(0);
    }
    private static JSONObject kdfOf(JSONObject row) throws Exception {
        Object k = row == null ? null : row.opt("kdf");
        JSONObject j = k instanceof JSONObject ? (JSONObject) k : k instanceof String ? new JSONObject((String) k) : null;
        return j != null && "argon2id".equals(j.optString("alg")) ? j : BackupFile.defaultKdf();
    }

    /** Ключ из пароля: salt и kdf берём из существующей строки (как сайт), иначе новые. Возвращает {key, salt(utf8 b64)}. */
    static Object[] derive(Context c, char[] pass) throws Exception { return derive(c, pass, false); }
    /** fresh: «Создать новую копию» — новая соль (старая копия будет заменена). */
    static Object[] derive(Context c, char[] pass, boolean fresh) throws Exception {
        JSONObject row = fresh ? null : row(c);
        String salt = row != null && !row.optString("salt").isEmpty() ? row.getString("salt") : Base64.getEncoder().encodeToString(rnd(16));
        JSONObject kdf = row != null ? kdfOf(row) : BackupFile.defaultKdf();
        return new Object[]{BackupFile.deriveKey(pass, Base64.getDecoder().decode(salt), kdf), salt, kdf};
    }
    private static byte[] rnd(int n) { byte[] b = new byte[n]; new java.security.SecureRandom().nextBytes(b); return b; }

    /** createBackup сайта. */
    /** Спрашивает, заменить ли более полную копию: (старых, новых) → да/нет. null — автокопия: молча пропустить. */
    interface Smaller { boolean allow(int oldCount, int newCount); }
    static final int SKIPPED = -1;
    static int create(Context c, byte[] key, String salt, JSONObject kdf, java.util.function.IntConsumer progress) throws Exception { return create(c, key, salt, kdf, progress, null, false); }
    /** fresh=true — новая соль, старую копию не проверяем (её пароль забыт). Иначе существующая копия расшифровывается тем же ключом:
     *  неверный пароль → BadPass (ничего не перезаписано); в ней больше сообщений → спросить (ручная) / пропустить (авто). */
    static int create(Context c, byte[] key, String salt, JSONObject kdf, java.util.function.IntConsumer progress, Smaller smaller, boolean fresh) throws Exception {
        Api api = Api.get(c); String uid = api.uid();
        JSONArray msgs = collect(c, progress);
        JSONObject row = fresh ? null : row(c);
        if (row != null) {
            int old = BackupFile.open(download(c, row), key, uid).getJSONArray("messages").length();
            if (old > msgs.length() && (smaller == null || !smaller.allow(old, msgs.length()))) return SKIPPED;
        }
        byte[] bin = BackupFile.seal(new JSONObject().put("v", 1).put("uid", uid).put("created_at", java.time.Instant.now().toString()).put("messages", msgs), key, uid);
        HttpURLConnection h = (HttpURLConnection) new URL(Api.URL + "/storage/v1/object/" + BUCKET + "/" + path(uid)).openConnection();
        h.setRequestMethod("POST"); h.setDoOutput(true);
        h.setRequestProperty("apikey", Api.KEY); h.setRequestProperty("Authorization", "Bearer " + api.freshToken());
        h.setRequestProperty("Content-Type", "application/octet-stream"); h.setRequestProperty("x-upsert", "true");
        h.setFixedLengthStreamingMode(bin.length);
        try (OutputStream o = h.getOutputStream()) { o.write(bin); }
        int code = h.getResponseCode();
        if (code / 100 != 2) throw new java.io.IOException("Сервер не принял копию (код " + code + ")");
        api.rawPrefer("POST", "/rest/v1/key_backups?on_conflict=user_id", new JSONObject().put("user_id", uid).put("version", 1).put("salt", salt).put("kdf", kdf)
                .put("object_path", path(uid)).put("size_bytes", bin.length).put("updated_at", java.time.Instant.now().toString()).toString(), "resolution=merge-duplicates,return=minimal");
        sp(c).edit().putLong("last", System.currentTimeMillis()).putLong("size", bin.length).apply();
        return msgs.length();
    }

    /** Проверка пароля на существующей копии (тег GCM) до перезаписи. */
    static void verify(Context c, JSONObject row, byte[] key) throws Exception {
        byte[] bin = download(c, row);
        BackupFile.open(bin, key, Api.get(c).uid());
    }
    static byte[] download(Context c, JSONObject row) throws Exception {
        Api api = Api.get(c);
        HttpURLConnection h = (HttpURLConnection) new URL(Api.URL + "/storage/v1/object/authenticated/" + BUCKET + "/" + row.optString("object_path", path(api.uid()))).openConnection();
        h.setRequestProperty("apikey", Api.KEY); h.setRequestProperty("Authorization", "Bearer " + api.freshToken());
        int code = h.getResponseCode();
        if (code / 100 != 2) throw new java.io.IOException(code == 404 || code == 400 ? "Резервная копия не найдена" : "Не удалось скачать копию (код " + code + ")");
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (InputStream in = h.getInputStream()) { byte[] b = new byte[65536]; int r; long n = 0; while ((r = in.read(b)) > 0) { n += r; if (n > 100L << 20) throw new Exception("Копия больше 100 МБ"); bo.write(b, 0, r); } }
        return bo.toByteArray();
    }

    /** collectMessages сайта: сообщения моих чатов с расшифрованным текстом на этом устройстве. */
    static JSONArray collect(Context c, java.util.function.IntConsumer progress) throws Exception {
        Api api = Api.get(c); E2eCore core = E2e.get(c);
        JSONArray out = new JSONArray(); String before = null;
        for (;;) {
            JSONArray page = api.select("messages?select=id,chat_id,sender_id,created_at&order=created_at.desc&limit=1000" + (before == null ? "" : "&created_at=lt." + android.net.Uri.encode(before)));
            for (int i = 0; i < page.length(); i++) {
                JSONObject m = page.getJSONObject(i); String pts = core.peekOwn(m.optString("id"));
                if (pts != null) out.put(BackupFile.toEntry(m, pts));
            }
            if (progress != null) progress.accept(out.length());
            if (page.length() < 1000) break;
            before = page.getJSONObject(page.length() - 1).optString("created_at");
        }
        return out;
    }

    /** restoreBackup сайта: скачать, расшифровать и проверить целиком, только потом записать. */
    static int restore(Context c, char[] pass) throws Exception {
        Api api = Api.get(c); String uid = api.uid();
        JSONObject row = row(c); if (row == null) throw new Exception("Резервной копии нет");
        byte[] bin = download(c, row);
        byte[] key = BackupFile.deriveKey(pass, Base64.getDecoder().decode(row.getString("salt")), kdfOf(row));
        JSONObject o;
        try { o = BackupFile.open(bin, key, uid); } finally { Arrays.fill(key, (byte) 0); }
        JSONArray ms = o.getJSONArray("messages");
        java.util.Map<String, String> mine = myMessages(api, ms);
        java.util.List<String[]> items = new java.util.ArrayList<>();
        for (int i = 0; i < ms.length(); i++) { String[] x = BackupFile.toCache(ms.optJSONObject(i), mine); if (x != null) items.add(x); }
        int n = E2e.get(c).importPlain(items);
        sp(c).edit().putBoolean("asked:" + uid, true).apply();
        return n;
    }
    /** message_id → chat_id только для сообщений, видимых мне (RLS — мои чаты). */
    private static java.util.Map<String, String> myMessages(Api api, JSONArray ms) throws Exception {
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < ms.length(); i++) { String id = ms.optJSONObject(i) == null ? "" : ms.optJSONObject(i).optString("message_id"); if (id.matches("[0-9a-fA-F-]{36}")) ids.add(id); }
        java.util.Map<String, String> m = new java.util.HashMap<>();
        for (int i = 0; i < ids.size(); i += 100) {
            JSONArray r = api.select("messages?select=id,chat_id&id=in.(" + String.join(",", ids.subList(i, Math.min(ids.size(), i + 100))) + ")");
            for (int k = 0; k < r.length(); k++) m.put(r.getJSONObject(k).optString("id"), r.getJSONObject(k).optString("chat_id"));
        }
        return m;
    }

    // ---------- Автокопия: ключ Argon2id, обёрнутый ключом Keystore ----------
    private static SecretKey wrapKey(boolean create) throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if (ks.containsAlias(KS_ALIAS)) return (SecretKey) ks.getKey(KS_ALIAS, null);
        if (!create) return null;
        for (boolean strong : new boolean[]{true, false}) {
            try {
                KeyGenParameterSpec.Builder b = new KeyGenParameterSpec.Builder(KS_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256);
                if (strong) b.setIsStrongBoxBacked(true);
                KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"); g.init(b.build());
                return g.generateKey();
            } catch (Exception e) { if (!strong) throw e; } // StrongBoxUnavailableException → TEE
        }
        throw new IllegalStateException();
    }
    static boolean enabled(Context c) { return sp(c).getBoolean("on", true); }
    static boolean hasKey(Context c) { return sp(c).getString("wk", null) != null; }
    /** Сохраняет только обёрнутый ключ + salt + kdf. */
    static void storeKey(Context c, byte[] key, String salt, JSONObject kdf) throws Exception {
        Cipher ci = Cipher.getInstance("AES/GCM/NoPadding"); ci.init(Cipher.ENCRYPT_MODE, wrapKey(true));
        byte[] ct = ci.doFinal(key);
        Base64.Encoder e = Base64.getEncoder();
        sp(c).edit().putString("wk", e.encodeToString(ci.getIV()) + "." + e.encodeToString(ct)).putString("salt", salt).putString("kdf", kdf.toString())
                .putString("uid", Api.get(c).uid()).putBoolean("on", true).apply();
    }
    private static byte[] loadKey(Context c) throws Exception {
        String w = sp(c).getString("wk", null); if (w == null) return null;
        SecretKey k = wrapKey(false); if (k == null) return null;
        String[] p = w.split("\\."); Base64.Decoder d = Base64.getDecoder();
        Cipher ci = Cipher.getInstance("AES/GCM/NoPadding"); ci.init(Cipher.DECRYPT_MODE, k, new GCMParameterSpec(128, d.decode(p[0])));
        return ci.doFinal(d.decode(p[1]));
    }
    /** Выход / выключение автокопии: стереть обёрнутый ключ и ключ Keystore. */
    static void wipe(Context c) {
        try { sp(c).edit().remove("wk").remove("salt").remove("kdf").remove("uid").apply(); } catch (Exception ignored) { }
        try { KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null); ks.deleteEntry(KS_ALIAS); } catch (Exception ignored) { }
    }
    static void setEnabled(Context c, boolean on) { sp(c).edit().putBoolean("on", on).apply(); if (!on) wipe(c); }

    /** «Сохранить сейчас» при включённой автокопии: обёрнутым ключом, без пароля. false — ключа нет/устарел. */
    static boolean nowWithStoredKey(Context c, Smaller smallerAsk) throws Exception {
        if (!hasKey(c)) return false;
        JSONObject row = row(c); String salt = sp(c).getString("salt", "");
        if (row != null && !salt.equals(row.optString("salt"))) { wipe(c); return false; }
        byte[] key = loadKey(c); if (key == null) return false;
        try { return create(c, key, salt, new JSONObject(sp(c).getString("kdf", "{}")), null, smallerAsk, false) != SKIPPED; } finally { Arrays.fill(key, (byte) 0); }
    }
    /** v17: автокопия только после восстановления или «Пропустить» на этом устройстве (на аккаунт). */
    static boolean ready(Context c) { return sp(c).getBoolean("asked:" + Api.get(c).uid(), false); }
    static void markReady(Context c) { sp(c).edit().putBoolean("asked:" + Api.get(c).uid(), true).apply(); }
    private static volatile boolean running;
    /** Автокопия: раз в сутки (из ChatsActivity) и после выхода из чата (не чаще раза в 10 мин). Тихо, в фоне. */
    static void maybeAuto(Context c, boolean afterChat) {
        Context app = c.getApplicationContext();
        if (running || !enabled(app) || !hasKey(app) || !Api.get(app).hasSession() || !ready(app)) return;
        long last = sp(app).getLong("last", 0), gap = System.currentTimeMillis() - last;
        if (gap < (afterChat ? AFTER_CHAT_MIN : DAY)) return;
        running = true;
        new Thread(() -> {
            byte[] key = null;
            try {
                if (!Api.get(app).uid().equals(sp(app).getString("uid", ""))) { wipe(app); return; }
                JSONObject row = row(app);
                String salt = sp(app).getString("salt", "");
                // пароль сменили на сайте (новая соль) — ключ устарел: просим пароль заново
                if (row != null && !salt.equals(row.optString("salt"))) { wipe(app); return; }
                key = loadKey(app); if (key == null) return;
                create(app, key, salt, new JSONObject(sp(app).getString("kdf", "{}")), null);
            } catch (Exception ignored) {
            } finally { if (key != null) Arrays.fill(key, (byte) 0); running = false; }
        }, "auto-backup").start();
    }
}
