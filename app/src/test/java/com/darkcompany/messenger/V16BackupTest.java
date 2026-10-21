package com.darkcompany.messenger;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;

/** v16: копия как на сайте (key_backups + backup.bin), совместимость с web/backup.js в обе стороны (tools/backup-interop). */
public class V16BackupTest {
    static final String UID = "11111111-2222-3333-4444-555555555555", CHAT = "cccccccc-0000-0000-0000-000000000001";

    static JSONObject site() throws Exception { return new JSONObject(new String(Files.readAllBytes(Paths.get("src/test/resources/web_backup.json")), "UTF-8")); }
    static byte[] key(JSONObject row, String pass) throws Exception { return BackupFile.deriveKey(pass.toCharArray(), Base64.getDecoder().decode(row.getString("salt")), row.getJSONObject("kdf")); }

    @Test public void restoresSiteBackup() throws Exception {
        JSONObject s = site(), row = s.getJSONObject("row");
        JSONObject o = BackupFile.open(Base64.getDecoder().decode(s.getString("bin")), key(row, "правильный пароль 123"), UID);
        JSONArray ms = o.getJSONArray("messages"); assertEquals(2, ms.length());
        java.util.Map<String, String> mine = new java.util.HashMap<>();
        mine.put("aaaaaaaa-0000-0000-0000-000000000001", CHAT); mine.put("aaaaaaaa-0000-0000-0000-000000000002", CHAT);
        assertEquals("Привет с сайта 👋", new JSONObject(BackupFile.toCache(ms.getJSONObject(0), mine)[1]).getString("t"));
        JSONObject p1 = new JSONObject(BackupFile.toCache(ms.getJSONObject(1), mine)[1]);
        assertEquals("подпись", p1.getString("caption")); assertTrue(p1.getJSONObject("f").getString("p").startsWith("chat/" + CHAT + "/"));
    }

    @Test public void wrongPasswordTamperOtherUid() throws Exception {
        JSONObject s = site(), row = s.getJSONObject("row"); byte[] bin = Base64.getDecoder().decode(s.getString("bin"));
        try { BackupFile.open(bin, key(row, "неверный пароль!!"), UID); fail(); } catch (BackupFile.BadPass ok) { }
        try { BackupFile.open(bin, key(row, "правильный пароль 123"), "other"); fail(); } catch (BackupFile.BadPass ok) { } // AAD с uid
        bin[bin.length - 1] ^= 1;
        try { BackupFile.open(bin, key(row, "правильный пароль 123"), UID); fail(); } catch (BackupFile.BadPass ok) { }
    }

    @Test public void importValidation() throws Exception {
        JSONObject e = new JSONObject().put("chat_id", CHAT).put("message_id", "aaaaaaaa-0000-0000-0000-000000000009").put("plaintext", "x")
                .put("attachments", new JSONArray().put(new JSONObject().put("path", "chat/other-chat/f.bin").put("key", "k").put("nonce", "n")));
        java.util.Map<String, String> mine = new java.util.HashMap<>();
        assertNull("чужой message_id отбрасывается", BackupFile.toCache(e, mine));
        mine.put("aaaaaaaa-0000-0000-0000-000000000009", "dddddddd-0000-0000-0000-000000000000");
        assertNull("chat_id не совпадает с сервером", BackupFile.toCache(e, mine));
        mine.put("aaaaaaaa-0000-0000-0000-000000000009", CHAT);
        assertFalse("вложение из чужого чата отброшено", new JSONObject(BackupFile.toCache(e, mine)[1]).has("f"));
    }

    @Test public void androidBackupForSite() throws Exception {
        JSONObject row = new JSONObject().put("user_id", UID).put("salt", Base64.getEncoder().encodeToString(new byte[16])).put("kdf", BackupFile.defaultKdf());
        JSONObject m = new JSONObject().put("id", "a-1").put("chat_id", CHAT).put("sender_id", UID).put("created_at", "2026-10-21T11:00:00+00:00");
        JSONArray msgs = new JSONArray().put(BackupFile.toEntry(m, "{\"v\":1,\"t\":\"Привет с телефона\"}"));
        byte[] bin = BackupFile.seal(new JSONObject().put("v", 1).put("uid", UID).put("created_at", "2026-10-21T11:00:00Z").put("messages", msgs), key(row, "пароль телефона 456"), UID);
        assertFalse(new String(bin, "ISO-8859-1").contains("Привет"));
        Files.write(Paths.get("build/android_backup.json"), new JSONObject().put("row", row).put("bin", Base64.getEncoder().encodeToString(bin)).toString().getBytes("UTF-8"));
    }
}
