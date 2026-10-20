package com.darkcompany.messenger;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 35)
public class V14Test {
    private static JSONObject pl(String t, String cap, String n, String m) throws Exception {
        JSONObject p = new JSONObject().put("v", 1).put("t", t);
        if (cap != null) p.put("caption", cap);
        if (n != null) p.put("f", new JSONObject().put("n", n).put("m", m));
        return p;
    }
    @Test public void previewMarkers() throws Exception {
        assertEquals("🖼 Фото · Ну вот, как-то так", Cache.previewOf(pl("Ну вот, как-то так", null, "a.jpg", "image/jpeg")));
        assertEquals("🖼 Фото · Ну вот, как-то так", Cache.previewOf(pl("", "Ну вот, как-то так", "a.jpg", "image/jpeg")));
        assertEquals("🖼 Фото", Cache.previewOf(pl("", null, "a.jpg", "image/jpeg")));
        assertEquals("🎥 Видео · смотри", Cache.previewOf(pl("смотри", null, "clip.mp4", "video/mp4")));
        assertEquals("🎥 Видео", Cache.previewOf(pl("", null, "clip.mp4", "video/mp4")));
        assertEquals("📎 a.pdf · договор", Cache.previewOf(pl("договор", null, "a.pdf", "application/pdf")));
        assertEquals("📎 a.pdf", Cache.previewOf(pl("", null, "a.pdf", "application/pdf")));
        assertEquals("🎤 Голосовое 0:05", Cache.previewOf(new JSONObject("{\"v\":1,\"t\":\"\",\"f\":{\"vk\":\"voice\",\"d\":5}}")));
        assertEquals("⭕ Видеосообщение", Cache.previewOf(new JSONObject("{\"v\":1,\"t\":\"\",\"f\":{\"vk\":\"round\",\"d\":5}}")));
    }
    @Test public void notifyMarkers() throws Exception {
        assertEquals("🖼 Фото · Ну вот, как-то так", Notify.text(pl("Ну вот, как-то так", null, "a.jpg", "image/jpeg")));
        assertEquals("🖼 Фото", Notify.text(pl("", null, "a.jpg", "image/jpeg")));
        assertEquals("🎥 Видео · смотри", Notify.text(pl("смотри", null, "clip.mp4", "video/mp4")));
        assertEquals("📎 a.pdf · договор", Notify.text(pl("договор", null, "a.pdf", "application/pdf")));
    }
    @Test public void captionFormat() throws Exception {
        JSONObject f = new JSONObject().put("n", "a.jpg").put("m", "image/jpeg");
        JSONObject p = ChatActivity.filePayload(f, " Ну вот, как-то так ");
        assertEquals("", p.getString("t"));
        assertEquals("Ну вот, как-то так", p.getString("caption"));
        assertFalse(ChatActivity.filePayload(f, "").has("caption"));
        assertEquals("Ну вот, как-то так", ChatActivity.captionOf(p));
        assertEquals("подпись", ChatActivity.captionOf(new JSONObject().put("v", 1).put("t", "подпись").put("f", f)));
        assertEquals("", ChatActivity.captionOf(new JSONObject().put("v", 1).put("t", "").put("f", f)));
        assertEquals("🖼 Фото · Ну вот, как-то так", Cache.previewOf(p));
    }
}
