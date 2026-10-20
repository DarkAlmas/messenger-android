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
}
