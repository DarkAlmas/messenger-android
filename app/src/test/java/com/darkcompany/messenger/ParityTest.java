package com.darkcompany.messenger;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Без сервера: галочки, шапка, превью голосовых/кружков, волна, офлайн-кэш. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class ParityTest {
    @Test public void ticks() {
        assertEquals(1, ChatActivity.tickState(100, 0, 0));
        assertEquals(1, ChatActivity.tickState(100, 50, 0));
        assertEquals(2, ChatActivity.tickState(100, 100, 50));
        assertEquals(3, ChatActivity.tickState(100, 200, 150));
    }
    @Test public void header() {
        assertEquals("Чаты (Ожидание сети…)", ChatsActivity.titleFor(false, false));
        assertEquals("Чаты (Соединение…)", ChatsActivity.titleFor(true, false));
        assertEquals("Чаты", ChatsActivity.titleFor(true, true));
    }
    @Test public void previews() throws Exception {
        assertEquals("🎤 Голосовое 0:12", Cache.previewOf(new JSONObject("{\"v\":1,\"t\":\"\",\"f\":{\"vk\":\"voice\",\"d\":12}}")));
        assertEquals("⭕ Видеосообщение", Cache.previewOf(new JSONObject("{\"v\":1,\"t\":\"\",\"f\":{\"vk\":\"round\",\"d\":8}}")));
        assertEquals("📎 a.pdf", Cache.previewOf(new JSONObject("{\"v\":1,\"t\":\"\",\"f\":{\"n\":\"a.pdf\",\"m\":\"application/pdf\"}}")));
        assertEquals("привет", Cache.previewOf(new JSONObject("{\"v\":1,\"t\":\"привет\"}")));
        assertEquals("1:05", Cache.fmtDur(65));
    }
    @Test public void wave() {
        java.util.List<Integer> w = new java.util.ArrayList<>();
        for (int i = 0; i < 200; i++) w.add(i * 10);
        JSONArray a = Recorder.squash(w, 40);
        assertEquals(40, a.length());
        for (int i = 0; i < 40; i++) assertTrue(a.optInt(i) >= 1 && a.optInt(i) <= 15);
        assertEquals(15, a.optInt(39));
        assertEquals(20, MediaPlay.waveText(a).length());
    }
    @Test public void offlineCacheKeepsLast100() throws Exception {
        android.content.Context c = RuntimeEnvironment.getApplication();
        JSONArray msgs = new JSONArray(); java.util.Map<String, JSONObject> dec = new java.util.HashMap<>();
        for (int i = 0; i < 150; i++) { msgs.put(new JSONObject().put("id", "m" + i).put("created_at", "2026-10-15T10:00:00Z")); dec.put("m" + i, new JSONObject().put("v", 1).put("t", "x" + i)); }
        Cache.putChat(c, "chat-1", msgs, dec);
        JSONObject got = Cache.chat(c, "chat-1");
        assertEquals(100, got.getJSONArray("m").length());
        assertEquals("m50", got.getJSONArray("m").getJSONObject(0).getString("id"));
        assertEquals("x149", got.getJSONObject("d").getJSONObject("m149").getString("t"));
        Cache.putChats(c, "[{\"chat_id\":\"chat-1\"}]");
        assertEquals(1, Cache.chats(c).length());
        Cache.wipe(c);
        assertNull(Cache.chat(c, "chat-1"));
    }
    @Test public void updateTag() {
        assertEquals(4, Updater.codeOf("v4"));
        assertEquals(12, Updater.codeOf("12-native"));
        assertEquals(-1, Updater.codeOf("latest"));
    }
    @Test public void seenAndDays() {
        long now = java.time.ZonedDateTime.of(2026, 10, 20, 14, 0, 0, 0, java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        assertEquals("в сети", Site.seenText(now - 30000, now));
        assertEquals("был(а) 5 мин. назад", Site.seenText(now - 5 * 60000, now));
        assertEquals("был(а) недавно", Site.seenText(0, now));
        assertEquals("Сегодня", Site.dayLabel(now - 3600000, now));
        assertEquals("Вчера", Site.dayLabel(now - 24 * 3600000L, now));
        assertEquals("15 октября", Site.dayLabel(now - 5 * 24 * 3600000L, now));
    }
    @Test public void updateHosts() {
        assertTrue(Updater.allowedHost("https://github.com/DarkAlmas/messenger-android/releases/download/v5/a.apk"));
        assertTrue(Updater.allowedHost("https://release-assets.githubusercontent.com/x"));
        assertTrue(Updater.allowedHost("https://objects.githubusercontent.com/x"));
        assertFalse(Updater.allowedHost("http://github.com/x"));
        assertFalse(Updater.allowedHost("https://github.com.evil.com/x"));
        assertFalse(Updater.allowedHost("https://evil.com/github.com"));
    }

    @org.junit.Test public void sniffMedia() {
        byte[] webm = new byte[64]; webm[0]=0x1A; webm[1]=0x45; webm[2]=(byte)0xDF; webm[3]=(byte)0xA3; System.arraycopy("webm".getBytes(),0,webm,20,4);
        org.junit.Assert.assertEquals(".webm", MediaPlay.sniffExt(webm, "audio/mp4"));
        byte[] mkv = webm.clone(); System.arraycopy("matroska".getBytes(),0,mkv,20,8);
        org.junit.Assert.assertEquals(".mkv", MediaPlay.sniffExt(mkv, "video/webm"));
        byte[] mp4 = new byte[16]; System.arraycopy("ftypisom".getBytes(),0,mp4,4,8);
        org.junit.Assert.assertEquals(".mp4", MediaPlay.sniffExt(mp4, "audio/webm"));
        org.junit.Assert.assertEquals(".webm", MediaPlay.sniffExt(new byte[3], "audio/webm"));
    }
}
