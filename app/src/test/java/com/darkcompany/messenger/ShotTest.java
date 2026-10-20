package com.darkcompany.messenger;

import android.graphics.Bitmap;
import android.view.View;
import android.widget.LinearLayout;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** Рисует компоненты чата (шапка, пузыри, галочки, голосовое, кружок, поле ввода) в PNG для сравнения с сайтом. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w412dp-h915dp-night-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ShotTest {
    @Test public void chatComponents() throws Exception {
        androidx.appcompat.app.AppCompatActivity a = Robolectric.buildActivity(androidx.appcompat.app.AppCompatActivity.class).setup().get();
        a.setTheme(R.style.Theme_Messenger_Site);
        LinearLayout root = new LinearLayout(a); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(a.getColor(R.color.site_chatbg));
        LinearLayout head = Site.bar(a);
        head.addView(Site.iconButton(a, "arrow_back", "Назад", null));
        head.addView(Avatars.render(a, null, "Svetlana", "s", 40, null), new LinearLayout.LayoutParams(Ui.dp(a, 40), Ui.dp(a, 40)));
        LinearLayout t = new LinearLayout(a); t.setOrientation(LinearLayout.VERTICAL); t.setPadding(Ui.dp(a, 12), 0, 0, 0);
        t.addView(Site.label(a, "Svetlana", 17, Site.text(a), true)); t.addView(Site.label(a, "в сети", 14, Site.primary(a), false));
        head.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        head.addView(Site.iconButton(a, "more_vert", "Меню", null));
        root.addView(head);
        LinearLayout box = new LinearLayout(a); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(Ui.dp(a, 12), Ui.dp(a, 8), Ui.dp(a, 12), Ui.dp(a, 8));
        box.addView(Site.dateSep(a, "Сегодня"));
        box.addView(Site.bubble(a, false, null, "Привет! Как дела?", null, Site.meta(a, false, true, null, "12:00", 0), true));
        box.addView(Site.bubble(a, true, null, "Привет, всё отлично. Смотри новую версию", null, Site.meta(a, true, true, null, "12:01", 3), false));
        box.addView(Site.bubble(a, true, null, "Работает 👍", null, Site.meta(a, true, true, null, "12:02", 2), true));
        JSONObject v = new JSONObject("{\"vk\":\"voice\",\"d\":12,\"w\":[3,5,8,12,15,9,4,2,6,10,13,7,3,2,5,9,11,6,3,2,4,8,12,14,10,6,3,2,5,7,9,4,2,3,6,8,5,3,2,2]}");
        box.addView(Site.bubble(a, false, null, "", MediaPlay.view(a, false, v, null), Site.meta(a, false, true, null, "12:03", 0), true));
        JSONObject r = new JSONObject("{\"vk\":\"round\",\"d\":8}");
        box.addView(Site.bubble(a, true, null, "", MediaPlay.view(a, true, r, null), Site.meta(a, true, true, null, "12:04", 4), true));
        box.addView(Site.bubble(a, true, null, "Без сети", null, Site.meta(a, true, true, null, "12:05", 5), true));
        root.addView(box, new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout bar = new LinearLayout(a); bar.setGravity(android.view.Gravity.CENTER_VERTICAL); bar.setBackgroundColor(Site.surface(a)); bar.setPadding(Ui.dp(a, 6), Ui.dp(a, 8), Ui.dp(a, 6), Ui.dp(a, 8));
        bar.addView(Site.iconButton(a, "attach_file", "", null));
        android.widget.TextView in = Site.label(a, "Сообщение", 16, Site.text2(a), false);
        in.setBackground(Site.round(Site.container(a), Ui.dp(a, 24))); in.setPadding(Ui.dp(a, 16), Ui.dp(a, 12), Ui.dp(a, 16), Ui.dp(a, 12));
        bar.addView(in, new LinearLayout.LayoutParams(0, -2, 1f));
        bar.addView(Site.iconButton(a, "mic", "", null));
        root.addView(bar);
        snap(a, root, "native-chat.png");
    }
    @Test public void chatsComponents() throws Exception {
        androidx.appcompat.app.AppCompatActivity a = Robolectric.buildActivity(androidx.appcompat.app.AppCompatActivity.class).setup().get();
        a.setTheme(R.style.Theme_Messenger_Site);
        LinearLayout root = new LinearLayout(a); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(Site.surface(a));
        LinearLayout top = Site.bar(a);
        top.addView(Avatars.render(a, null, "Dark", "d", 40, null), new LinearLayout.LayoutParams(Ui.dp(a, 40), Ui.dp(a, 40)));
        android.widget.TextView n = Site.label(a, "Dark", 16, Site.text(a), true); n.setPadding(Ui.dp(a, 12), 0, 0, 0);
        top.addView(n, new LinearLayout.LayoutParams(0, -2, 1f)); top.addView(Site.iconButton(a, "more_vert", "", null));
        root.addView(top);
        LinearLayout tr = new LinearLayout(a); tr.setGravity(android.view.Gravity.CENTER_VERTICAL); tr.setPadding(Ui.dp(a, 16), Ui.dp(a, 12), Ui.dp(a, 16), Ui.dp(a, 4));
        tr.addView(Site.label(a, "Чаты", 20, Site.text(a), true)); tr.addView(Site.label(a, " (", 16, Site.text2(a), false)); tr.addView(Site.spinner(a));
        tr.addView(Site.label(a, " Соединение… )", 16, Site.text2(a), false));
        root.addView(tr);
        String[][] rows = {{"Svetlana", "Вы: Работает 👍", "12:03", "0"}, {"Учебная группа", "🔒 зашифрованное сообщение", "пн", "3"}};
        for (String[] r : rows) {
            JSONObject o = new JSONObject().put("chat_id", r[0]).put("_title", r[0]).put("last_at", "2026-10-20T09:03:00Z").put("last_body", "").put("unread", Integer.parseInt(r[3])).put("_preview", r[1]);
            root.addView(Ui.chatRow(a, o, "me"));
        }
        android.view.View sp = new android.view.View(a); root.addView(sp, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(Site.bottomNav(a, 0), new LinearLayout.LayoutParams(-1, Ui.dp(a, 56)));
        snap(a, root, "native-chats.png");
    }
    private static void fakeSession(android.content.Context c) throws Exception {
        java.lang.reflect.Field inst = Api.class.getDeclaredField("instance"); inst.setAccessible(true); inst.set(null, null);
        Api api = Api.get(c);
        java.lang.reflect.Field f = Api.class.getDeclaredField("sp"); f.setAccessible(true);
        ((android.content.SharedPreferences) f.get(api)).edit().putString("refresh", "x").putString("access", "x").putString("uid", "1f52839f-0000-4000-8000-00000000000a").putString("email", "b@test.invalid").apply();
        Avatars.profiles.put("1f52839f-0000-4000-8000-00000000000a", new JSONObject("{\"id\":\"1f52839f-0000-4000-8000-00000000000a\",\"username\":\"dark44\",\"display_name\":\"Dark\"}"));
        android.content.Context ac = c.getApplicationContext();
        Avatars.prefs(ac).edit().putInt("theme", 0).apply();
    }
    @Test @Config(qualifiers = "w412dp-h915dp-night-420dpi") public void settingsScreen() throws Exception {
        fakeSession(org.robolectric.RuntimeEnvironment.getApplication());
        SettingsActivity a = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        snap(a, a.getWindow().getDecorView(), "v7_native_settings.png");
    }
    @Test @Config(qualifiers = "w412dp-h915dp-night-420dpi") public void profileScreen() throws Exception {
        fakeSession(org.robolectric.RuntimeEnvironment.getApplication());
        ProfileActivity a = Robolectric.buildActivity(ProfileActivity.class).setup().get();
        snap(a, a.getWindow().getDecorView(), "v7_native_profile.png");
    }
    @Test @Config(qualifiers = "w412dp-h915dp-night-420dpi") public void loginScreen() throws Exception {
        java.lang.reflect.Field inst = Api.class.getDeclaredField("instance"); inst.setAccessible(true); inst.set(null, null);
        LoginActivity a = Robolectric.buildActivity(LoginActivity.class).setup().get();
        java.lang.reflect.Method qf = LoginActivity.class.getDeclaredMethod("qrFail", Exception.class); qf.setAccessible(true);
        qf.invoke(a, new Api.HttpError(503, "")); // то же состояние, что у сайта на моке (qr-create нет)
        snap(a, a.getWindow().getDecorView(), "v7_native_login.png");
    }
    @Test public void chatsScreen() throws Exception {
        fakeSession(org.robolectric.RuntimeEnvironment.getApplication());
        Avatars.profiles.put("0fcf4b38-0000-4000-8000-00000000000b", new JSONObject("{\"id\":\"0fcf4b38-0000-4000-8000-00000000000b\",\"username\":\"svetlana\",\"display_name\":\"Svetlana\"}"));
        ChatsActivity a = Robolectric.buildActivity(ChatsActivity.class).setup().get();
        long now = System.currentTimeMillis();
        org.json.JSONArray arr = new org.json.JSONArray()
            .put(new JSONObject().put("chat_id", "03980fef-0000-4000-8000-0000000000cc").put("kind", "direct").put("peer_id", "0fcf4b38-0000-4000-8000-00000000000b").put("title", "Svetlana").put("last_at", java.time.Instant.ofEpochSecond(now / 1000).toString()).put("last_sender", "1f52839f-0000-4000-8000-00000000000a").put("unread", 0))
            .put(new JSONObject().put("chat_id", "03980fef-0000-4000-8000-0000000000dd").put("kind", "group").put("title", "Учебная группа").put("last_at", java.time.Instant.ofEpochSecond(now / 1000 - 86400).toString()).put("last_sender", "0fcf4b38-0000-4000-8000-00000000000b").put("unread", 3));
        java.lang.reflect.Method m = ChatsActivity.class.getDeclaredMethod("applyChats", org.json.JSONArray.class); m.setAccessible(true); m.invoke(a, arr);
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        snap(a, a.getWindow().getDecorView(), "v7_native_chats.png");
    }
    @Test public void chatScreen() throws Exception {
        androidx.appcompat.app.AppCompatActivity a = Robolectric.buildActivity(androidx.appcompat.app.AppCompatActivity.class).setup().get();
        a.setTheme(R.style.Theme_Messenger_Site);
        LinearLayout root = new LinearLayout(a); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(a.getColor(R.color.site_chatbg));
        LinearLayout head = Site.bar(a);
        head.addView(Site.iconButton(a, "arrow_back", "Назад", null));
        LinearLayout peer = new LinearLayout(a); peer.setGravity(android.view.Gravity.CENTER_VERTICAL); peer.setPadding(Ui.dp(a, 6), Ui.dp(a, 4), Ui.dp(a, 6), Ui.dp(a, 4));
        peer.addView(Avatars.render(a, null, "Svetlana", "0fcf4b38-0000-4000-8000-00000000000b", 40, null), new LinearLayout.LayoutParams(Ui.dp(a, 40), Ui.dp(a, 40)));
        LinearLayout t = new LinearLayout(a); t.setOrientation(LinearLayout.VERTICAL); t.setPadding(Ui.dp(a, 10), 0, 0, 0);
        t.addView(Site.lh(Site.label(a, "Svetlana", 17, Site.text(a), 700), 20.4f)); t.addView(Site.lh(Site.label(a, "соединение…", 14, Site.text2(a), 400), 16.8f));
        peer.addView(t);
        head.addView(peer, new LinearLayout.LayoutParams(0, Ui.dp(a, 48), 1f));
        head.addView(Site.iconButton(a, "more_vert", "Меню", null));
        root.addView(head);
        LinearLayout box = new LinearLayout(a); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(Ui.dp(a, 12), Ui.dp(a, 8), Ui.dp(a, 12), Ui.dp(a, 8));
        box.addView(Site.dateSep(a, "Сегодня"));
        for (int i = 0; i < 4; i++) { boolean mine = i % 2 == 1;
            box.addView(Site.bubble(a, mine, null, "Работает 👍", null, Site.meta(a, mine, true, null, "12:0" + i, mine ? 1 : 0), true)); }
        root.addView(box, new LinearLayout.LayoutParams(-1, 0, 1f));
        LinearLayout bar = new LinearLayout(a); bar.setGravity(android.view.Gravity.CENTER_VERTICAL); bar.setBackgroundColor(Site.surface(a)); bar.setPadding(Ui.dp(a, 10), Ui.dp(a, 8), Ui.dp(a, 10), Ui.dp(a, 8));
        bar.addView(Site.iconButton(a, "attach_file", "", null));
        android.widget.EditText in = Site.input(a, "Сообщение"); in.setBackground(Site.round(Site.container(a), Ui.dp(a, 24)));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, Ui.dp(a, 46), 1f); ip.leftMargin = ip.rightMargin = Ui.dp(a, 6);
        bar.addView(in, ip);
        bar.addView(Site.iconButton(a, "mic", "", null));
        root.addView(bar);
        snap(a, root, "v7_native_chat.png");
    }
    static void snap(android.app.Activity a, View v, String name) throws Exception {
        int w = Ui.dp(a, 412), hh = Ui.dp(a, 915);
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(hh, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, w, hh);
        Bitmap b = Bitmap.createBitmap(w, hh, Bitmap.Config.ARGB_8888);
        v.draw(new android.graphics.Canvas(b));
        java.io.File out = new java.io.File("/workspace/messenger/shots/" + name);
        try (java.io.FileOutputStream f = new java.io.FileOutputStream(out)) { b.compress(Bitmap.CompressFormat.PNG, 100, f); }
    }
}
