package com.darkcompany.messenger;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Paths;

/** v15: копия совместима с web/backup.js в обе стороны (web_backup.msgrbak сделан tools/backup-interop/web_make.js). */
public class V15BackupTest {
    static final String UID = "11111111-2222-3333-4444-555555555555";

    @Test public void readsSiteBackup() throws Exception {
        byte[] f = Files.readAllBytes(Paths.get("src/test/resources/web_backup.msgrbak"));
        JSONObject o = BackupFile.read(f, "правильный пароль 123".toCharArray(), UID);
        JSONArray ms = o.getJSONArray("messages");
        assertEquals(2, ms.length());
        String[] c0 = BackupFile.toCache(ms.getJSONObject(0));
        assertEquals("m-1", c0[0]); assertEquals("Привет с сайта 👋", new JSONObject(c0[1]).getString("t"));
        JSONObject p1 = new JSONObject(BackupFile.toCache(ms.getJSONObject(1))[1]);
        assertEquals("подпись", p1.getString("caption")); assertEquals("chat/c-1/x_file.bin", p1.getJSONObject("f").getString("p"));
    }

    @Test public void wrongPasswordAndTamper() throws Exception {
        byte[] f = Files.readAllBytes(Paths.get("src/test/resources/web_backup.msgrbak"));
        try { BackupFile.read(f, "неверный пароль!!".toCharArray(), UID); fail(); } catch (BackupFile.BadPass ok) { }
        try { BackupFile.read(f, "правильный пароль 123".toCharArray(), "other-uid"); fail(); } catch (Exception ok) { assertFalse(ok instanceof IllegalStateException); }
        JSONObject j = new JSONObject(new String(f, "UTF-8"));
        byte[] bin = java.util.Base64.getDecoder().decode(j.getString("data")); bin[bin.length - 1] ^= 1;
        j.put("data", java.util.Base64.getEncoder().encodeToString(bin));
        try { BackupFile.read(j.toString().getBytes("UTF-8"), "правильный пароль 123".toCharArray(), UID); fail(); } catch (BackupFile.BadPass ok) { }
    }

    @Test public void androidExportForSite() throws Exception {
        JSONObject m = new JSONObject().put("id", "a-1").put("chat_id", "c-9").put("sender_id", UID).put("created_at", "2026-10-21T11:00:00+00:00");
        JSONArray msgs = new JSONArray().put(BackupFile.toEntry(m, "{\"v\":1,\"t\":\"Привет с телефона\"}"))
                .put(BackupFile.toEntry(new JSONObject(m.toString()).put("id", "a-2"), "старый простой текст"));
        byte[] f = BackupFile.export(msgs, "пароль телефона 456".toCharArray(), UID, "2026-10-21T11:00:00Z");
        String s = new String(f, "UTF-8");
        assertFalse(s.contains("Привет")); assertFalse(s.contains("пароль"));
        Files.write(Paths.get("build/android_backup.msgrbak"), f); // проверяется node tools/backup-interop/web_open.js
        JSONObject back = BackupFile.read(f, "пароль телефона 456".toCharArray(), UID);
        assertEquals("старый простой текст", back.getJSONArray("messages").getJSONObject(1).getString("plaintext"));
    }
}
