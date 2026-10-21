package com.darkcompany.messenger;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Резервная копия переписки — тот же шифр, что web/backup.js (docs/backup_format.md):
 * data = nonce(12) || AES-256-GCM(key, nonce, JSON, AAD="msgr-backup:v1:<uid>"),
 * key = Argon2id(UTF-8 пароль, salt, m=65536 KiB, t=3, p=1, v=0x13, 32 байта).
 * Файл на устройстве (docs/backup_format.md, «Файл копии»): JSON
 * {"format":"msgr-backup","v":1,"uid":..,"salt":b64,"kdf":{alg,m,t,p},"created_at":..,"data":b64(backup.bin)}.
 * Ключей устройства и сессий в копии нет; пароль нигде не сохраняется.
 */
final class BackupFile {
    static final int MIN_PASS = 12;
    static final int M = 65536, T = 3, P = 1;
    static final class BadPass extends Exception { BadPass() { super("Неверный пароль"); } }

    private static void checkKdf(JSONObject k) throws Exception {
        if (k == null || !"argon2id".equals(k.optString("alg"))) throw new Exception("Неизвестный KDF копии");
        int m = k.optInt("m", 0), t = k.optInt("t", 0), p = k.optInt("p", 0);
        if (m < 1 || t < 1 || p < 1) throw new Exception("Неверные параметры KDF");
        if (m > 1048576 || t > 10 || p > 16) throw new Exception("Слишком тяжёлые параметры KDF");
    }
    static byte[] deriveKey(char[] pass, byte[] salt, JSONObject kdf) throws Exception {
        checkKdf(kdf);
        byte[] pw = new String(pass).getBytes(StandardCharsets.UTF_8);
        try {
            Argon2Parameters prm = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id).withVersion(Argon2Parameters.ARGON2_VERSION_13)
                    .withIterations(kdf.getInt("t")).withMemoryAsKB(kdf.getInt("m")).withParallelism(kdf.getInt("p")).withSalt(salt).build();
            Argon2BytesGenerator g = new Argon2BytesGenerator(); g.init(prm);
            byte[] out = new byte[32]; g.generateBytes(pw, out); return out;
        } finally { Arrays.fill(pw, (byte) 0); }
    }
    static JSONObject defaultKdf() throws Exception { return new JSONObject().put("alg", "argon2id").put("m", M).put("t", T).put("p", P); }
    private static byte[] aad(String uid) { return ("msgr-backup:v1:" + uid).getBytes(StandardCharsets.UTF_8); }

    /** = Backup.seal сайта */
    static byte[] seal(JSONObject obj, byte[] key, String uid) throws Exception {
        byte[] nonce = new byte[12]; new SecureRandom().nextBytes(nonce);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        c.updateAAD(aad(uid));
        byte[] ct = c.doFinal(obj.toString().getBytes(StandardCharsets.UTF_8));
        byte[] out = new byte[12 + ct.length]; System.arraycopy(nonce, 0, out, 0, 12); System.arraycopy(ct, 0, out, 12, ct.length); return out;
    }
    /** = Backup.open сайта: проверка тега GCM (целостность), v, uid, messages. */
    static JSONObject open(byte[] bin, byte[] key, String uid) throws Exception {
        if (bin.length < 12 + 16) throw new Exception("Файл копии повреждён");
        byte[] pt;
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, bin, 0, 12));
            c.updateAAD(aad(uid));
            pt = c.doFinal(bin, 12, bin.length - 12);
        } catch (javax.crypto.AEADBadTagException e) { throw new BadPass(); }
        JSONObject o = new JSONObject(StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(pt)).toString());
        if (o.optInt("v") != 1) throw new Exception("Неподдерживаемая версия копии");
        if (!uid.equals(o.optString("uid"))) throw new Exception("Копия принадлежит другому аккаунту");
        if (!(o.opt("messages") instanceof JSONArray)) throw new Exception("В копии нет сообщений");
        return o;
    }

    /** Полный файл копии. */
    static byte[] export(JSONArray messages, char[] pass, String uid, String createdAt) throws Exception {
        byte[] salt = new byte[16]; new SecureRandom().nextBytes(salt);
        JSONObject kdf = defaultKdf();
        byte[] key = deriveKey(pass, salt, kdf);
        try {
            byte[] bin = seal(new JSONObject().put("v", 1).put("uid", uid).put("created_at", createdAt).put("messages", messages), key, uid);
            Base64.Encoder b = Base64.getEncoder();
            return new JSONObject().put("format", "msgr-backup").put("v", 1).put("uid", uid).put("salt", b.encodeToString(salt))
                    .put("kdf", kdf).put("created_at", createdAt).put("data", b.encodeToString(bin)).toString().getBytes(StandardCharsets.UTF_8);
        } finally { Arrays.fill(key, (byte) 0); }
    }
    /** Разбор и расшифровка файла; ничего не записывает. */
    static JSONObject read(byte[] file, char[] pass, String uid) throws Exception {
        JSONObject f;
        try { f = new JSONObject(new String(file, StandardCharsets.UTF_8)); } catch (Exception e) { throw new Exception("Это не файл резервной копии"); }
        if (!"msgr-backup".equals(f.optString("format"))) throw new Exception("Это не файл резервной копии");
        if (f.optInt("v") != 1) throw new Exception("Неподдерживаемая версия копии");
        if (f.has("uid") && !uid.equals(f.optString("uid"))) throw new Exception("Копия принадлежит другому аккаунту");
        Base64.Decoder d = Base64.getDecoder();
        byte[] salt, bin;
        try { salt = d.decode(f.getString("salt")); bin = d.decode(f.getString("data")); } catch (Exception e) { throw new Exception("Файл копии повреждён"); }
        byte[] key = deriveKey(pass, salt, f.optJSONObject("kdf"));
        try { return open(bin, key, uid); } finally { Arrays.fill(key, (byte) 0); }
    }

    /** = Backup.toEntry сайта; pts — значение кэша «pts:<id>» ({"v":1,"t",f,caption,fw} или старый простой текст). */
    static JSONObject toEntry(JSONObject m, String pts) throws Exception {
        JSONObject pl = null;
        try { JSONObject j = new JSONObject(pts); if (j.has("t") || j.has("f")) pl = j; } catch (Exception ignored) { }
        if (pl == null) pl = new JSONObject().put("t", pts);
        JSONObject e = new JSONObject().put("chat_id", m.optString("chat_id")).put("message_id", m.optString("id"))
                .put("plaintext", pl.has("t0") ? pl.optString("t0") : pl.optString("t", "")).put("attachments", new JSONArray());
        if (!pl.optString("caption").isEmpty()) e.put("caption", pl.optString("caption"));
        if (!m.optString("sender_id").isEmpty() && !m.isNull("sender_id")) e.put("sender_id", m.optString("sender_id"));
        if (!m.optString("created_at").isEmpty()) e.put("created_at", m.optString("created_at"));
        JSONObject f = pl.optJSONObject("f");
        if (f != null && !f.optString("p").isEmpty()) {
            JSONObject a = new JSONObject().put("path", f.optString("p")).put("name", f.optString("n", "file")).put("size", f.optLong("s", 0))
                    .put("key", f.optString("k")).put("nonce", f.optString("iv"));
            if (!f.optString("m").isEmpty()) a.put("mime", f.optString("m"));
            if (!f.optString("h").isEmpty()) a.put("sha256", f.optString("h"));
            e.getJSONArray("attachments").put(a);
        }
        if (!pl.optString("fw").isEmpty()) e.put("forwarded", new JSONObject().put("name", pl.optString("fw")));
        return e;
    }
    /** = Backup.toCache сайта: {id, pt} или null. */
    static String[] toCache(JSONObject e) throws Exception {
        if (e == null || !(e.opt("message_id") instanceof String)) return null;
        JSONObject o = new JSONObject().put("v", 1).put("t", e.opt("plaintext") instanceof String ? e.getString("plaintext") : "");
        JSONArray at = e.optJSONArray("attachments");
        JSONObject a = at != null && at.length() > 0 ? at.optJSONObject(0) : null;
        if (a != null && !a.optString("path").isEmpty() && !a.optString("key").isEmpty() && !a.optString("nonce").isEmpty()) {
            JSONObject f = new JSONObject().put("p", a.getString("path")).put("n", a.optString("name", "file")).put("s", a.optLong("size", 0)).put("k", a.getString("key")).put("iv", a.getString("nonce"));
            if (!a.optString("mime").isEmpty()) f.put("m", a.getString("mime"));
            if (!a.optString("sha256").isEmpty()) f.put("h", a.getString("sha256"));
            o.put("f", f);
        }
        Object fwo = e.opt("forwarded"); String fw = fwo instanceof String ? (String) fwo : fwo instanceof JSONObject ? ((JSONObject) fwo).optString("name", ((JSONObject) fwo).optString("fw")) : "";
        if (!fw.isEmpty()) o.put("fw", fw.length() > 64 ? fw.substring(0, 64) : fw);
        String cap = e.optString("caption");
        if (o.has("f") && !cap.isEmpty()) o.put("caption", cap.length() > 4000 ? cap.substring(0, 4000) : cap);
        return new String[]{e.getString("message_id"), o.toString()};
    }
    /** Сила пароля 0..4 для индикатора. */
    static int strength(String s) {
        if (s.length() < MIN_PASS) return 0;
        int cls = 0; if (s.matches(".*[a-zа-яё].*")) cls++; if (s.matches(".*[A-ZА-ЯЁ].*")) cls++; if (s.matches(".*\\d.*")) cls++; if (s.matches(".*[^\\p{L}\\d].*")) cls++;
        int sc = 1 + (s.length() >= 16 ? 1 : 0) + (cls >= 3 ? 1 : 0) + (s.length() >= 20 && cls >= 2 ? 1 : 0);
        return Math.min(4, sc);
    }
}
