package com.darkcompany.messenger;

import static org.junit.Assert.*;

import android.app.Notification;
import android.app.NotificationManager;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** v10: свой профиль в шапке списка чатов (не «?») и уведомления как у сайта/PWA. */
@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, qualifiers = "w412dp-h915dp-night-420dpi")
public class V10Test {
    static final String A = ChatMsgShotTest.A, B = ChatMsgShotTest.B, CHAT = "03980fef-0000-4000-8000-0000000000e1";

    // ---- 1. свой профиль ----
    @Test public void myNameFallsBackToEmailThenQuestion() throws Exception {
        android.app.Application app = RuntimeEnvironment.getApplication();
        ChatMsgShotTest.noNet(app);
        Avatars.profiles.clear(); Avatars.prefs(app).edit().remove("me_row").commit();
        assertEquals("b", Avatars.myName(app)); // b@test.invalid
        assertEquals("?", Avatars.emailPrefix(""));
        assertEquals("?", Avatars.emailPrefix(null));
        assertEquals("dima", Avatars.emailPrefix("dima@x.ru"));
    }
    @Test public void cachedOwnProfileShownInChatsHeader() throws Exception {
        android.app.Application app = RuntimeEnvironment.getApplication();
        ChatMsgShotTest.noNet(app);
        Avatars.profiles.clear();
        // кэш с прошлого запуска (loadMe сохраняет строку profiles); чужая строка игнорируется
        Avatars.prefs(app).edit().putString("me_row", new JSONObject().put("id", B).put("display_name", "Чужой").toString()).commit();
        assertEquals("b", Avatars.myName(app));
        Avatars.profiles.clear();
        Avatars.prefs(app).edit().putString("me_row", new JSONObject().put("id", A).put("display_name", JSONObject.NULL).put("username", "dark").toString()).commit();
        assertEquals("dark", Avatars.myName(app));
        ChatsActivity a = Robolectric.buildActivity(ChatsActivity.class).setup().get();
        // офлайн-сессия «x» в тесте сбрасывается экраном — возвращаем её и перерисовываем шапку
        java.lang.reflect.Field f = Api.class.getDeclaredField("sp"); f.setAccessible(true);
        ((android.content.SharedPreferences) f.get(Api.get(app))).edit().putString("uid", A).putString("email", "b@test.invalid").putString("refresh", "x").commit();
        a.refreshMe();
        android.widget.TextView n = (android.widget.TextView) ChatMsgShotTest.field(a, "meName");
        assertEquals("dark", n.getText().toString());
        android.widget.LinearLayout btn = (android.widget.LinearLayout) ChatMsgShotTest.field(a, "meBtn");
        android.view.View av = btn.getChildAt(0);
        assertTrue(av instanceof android.widget.TextView);
        assertEquals("D", ((android.widget.TextView) av).getText().toString());
    }

    // ---- 2. тексты уведомлений как на сайте ----
    @Test public void notificationTexts() throws Exception {
        assertEquals("🎤 Голосовое сообщение", Notify.text(new JSONObject().put("t", "").put("f", new JSONObject().put("n", "voice_1792499725862.webm").put("m", "audio/webm"))));
        assertEquals("🎤 Голосовое сообщение 0:07", Notify.text(new JSONObject().put("f", new JSONObject().put("vk", "voice").put("d", 7))));
        assertEquals("🎤 Голосовое сообщение", Notify.text(new JSONObject().put("t", "Файл: voice_1792499725862.webm")));
        assertEquals("⭕ Видеосообщение", Notify.text(new JSONObject().put("f", new JSONObject().put("vk", "round"))));
        assertEquals("🖼 Фото", Notify.text(new JSONObject().put("f", new JSONObject().put("n", "a.jpg").put("m", "image/jpeg"))));
        assertEquals("📎 doc.pdf", Notify.text(new JSONObject().put("f", new JSONObject().put("n", "doc.pdf").put("m", "application/pdf"))));
        assertEquals("🎥 Видео", Notify.text(new JSONObject().put("f", new JSONObject().put("n", "clip.mp4").put("m", "video/mp4"))));
        assertEquals("Привет", Notify.text(new JSONObject().put("t", "Привет")));
    }

    static Notification find(NotificationManager nm) { for (android.service.notification.StatusBarNotification x : nm.getActiveNotifications()) if (x.getId() == CHAT.hashCode()) return x.getNotification(); return null; }

    private Notification post(android.app.Application app, boolean showText) throws Exception {
        ChatMsgShotTest.noNet(app);
        ((org.robolectric.shadows.ShadowApplication) org.robolectric.shadow.api.Shadow.extract(app)).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
        Notify.sp(app).edit().putBoolean("notify", true).putBoolean("notify_text", showText).commit();
        Notify.cancelAll(app);
        Avatars.profiles.put(B, new JSONObject().put("id", B).put("display_name", "Дмитрий 2").put("avatar_color", "#5C6BC0"));
        Notify.message(app, CHAT, "Дмитрий 2", "direct", B, B, "Дмитрий 2",
                Notify.text(new JSONObject().put("f", new JSONObject().put("n", "voice_1792499725862.webm").put("m", "audio/webm"))), "2026-10-20T12:35:00.123456+00:00");
        NotificationManager nm = app.getSystemService(NotificationManager.class);
        return find(nm);
    }

    @Test public void notificationLooksLikeSite() throws Exception {
        android.app.Application app = RuntimeEnvironment.getApplication();
        Notification n = post(app, true);
        assertNotNull(n);
        assertEquals(Notification.CATEGORY_MESSAGE, n.category);
        Notification.MessagingStyle st = (Notification.MessagingStyle) Notification.Builder.recoverBuilder(app, n).getStyle();
        Notification.MessagingStyle.Message m = st.getMessages().get(0);
        assertEquals("Дмитрий 2", m.getSenderPerson().getName().toString());
        assertNotNull(m.getSenderPerson().getIcon());
        assertEquals("🎤 Голосовое сообщение", m.getText().toString());
        assertEquals(2, n.actions.length);
        assertEquals("Ответить", n.actions[0].title.toString());
        assertNotNull(n.actions[0].getRemoteInputs());
        assertEquals(Notify.KEY_REPLY, n.actions[0].getRemoteInputs()[0].getResultKey());
        assertEquals("Прочитано", n.actions[1].title.toString());
        assertEquals("Мессенджер", app.getPackageManager().getApplicationLabel(app.getApplicationInfo()).toString());
        // рендер для отчёта
        try {
            android.widget.RemoteViews rv = Notification.Builder.recoverBuilder(app, n).createBigContentView();
            if (rv == null) rv = Notification.Builder.recoverBuilder(app, n).createContentView();
            android.view.View v = rv.apply(app, new android.widget.FrameLayout(app));
            int w = 1024; v.measure(android.view.View.MeasureSpec.makeMeasureSpec(w, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED));
            v.layout(0, 0, w, Math.max(1, v.getMeasuredHeight()));
            android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(w, Math.max(1, v.getMeasuredHeight()), android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas cv = new android.graphics.Canvas(b); cv.drawColor(0xFF2B2B2E); v.draw(cv);
            java.io.File out = new java.io.File("/workspace/messenger/shots/v10_notification.png");
            try (java.io.FileOutputStream fo = new java.io.FileOutputStream(out)) { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fo); }
            android.graphics.Bitmap ava = Avatars.bitmap(app, B, "Дмитрий 2", 144);
            try (java.io.FileOutputStream fo = new java.io.FileOutputStream("/workspace/messenger/shots/v10_notification_avatar.png")) { ava.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fo); }
        } catch (Throwable t) { System.err.println("render: " + t); }
    }

    @Test public void hiddenTextRespected() throws Exception {
        android.app.Application app = RuntimeEnvironment.getApplication();
        Notification n = post(app, false);
        Notification.MessagingStyle st = (Notification.MessagingStyle) Notification.Builder.recoverBuilder(app, n).getStyle();
        assertEquals("Новое сообщение", st.getMessages().get(0).getText().toString());
        assertEquals(Notification.VISIBILITY_PUBLIC, n.visibility);
    }

    @Test public void readActionCancels() throws Exception {
        android.app.Application app = RuntimeEnvironment.getApplication();
        assertNotNull(post(app, true));
        assertEquals("2026-10-20T12:35:00.123456+00:00", Notify.upto.get(CHAT));
        new NotifyActions().onReceive(app, new android.content.Intent(app, NotifyActions.class).setAction(Notify.ACT_READ).putExtra("chat_id", CHAT));
        NotificationManager nm = app.getSystemService(NotificationManager.class);
        assertNull(find(nm));
    }

    @Test public void replyFailureShownInNotification() throws Exception {
        android.app.Application app = RuntimeEnvironment.getApplication();
        assertNotNull(post(app, true));
        Notify.replied(app, CHAT, "Дмитрий 2", "direct", B, "ок", "Не отправлено: нет сети");
        Notification n = find(app.getSystemService(NotificationManager.class));
        assertEquals("Не отправлено: нет сети", n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT).toString());
        Notify.replied(app, CHAT, "Дмитрий 2", "direct", B, "ок", null);
        n = find(app.getSystemService(NotificationManager.class));
        Notification.MessagingStyle st = (Notification.MessagingStyle) Notification.Builder.recoverBuilder(app, n).getStyle();
        Notification.MessagingStyle.Message last = st.getMessages().get(st.getMessages().size() - 1);
        assertEquals("ок", last.getText().toString());
        assertNull(last.getSenderPerson()); // своё
    }
}
