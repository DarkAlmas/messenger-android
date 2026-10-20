package com.darkcompany.messenger;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Баги с телефона Димы (v7): даты «58771 г.», аватар «nu», «🔒 …» вместо «Отправлено до входа на этом устройстве». */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w412dp-h915dp-night-420dpi")
public class V9BugsTest {
    static final String A = ChatMsgShotTest.A, B = ChatMsgShotTest.B, CHAT = "03980fef-0000-4000-8000-0000000000e1";

    // ---- 1. время: везде миллисекунды ----
    @Test public void instantIsMillis() {
        long t = Ui.instant("2026-10-20T09:00:00.123456+00:00");
        assertEquals(java.time.Instant.parse("2026-10-20T09:00:00.123Z").toEpochMilli(), t);
        assertEquals(t * 1000 + 456, Ui.instantMicros("2026-10-20T09:00:00.123456+00:00"));
        java.util.Calendar c = java.util.Calendar.getInstance(); c.setTimeInMillis(t);
        assertEquals(2026, c.get(java.util.Calendar.YEAR));
    }
    @Test public void dateChipsAndListAndSeenHaveSaneYear() throws Exception {
        long now = java.time.Instant.parse("2026-12-20T12:00:00Z").toEpochMilli();
        long t = Ui.instant("2026-12-13T10:00:00+00:00");
        String chip = Site.dayLabel(t, now), list = Ui.listTime(t, now), seen = Site.seenText(t, now);
        assertEquals("13 декабря", chip);
        for (String s : new String[]{chip, list, seen}) assertFalse(s, s.contains("5877") || s.contains("5876"));
        assertEquals("Сегодня", Site.dayLabel(Ui.instant("2026-12-20T08:00:00+00:00"), now));
        // «без звука до» сравнивается с текущим временем в мс
        assertTrue(ChatsActivity.muted(new JSONObject().put("muted_until", "9999-12-31T00:00:00Z")));
        assertFalse(ChatsActivity.muted(new JSONObject().put("muted_until", "2020-01-01T00:00:00Z")));
    }

    // ---- 2. имя/аватар при null-полях ----
    @Test public void avatarLetterNeverNu() throws Exception {
        JSONObject p = new JSONObject().put("display_name", JSONObject.NULL).put("username", "svetlana").put("avatar_letter", JSONObject.NULL);
        assertEquals("svetlana", Avatars.displayName(p));
        assertEquals("S", Avatars.letterFor(p, null));
        assertEquals("S", Avatars.letterFor(p, "null"));
        JSONObject empty = new JSONObject().put("display_name", JSONObject.NULL).put("username", JSONObject.NULL).put("avatar_letter", JSONObject.NULL);
        assertEquals("?", Avatars.displayName(empty));
        assertEquals("?", Avatars.letterFor(empty, null));
        assertEquals("Д", Avatars.letterFor(new JSONObject().put("display_name", "дима"), null));
    }
    @Test public void chatHeaderUsesUsernameWhenDisplayNameNull() throws Exception {
        android.content.Context app = org.robolectric.RuntimeEnvironment.getApplication();
        ChatMsgShotTest.noNet(app);
        Avatars.profiles.put(B, new JSONObject().put("id", B).put("username", "svetlana").put("display_name", JSONObject.NULL).put("avatar_letter", JSONObject.NULL));
        android.content.Intent it = new android.content.Intent(app, ChatActivity.class).putExtra("chat_id", CHAT).putExtra("kind", "direct").putExtra("peer_id", B).putExtra("title", "null");
        ChatActivity a = Robolectric.buildActivity(ChatActivity.class, it).setup().get();
        android.widget.TextView title = (android.widget.TextView) ChatMsgShotTest.field(a, "titleV");
        assertEquals("svetlana", title.getText().toString());
        android.widget.FrameLayout ava = (android.widget.FrameLayout) ChatMsgShotTest.field(a, "avaBox");
        assertEquals("S", ((android.widget.TextView) ava.getChildAt(0)).getText().toString());
    }

    // ---- 3. «Отправлено до входа на этом устройстве» сразу, без ожидания ключей ----
    @Test public void notForMeWithoutKeys() throws Exception {
        android.content.Context app = org.robolectric.RuntimeEnvironment.getApplication();
        ChatMsgShotTest.noNet(app);
        E2eCore core = E2e.get(app);
        assertTrue(core.notForMe(new JSONObject().put("other-device-1", new JSONObject().put("t", 3).put("b", "x").put("f", "o"))));
        assertFalse(core.notForMe(new JSONObject().put("sk", 1)));
        assertFalse(core.notForMe(new JSONObject()));
    }
    @Test public void otherDeviceShownEverywhere() throws Exception {
        android.content.Context app = org.robolectric.RuntimeEnvironment.getApplication();
        ChatMsgShotTest.noNet(app);
        android.content.Intent it = new android.content.Intent(app, ChatActivity.class).putExtra("chat_id", CHAT).putExtra("kind", "direct").putExtra("peer_id", B).putExtra("title", "Svetlana");
        ChatActivity a = Robolectric.buildActivity(ChatActivity.class, it).setup().get();
        java.lang.reflect.Field spf = Api.class.getDeclaredField("sp"); spf.setAccessible(true);
        ((android.content.SharedPreferences) spf.get(Api.get(a))).edit().putString("uid", A).commit();
        JSONObject ct = new JSONObject().put("some-old-device", new JSONObject().put("t", 3).put("b", "eA==").put("f", "x"));
        String id1 = "dddddddd-0000-4000-8000-000000000001", id2 = "dddddddd-0000-4000-8000-000000000002";
        JSONArray arr = new JSONArray()
            .put(new JSONObject().put("id", id1).put("sender_id", B).put("created_at", "2026-10-20T09:00:00+00:00").put("ciphertext", ct).put("body", "").put("reply_to", JSONObject.NULL).put("edited_at", JSONObject.NULL).put("attachment_path", JSONObject.NULL))
            .put(new JSONObject().put("id", id2).put("sender_id", A).put("created_at", "2026-10-20T09:01:00+00:00").put("ciphertext", new JSONObject()).put("body", "").put("reply_to", id1).put("edited_at", JSONObject.NULL).put("attachment_path", JSONObject.NULL));
        @SuppressWarnings("unchecked") java.util.Map<String, JSONObject> dec = (java.util.Map<String, JSONObject>) ChatMsgShotTest.field(a, "dec");
        dec.put(id2, new JSONObject().put("t", "ответ"));
        java.lang.reflect.Method r = ChatActivity.class.getDeclaredMethod("render", JSONArray.class, JSONArray.class, JSONArray.class); r.setAccessible(true);
        r.invoke(a, arr, new JSONArray(), new JSONArray());
        // лента: серая строка, без «🔒 …»
        StringBuilder sb = new StringBuilder(); collect((android.view.View) ChatMsgShotTest.field(a, "box"), sb);
        String all = sb.toString();
        assertTrue(all, all.contains(ChatActivity.OTHER_DEV));
        assertFalse(all, all.contains("🔒 …") || all.contains("Зашифровано для другого устройства") || all.contains("Расшифровка…"));
        // цитата в ответе и превью для закрепа / списка чатов
        assertTrue(all, all.split(java.util.regex.Pattern.quote(ChatActivity.OTHER_DEV), -1).length - 1 >= 2);
        @SuppressWarnings("unchecked") java.util.Map<String, String> previews = (java.util.Map<String, String>) ChatMsgShotTest.field(a, "previews");
        assertEquals(ChatActivity.OTHER_DEV, previews.get(id1));
    }
    static void collect(android.view.View v, StringBuilder sb) {
        if (v instanceof android.widget.TextView) sb.append(((android.widget.TextView) v).getText()).append('\n');
        if (v instanceof android.view.ViewGroup) for (int i = 0; i < ((android.view.ViewGroup) v).getChildCount(); i++) collect(((android.view.ViewGroup) v).getChildAt(i), sb);
    }
}
