package com.darkcompany.messenger;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** Настоящий ChatActivity.render() на том же наборе сообщений, что и сайт (shots/v8/msgsets.json). Сеть отключена. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w412dp-h915dp-night-420dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ChatMsgShotTest {
    static final String A = "1f52839f-0000-4000-8000-00000000000a", B = "0fcf4b38-0000-4000-8000-00000000000b";
    static Object field(Object o, String n) throws Exception { java.lang.reflect.Field f = o.getClass().getDeclaredField(n); f.setAccessible(true); return f.get(o); }
    static void noNet(android.content.Context c) throws Exception {
        java.lang.reflect.Field inst = Api.class.getDeclaredField("instance"); inst.setAccessible(true); inst.set(null, null);
        Api api = Api.get(c);
        java.lang.reflect.Field h = Api.class.getDeclaredField("http"); h.setAccessible(true);
        h.set(api, new okhttp3.OkHttpClient.Builder().addInterceptor(ch -> { throw new java.io.IOException("offline (test)"); }).build());
        java.lang.reflect.Field f = Api.class.getDeclaredField("sp"); f.setAccessible(true);
        ((android.content.SharedPreferences) f.get(api)).edit().putString("refresh", "x").putString("access", "x").putString("uid", A).putString("email", "b@test.invalid").apply();
        Avatars.profiles.put(A, new JSONObject().put("id", A).put("username", "dark44").put("display_name", "Dark"));
        Avatars.profiles.put(B, new JSONObject().put("id", B).put("username", "svetlana").put("display_name", "Svetlana"));
    }
    void shoot(String name) throws Exception {
        JSONObject S = new JSONObject(new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("/workspace/messenger/shots/v8/msgsets.json")), "UTF-8")).getJSONObject(name);
        android.content.Context app = org.robolectric.RuntimeEnvironment.getApplication();
        noNet(app);
        android.content.Intent it = new android.content.Intent(app, ChatActivity.class).putExtra("chat_id", S.getString("chat")).putExtra("kind", "direct").putExtra("peer_id", B).putExtra("title", "Svetlana");
        ChatActivity a = Robolectric.buildActivity(ChatActivity.class, it).setup().get();
        @SuppressWarnings("unchecked") java.util.Map<String, JSONObject> dec = (java.util.Map<String, JSONObject>) field(a, "dec");
        @SuppressWarnings("unchecked") java.util.Map<String, android.graphics.Bitmap> thumbs = (java.util.Map<String, android.graphics.Bitmap>) field(a, "thumbs");
        @SuppressWarnings("unchecked") java.util.Set<String> off = (java.util.Set<String>) field(a, "offlineImgs");
        java.lang.reflect.Field on = ChatActivity.class.getDeclaredField("online"); on.setAccessible(true);
        JSONArray arr = new JSONArray(), reacts = new JSONArray();
        JSONArray ms = S.getJSONArray("msgs");
        for (int i = 0; i < ms.length(); i++) {
            JSONObject x = ms.getJSONObject(i);
            JSONObject row = new JSONObject().put("id", x.getString("id")).put("sender_id", "A".equals(x.getString("who")) ? A : B).put("created_at", x.getString("at"))
                    .put("ciphertext", new JSONObject().put("sk", "S")).put("body", "").put("reply_to", x.has("reply_to") ? x.get("reply_to") : JSONObject.NULL)
                    .put("edited_at", x.has("edited_at") ? x.get("edited_at") : JSONObject.NULL).put("attachment_path", JSONObject.NULL);
            arr.put(row);
            JSONObject pl = x.getJSONObject("pl");
            dec.put(x.getString("id"), pl.optBoolean("other") ? new JSONObject().put("err", "other") : pl);
            JSONObject f = pl.optJSONObject("f");
            if (f != null && f.optString("p").contains("imgok")) thumbs.put(f.getString("p"), android.graphics.BitmapFactory.decodeFile("/workspace/messenger/shots/v8/test_img.png"));
            if (f != null && f.optString("p").contains("imgoff")) { off.add(f.getString("p")); }
        }
        if (S.has("reacts")) for (int i = 0; i < S.getJSONArray("reacts").length(); i++) { JSONArray r = S.getJSONArray("reacts").getJSONArray(i);
            reacts.put(new JSONObject().put("message_id", r.getString(0)).put("user_id", "A".equals(r.getString(1)) ? A : B).put("emoji", r.getString(2))); }
        JSONArray rd = S.getJSONArray("read");
        JSONArray reads = new JSONArray().put(new JSONObject().put("user_id", B).put("last_read_at", rd.getString(0)).put("last_delivered_at", rd.getString(1)));
        if (S.has("failed")) { @SuppressWarnings("unchecked") java.util.Map<String, JSONObject> ob = (java.util.Map<String, JSONObject>) field(a, "outbox");
            JSONArray fl = S.getJSONArray("failed"); for (int i = 0; i < fl.length(); i++) { JSONObject j = fl.getJSONObject(i);
                ob.put("job" + i, new JSONObject().put("id", "cccccccc-0000-4000-8000-00000000000" + i).put("text", j.getString("t")).put("at", j.getString("at")).put("failed", true)); } }
        on.set(a, false);
        java.lang.reflect.Field spf = Api.class.getDeclaredField("sp"); spf.setAccessible(true);
        ((android.content.SharedPreferences) spf.get(Api.get(a))).edit().putString("uid", A).putString("refresh", "x").commit();
        ((android.view.View) field(a, "banner")).setVisibility(android.view.View.GONE);
        java.lang.reflect.Field le = ChatActivity.class.getDeclaredField("lastFetchErr"); le.setAccessible(true); le.set(a, Long.MAX_VALUE);
        android.view.View tv = (android.view.View) field(a, "toastV"); if (tv != null) tv.setVisibility(android.view.View.GONE);
        java.lang.reflect.Method r = ChatActivity.class.getDeclaredMethod("render", JSONArray.class, JSONArray.class, JSONArray.class); r.setAccessible(true);
        r.invoke(a, arr, reacts, reads);
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        ((android.view.View) field(a, "banner")).setVisibility(android.view.View.GONE);
        { android.view.View tv2 = (android.view.View) field(a, "toastV"); if (tv2 != null) tv2.setVisibility(android.view.View.GONE); }
        android.view.View root = a.getWindow().getDecorView();
        ShotTest.snap(a, root, "v8/native_" + name + ".png");
        android.widget.ScrollView sc = (android.widget.ScrollView) field(a, "scroll");
        sc.scrollTo(0, 100000);
        ShotTest.snap(a, root, "v8/native_" + name + ".png");
        StringBuilder sb = new StringBuilder(); dump(root, 0, sb, a.getResources().getDisplayMetrics().density);
        java.nio.file.Files.write(java.nio.file.Paths.get("/workspace/messenger/shots/v8/native_" + name + ".txt"), sb.toString().getBytes("UTF-8"));
    }
    static void dump(android.view.View v, int depth, StringBuilder sb, float d) {
        if (v.getVisibility() != android.view.View.VISIBLE) return;
        int[] xy = new int[2]; v.getLocationInWindow(xy);
        sb.append("  ".repeat(Math.min(depth, 30))).append(v.getClass().getSimpleName()).append(' ').append(Math.round(xy[0] / d)).append(',').append(Math.round(xy[1] / d)).append(' ')
          .append(Math.round(v.getWidth() / d)).append('x').append(Math.round(v.getHeight() / d));
        if (v instanceof android.widget.TextView) sb.append(" '").append(((android.widget.TextView) v).getText().toString().replace("\n", " "), 0, Math.min(18, ((android.widget.TextView) v).getText().length())).append("'");
        sb.append('\n');
        if (v instanceof android.view.ViewGroup) for (int i = 0; i < ((android.view.ViewGroup) v).getChildCount(); i++) dump(((android.view.ViewGroup) v).getChildAt(i), depth + 1, sb, d);
    }
    @Test public void settingsNotif() throws Exception {
        android.content.Context app = org.robolectric.RuntimeEnvironment.getApplication();
        noNet(app);
        ((org.robolectric.shadows.ShadowApplication) org.robolectric.shadow.api.Shadow.extract(app)).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS);
        SettingsActivity a = Robolectric.buildActivity(SettingsActivity.class).setup().get();
        android.view.View row = (android.view.View) field(a, "rowNotif");
        android.view.View root = a.getWindow().getDecorView();
        ShotTest.snap(a, root, "v8/native_settings_full.png");
        android.view.View p = row; while (p != null && !(p instanceof android.widget.ScrollView)) p = (android.view.View) p.getParent();
        int[] xy = new int[2]; row.getLocationInWindow(xy);
        if (p != null) p.scrollTo(0, xy[1] - (int) (200 * a.getResources().getDisplayMetrics().density));
        ShotTest.snap(a, root, "v8/native_settings_notif.png");
    }
    @Test public void text() throws Exception { shoot("text"); }
    @Test public void media1() throws Exception { shoot("media1"); }
    @Test public void media2() throws Exception { shoot("media2"); }
}
