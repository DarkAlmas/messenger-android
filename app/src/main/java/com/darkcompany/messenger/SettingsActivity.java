package com.darkcompany.messenger;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** «Настройки» как на сайте (index.html data-tab="settings" + скриншот Димы). */
public class SettingsActivity extends AppCompatActivity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private LinearLayout body;
    private View rowSeen, rowDevices, rowBackup, rowStorage, rowTheme;
    private LinearLayout seenBox, themeBox;
    private TextView blockList;
    private static final String[] SEEN_V = {"everyone", "contacts", "nobody"};
    private static final String[] SEEN_L = {"Все", "Мои контакты", "Никто"};

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!Api.get(this).hasSession()) { finish(); return; }
        body = Site.scaffold(this, Site.pageTitle(this, "Настройки", null, null, null), 1);
        build();
    }

    @Override protected void onResume() {
        super.onResume();
        io.execute(() -> {
            boolean ch = Avatars.refresh(this, Collections.singleton(Api.get(this).uid()), 0);
            if (ch) runOnUiThread(this::build);
            loadSeen(); loadDevCount(); loadBackup();
        });
        paintStorage();
    }

    private void build() {
        body.removeAllViews();
        String uid = Api.get(this).uid();
        JSONObject p = Avatars.merged(this, uid);
        String un = p.optString("username", "");
        String dn = p.isNull("display_name") || p.optString("display_name").isEmpty() ? un : p.optString("display_name");
        // .mini-head
        LinearLayout mh = new LinearLayout(this); mh.setGravity(Gravity.CENTER_VERTICAL);
        mh.setPadding(Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 20), Ui.dp(this, 8));
        mh.addView(Avatars.view(this, uid, dn, 72), new LinearLayout.LayoutParams(Ui.dp(this, 72), Ui.dp(this, 72)));
        LinearLayout tc = new LinearLayout(this); tc.setOrientation(LinearLayout.VERTICAL); tc.setPadding(Ui.dp(this, 20), 0, 0, 0);
        tc.addView(Site.label(this, dn.isEmpty() ? "Мой профиль" : dn, 19, Site.text(this), true));
        tc.addView(Site.label(this, un.isEmpty() ? "" : "@" + un, 15, Site.text2(this), false));
        mh.addView(tc, new LinearLayout.LayoutParams(0, -2, 1f));
        mh.setOnClickListener(v -> openProfile());
        body.addView(mh);

        LinearLayout g = Site.group(this, body, "Аккаунт");
        Site.row(this, g, "person", "Профиль", null, null, v -> openProfile());
        rowSeen = Site.row(this, g, "lock", "Конфиденциальность", null, SEEN_L[seenIdx()], v -> toggle(seenBox));
        seenBox = new LinearLayout(this); seenBox.setOrientation(LinearLayout.VERTICAL); seenBox.setVisibility(View.GONE);
        g.addView(seenBox);
        paintSeenBox();
        rowDevices = Site.row(this, g, "devices", "Устройства", null, getSharedPreferences("ui", 0).getString("dev_count", ""), v -> devices());
        Site.row(this, g, "key", "Шифрование", null, E2e.lastError == null ? "Включено" : "Ошибка", v -> thisDevice());
        rowBackup = Site.row(this, g, "cloud_upload", "Резервная копия чатов", null, getSharedPreferences("ui", 0).getString("backup_val", "Выкл."), v -> backup());

        g = Site.group(this, body, "Оформление");
        rowTheme = Site.row(this, g, "palette", "Тема", null, Avatars.THEMES[Avatars.themeIndex(this)], v -> toggle(themeBox));
        themeBox = new LinearLayout(this); themeBox.setOrientation(LinearLayout.VERTICAL); themeBox.setVisibility(View.GONE);
        g.addView(themeBox);
        paintThemeBox();

        g = Site.group(this, body, "Уведомления");
        LinearLayout nr = Site.row(this, g, "notifications", "Уведомления", null, null, null);
        com.google.android.material.materialswitch.MaterialSwitch sw = new com.google.android.material.materialswitch.MaterialSwitch(this);
        sw.setChecked(Notify.enabled(this));
        sw.setOnCheckedChangeListener((bt, on) -> {
            Notify.setEnabled(this, on);
            if (on && android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 7);
            Ui.toast(this, on ? "Уведомления включены" : "Уведомления выключены");
        });
        nr.addView(sw);
        nr.setOnClickListener(v -> sw.toggle());
        Site.row(this, g, "volume_up", "Звук", null, "Системный", v -> {
            Intent i = new Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName()).putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, Notify.CH);
            try { startActivity(i); } catch (Exception e) { Ui.toast(this, "Не удалось открыть настройки звука"); }
        });

        g = Site.group(this, body, "Данные");
        String mail = Api.get(this).email();
        Site.row(this, g, "mail", "Почта", null, mail.isEmpty() ? "—" : mail, v -> new MaterialAlertDialogBuilder(this).setTitle("Почта")
                .setMessage("Почта аккаунта: " + (mail.isEmpty() ? "—" : mail) + "\n\nПочта используется для входа. Её видите только вы.").setPositiveButton("OK", null).show());
        rowStorage = Site.row(this, g, "storage", "Хранилище", null, "—", v -> storage());
        Site.row(this, g, "language", "Язык", null, "Русский", v -> new MaterialAlertDialogBuilder(this).setTitle("Язык")
                .setSingleChoiceItems(new String[]{"Русский"}, 0, null).setMessage(null).setPositiveButton("OK", null).show());

        g = Site.group(this, body, "Прочее");
        Site.row(this, g, "help", "Помощь", null, "о шифровании", v -> Ui.toast(this,
                "Сообщения и файлы шифруются на устройстве (Signal). Сервер их не видит. Профиль виден всем и не шифруется."));
        Site.row(this, g, "group_add", "Новая группа", null, null, v -> toChats("new_group"));
        Site.row(this, g, "qr_code_scanner", "Войти на сайте (QR)", null, null, v -> toChats("qr"));
        Site.row(this, g, "system_update", "Проверить обновления", null, null, v -> Updater.check(this, true));
        Site.row(this, g, "logout", "Выйти", null, null, v -> new MaterialAlertDialogBuilder(this).setTitle("Выйти из аккаунта?")
                .setMessage("Ключи шифрования этого устройства будут удалены. После входа телефон станет новым устройством.")
                .setPositiveButton("Выйти", (d, w) -> ChatsActivity.logout(this)).setNegativeButton("Отмена", null).show());

        String ver = "?";
        try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
        TextView bi = Site.label(this, "Версия " + ver + " · сборка " + BuildConfig.BUILD_TIME, 12, Site.text2(this), false);
        bi.setGravity(Gravity.CENTER); bi.setPadding(0, Ui.dp(this, 16), 0, 0);
        body.addView(bi, new LinearLayout.LayoutParams(-1, -2));
        paintStorage();
    }

    private void openProfile() { startActivity(new Intent(this, ProfileActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)); }
    private void toChats(String act) { startActivity(new Intent(this, ChatsActivity.class).putExtra("action", act).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)); }
    private static void toggle(View v) { v.setVisibility(v.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); }

    // ---------- Конфиденциальность (last_seen_visibility + заблокированные) ----------
    private int seenIdx() { String v = getSharedPreferences("ui", 0).getString("seen", "everyone"); for (int i = 0; i < 3; i++) if (SEEN_V[i].equals(v)) return i; return 0; }
    private void paintSeenBox() {
        seenBox.removeAllViews();
        TextView h = Site.label(this, "Кто видит, когда я был(а) в сети", 13, Site.text2(this), false);
        h.setPadding(Ui.dp(this, 20), Ui.dp(this, 4), Ui.dp(this, 20), Ui.dp(this, 6));
        seenBox.addView(h);
        seenBox.addView(Site.seg(this, SEEN_L, seenIdx(), i -> io.execute(() -> {
            try {
                Api.get(this).update("profiles?id=eq." + Api.get(this).uid(), new JSONObject().put("last_seen_visibility", SEEN_V[i]));
                getSharedPreferences("ui", 0).edit().putString("seen", SEEN_V[i]).apply();
                runOnUiThread(() -> { paintSeenBox(); Site.setValue(rowSeen, SEEN_L[i]); });
                Ui.toast(this, "Сохранено");
            } catch (Exception e) { Ui.toast(this, "Не сохранено: " + Ui.err(e)); }
        })));
        TextView bh = Site.label(this, "Заблокированные", 13, Site.text2(this), false);
        bh.setPadding(Ui.dp(this, 20), Ui.dp(this, 4), Ui.dp(this, 20), 0);
        seenBox.addView(bh);
        blockList = Site.label(this, "…", 14, Site.text(this), false);
        blockList.setPadding(Ui.dp(this, 20), Ui.dp(this, 4), Ui.dp(this, 20), Ui.dp(this, 14));
        seenBox.addView(blockList);
        io.execute(this::loadBlocks);
    }
    private void loadSeen() {
        JSONObject p = Avatars.profiles.get(Api.get(this).uid());
        if (p == null || !p.has("last_seen_visibility")) {
            try { JSONArray r = Api.get(this).select("profiles?select=last_seen_visibility&id=eq." + Api.get(this).uid()); if (r.length() > 0) p = r.getJSONObject(0); } catch (Exception e) { return; }
        }
        if (p == null || p.isNull("last_seen_visibility")) return;
        getSharedPreferences("ui", 0).edit().putString("seen", p.optString("last_seen_visibility", "everyone")).apply();
        runOnUiThread(() -> { Site.setValue(rowSeen, SEEN_L[seenIdx()]); paintSeenBox(); });
    }
    private void loadBlocks() {
        String txt;
        try {
            JSONArray r = Api.get(this).select("blocks?select=blocked,created_at&blocker=eq." + Api.get(this).uid() + "&order=created_at.desc");
            if (r.length() == 0) txt = "Никого";
            else {
                java.util.Set<String> ids = new java.util.LinkedHashSet<>();
                for (int i = 0; i < r.length(); i++) ids.add(r.getJSONObject(i).getString("blocked"));
                Avatars.refresh(this, ids, 60000);
                StringBuilder sb = new StringBuilder();
                for (String id : ids) {
                    JSONObject p = Avatars.profiles.get(id);
                    if (sb.length() > 0) sb.append('\n');
                    if (p == null) sb.append("удалённый пользователь");
                    else sb.append(p.isNull("display_name") || p.optString("display_name").isEmpty() ? p.optString("username") : p.optString("display_name")).append(" · @").append(p.optString("username"));
                }
                txt = sb.toString();
            }
        } catch (Exception e) { txt = "Не удалось загрузить: " + Ui.err(e); }
        final String t = txt;
        runOnUiThread(() -> { if (blockList != null) blockList.setText(t); });
    }

    // ---------- Тема ----------
    private void paintThemeBox() {
        themeBox.removeAllViews();
        // порядок как на сайте: Системная / Светлая / Тёмная → индексы Avatars.THEMES 2/1/0
        final int[] map = {2, 1, 0};
        int cur = Avatars.themeIndex(this), act = cur == 2 ? 0 : cur == 1 ? 1 : 2;
        themeBox.addView(Site.seg(this, new String[]{"Системная", "Светлая", "Тёмная"}, act, i -> {
            Avatars.prefs(this).edit().putInt("theme", map[i]).apply();
            Avatars.applyTheme(this);
        }));
    }

    // ---------- Устройства (list_devices / revoke_device, как на сайте) ----------
    private void loadDevCount() {
        try {
            JSONArray d = new JSONArray(Api.get(this).rpc("list_devices", new JSONObject()));
            String n = String.valueOf(d.length());
            getSharedPreferences("ui", 0).edit().putString("dev_count", n).apply();
            runOnUiThread(() -> Site.setValue(rowDevices, n));
        } catch (Exception ignored) { }
    }
    static String[] uaName(String ua) {
        if (ua == null) ua = "";
        if (ua.isEmpty()) return new String[]{"Браузер", "devices"};
        String b = ua.contains("Edg/") ? "Edge" : ua.matches("(?s).*(OPR/|Opera).*") ? "Opera" : ua.contains("YaBrowser") ? "Яндекс Браузер" : ua.contains("Firefox/") ? "Firefox"
                : ua.contains("Chrome/") ? "Chrome" : ua.contains("Safari/") ? "Safari" : ua.matches("(?is).*(okhttp|dalvik|supabase|java|kotlin).*") ? "Приложение" : null;
        String o = ua.contains("Android") ? "Android" : ua.contains("iPhone") ? "iPhone" : ua.contains("iPad") ? "iPad" : ua.contains("Windows") ? "Windows"
                : ua.matches("(?s).*(Mac OS X|Macintosh).*") ? "macOS" : ua.contains("Linux") ? "Linux" : null;
        boolean mobile = ua.matches("(?is).*(android|iphone|ipad|mobile|okhttp|dalvik).*");
        return new String[]{(b == null ? "Браузер" : b) + (o == null ? "" : " · " + o), mobile ? "smartphone" : "computer"};
    }
    private String devSub(JSONObject d) {
        String ip = d.isNull("ip") ? "" : d.optString("ip");
        String st = d.optBoolean("is_current") ? "в сети" : Site.seenText(Ui.instant(d.isNull("refreshed_at") ? d.optString("created_at") : d.optString("refreshed_at")), System.currentTimeMillis());
        return (ip.isEmpty() ? "" : ip + " · ") + st;
    }
    private void devices() {
        io.execute(() -> {
            try {
                JSONArray d = new JSONArray(Api.get(this).rpc("list_devices", new JSONObject()));
                runOnUiThread(() -> showDevices(d));
            } catch (Exception e) { Ui.toast(this, "Не удалось загрузить устройства: " + Ui.err(e)); }
        });
    }
    private void showDevices(JSONArray d) {
        Site.setValue(rowDevices, String.valueOf(d.length()));
        LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setPadding(0, 0, 0, Ui.dp(this, 8));
        final androidx.appcompat.app.AlertDialog[] dlg = new androidx.appcompat.app.AlertDialog[1];
        java.util.List<JSONObject> others = new java.util.ArrayList<>();
        JSONObject cur = null;
        for (int i = 0; i < d.length(); i++) { JSONObject o = d.optJSONObject(i); if (o == null) continue; if (o.optBoolean("is_current")) cur = o; else others.add(o); }
        others.sort((x, y) -> Long.compare(Ui.instant(y.optString("refreshed_at", y.optString("created_at"))), Ui.instant(x.optString("refreshed_at", x.optString("created_at")))));
        LinearLayout g = Site.group(this, c, "Это устройство");
        if (cur != null) { String[] u = uaName(cur.optString("user_agent")); final JSONObject cc = cur; Site.row(this, g, u[1], u[0], devSub(cur), null, v -> session(cc, dlg)); }
        else Site.row(this, g, "devices", "Текущий сеанс не найден", null, null, null);
        if (!others.isEmpty()) {
            LinearLayout all = Site.row(this, g, "logout", "Завершить все другие сеансы", null, null, v -> confirm("Завершить все другие сеансы (" + others.size() + ")? Их ключи будут удалены. Восстановить их будет нельзя.", () -> {
                int fail = 0;
                for (JSONObject o : others) { try { Api.get(this).rpc("revoke_device", new JSONObject().put("p_session_id", o.optString("id"))); } catch (Exception e) { fail++; } }
                Ui.toast(this, fail > 0 ? "Не удалось завершить " + fail + " из " + others.size() : "Сеансы завершены");
                runOnUiThread(() -> { if (dlg[0] != null) dlg[0].dismiss(); }); loadDevCount();
            }));
            danger(all);
            LinearLayout g2 = Site.group(this, c, "Активные сеансы");
            for (JSONObject o : others) { String[] u = uaName(o.optString("user_agent")); Site.row(this, g2, u[1], u[0], devSub(o), null, v -> session(o, dlg)); }
        }
        android.widget.ScrollView sv = new android.widget.ScrollView(this); sv.addView(c);
        dlg[0] = new MaterialAlertDialogBuilder(this).setTitle("Устройства").setView(sv).setPositiveButton("Закрыть", null).show();
    }
    private void danger(LinearLayout row) {
        for (int i = 0; i < row.getChildCount(); i++) { View v = row.getChildAt(i); if (v instanceof TextView) ((TextView) v).setTextColor(Site.danger(this)); }
        TextView l = row.findViewWithTag("label"); if (l != null) l.setTextColor(Site.danger(this));
    }
    private void session(JSONObject d, androidx.appcompat.app.AlertDialog[] parent) {
        String[] u = uaName(d.optString("user_agent"));
        String created = d.optString("created_at");
        String msg = "IP: " + (d.isNull("ip") ? "—" : d.optString("ip")) + "\nПоследняя активность: " + (d.optBoolean("is_current") ? "в сети" : Site.seenText(Ui.instant(d.optString("refreshed_at", created)), System.currentTimeMillis()))
                + "\nВход выполнен: " + (created.isEmpty() ? "—" : new java.text.SimpleDateFormat("d MMM yyyy, HH:mm", new java.util.Locale("ru")).format(new java.util.Date(Ui.instant(created))))
                + "\nБраузер: " + (d.isNull("user_agent") || d.optString("user_agent").isEmpty() ? "—" : d.optString("user_agent"));
        new MaterialAlertDialogBuilder(this).setTitle(u[0]).setMessage(msg).setNegativeButton("Закрыть", null)
                .setPositiveButton("Завершить сеанс", (x, w) -> confirm("Завершить сеанс на этом устройстве? Его ключи будут удалены. Восстановить их будет нельзя.", () -> {
                    try {
                        Api.get(this).rpc("revoke_device", new JSONObject().put("p_session_id", d.optString("id")));
                        runOnUiThread(() -> { if (parent[0] != null) parent[0].dismiss(); if (d.optBoolean("is_current")) ChatsActivity.logout(this); });
                        Ui.toast(this, "Сеанс завершён"); loadDevCount();
                    } catch (Exception e) { Ui.toast(this, "Не удалось завершить сеанс: " + Ui.err(e)); }
                })).show();
    }
    private void confirm(String text, Runnable bg) {
        androidx.appcompat.app.AlertDialog a = new MaterialAlertDialogBuilder(this).setMessage(text).setNegativeButton("Отмена", null)
                .setPositiveButton("Завершить", (x, w) -> io.execute(bg)).show();
        a.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setTextColor(Site.danger(this));
    }

    // ---------- Шифрование: «Это устройство» ----------
    private void thisDevice() {
        io.execute(() -> {
            String id = null, fp;
            try { id = E2e.get(this).deviceId(); } catch (Exception ignored) { }
            if (id == null) fp = "—";
            else try {
                JSONArray r = Api.get(this).select("devices_keys?select=identity_pub&device_id=eq." + id);
                fp = r.length() == 0 ? "ключи не найдены на сервере" : fingerprint(r.getJSONObject(0).optString("identity_pub"));
            } catch (Exception e) { fp = "не удалось загрузить"; }
            final String m = "ID устройства: " + (id == null ? "ключи шифрования ещё не созданы" : id) + "\n\nОтпечаток ключа:\n" + fp
                    + "\n\nШифрование: Signal (сквозное): сообщения и файлы шифруются на устройстве." + (E2e.lastError == null ? "" : "\n\n⚠ " + E2e.lastError);
            runOnUiThread(() -> new MaterialAlertDialogBuilder(this).setTitle("Это устройство").setMessage(m).setPositiveButton("OK", null).show());
        });
    }
    static String fingerprint(String pub) throws Exception {
        if (pub == null || pub.isEmpty()) return "—";
        byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(pub.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) { if (i > 0 && i % 2 == 0) sb.append(' '); sb.append(String.format("%02x", d[i] & 0xff)); }
        return sb.toString();
    }

    // ---------- Резервная копия (key_backups) ----------
    private void loadBackup() {
        try {
            JSONArray r = Api.get(this).select("key_backups?select=updated_at,size_bytes&user_id=eq." + Api.get(this).uid());
            String v = r.length() == 0 ? "Выкл." : new java.text.SimpleDateFormat("d MMM yyyy", new java.util.Locale("ru")).format(new java.util.Date(Ui.instant(r.getJSONObject(0).optString("updated_at"))));
            getSharedPreferences("ui", 0).edit().putString("backup_val", v).putString("backup_sub", r.length() == 0 ? "" : Ui.size(r.getJSONObject(0).optLong("size_bytes"))).apply();
            runOnUiThread(() -> Site.setValue(rowBackup, v));
        } catch (Exception ignored) { }
    }
    private void backup() {
        String v = getSharedPreferences("ui", 0).getString("backup_val", "Выкл.");
        String sz = getSharedPreferences("ui", 0).getString("backup_sub", "");
        new MaterialAlertDialogBuilder(this).setTitle("Резервная копия чатов")
                .setMessage(("Выкл.".equals(v) ? "Резервная копия не создана." : "Последняя копия: " + v + (sz.isEmpty() ? "" : " · " + sz))
                        + "\n\nКопия шифруется паролем на устройстве (Argon2), сервер её не может прочитать. Создать или восстановить копию пока можно на сайте: darkalmas.github.io/messenger-web → Настройки → Резервная копия.")
                .setPositiveButton("Открыть сайт", (d, w) -> { try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://darkalmas.github.io/messenger-web/"))); } catch (Exception ignored) { } })
                .setNegativeButton("Закрыть", null).show();
    }

    // ---------- Хранилище ----------
    private static long du(java.io.File f) { if (f == null || !f.exists()) return 0; if (f.isFile()) return f.length(); long n = 0; java.io.File[] l = f.listFiles(); if (l != null) for (java.io.File x : l) n += du(x); return n; }
    private void paintStorage() {
        if (rowStorage == null) return;
        io.execute(() -> { long n = du(getCacheDir()) + du(getFilesDir()); runOnUiThread(() -> Site.setValue(rowStorage, Ui.size(n))); });
    }
    private void storage() {
        io.execute(() -> {
            long all = du(getCacheDir()) + du(getFilesDir()), cache = du(getCacheDir()), files = du(new java.io.File(getCacheDir(), "chat-files"));
            String m = "Всего занято приложением: " + Ui.size(all) + "\nКэш: " + Ui.size(cache) + "\nРасшифрованные файлы в кэше: " + Ui.size(files)
                    + "\nПрофилей в памяти: " + Avatars.profiles.size()
                    + "\n\nКлючи шифрования и расшифрованная история хранятся отдельно и при очистке не удаляются.";
            runOnUiThread(() -> new MaterialAlertDialogBuilder(this).setTitle("Хранилище").setMessage(m).setNegativeButton("Закрыть", null)
                    .setPositiveButton("Очистить кэш", (d, w) -> io.execute(() -> {
                        java.io.File[] l = getCacheDir().listFiles();
                        if (l != null) for (java.io.File f : l) if (!f.getName().equals("updates")) rm(f);
                        Ui.toast(this, "Кэш очищен"); paintStorage();
                    })).show());
        });
    }
    private static void rm(java.io.File f) { java.io.File[] l = f.listFiles(); if (l != null) for (java.io.File x : l) rm(x); f.delete(); }
}
