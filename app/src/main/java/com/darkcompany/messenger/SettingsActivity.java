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
        body = Site.scaffold(this, Site.pageTitle(this, "Настройки", null, null, null), 2);
        build();
    }

    @Override protected void onResume() {
        super.onResume(); if (rowSound != null) Site.setValue(rowSound, Notify.soundLabel(this));
        io.execute(() -> {
            boolean ch = Avatars.refresh(this, Collections.singleton(Api.get(this).uid()), 0);
            if (ch) runOnUiThread(this::build);
            loadSeen(); loadDevCount(); loadBackup();
        });
        paintStorage();
    }

    private View rowNotif, rowSound;
    private void build() {
        body.removeAllViews();
        String uid = Api.get(this).uid();
        JSONObject p = Avatars.merged(this, uid);
        String un = p.optString("username", "");
        String dn = p.isNull("display_name") || p.optString("display_name").isEmpty() ? "@" + un : p.optString("display_name");
        // button.mini-head: высота 80, padding 12 16, аватар 56, имя 17px/500, ник 14px/500 серым
        LinearLayout mh = new LinearLayout(this); mh.setGravity(Gravity.CENTER_VERTICAL);
        mh.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 12));
        mh.addView(Avatars.view(this, uid, dn, 56), new LinearLayout.LayoutParams(Ui.dp(this, 56), Ui.dp(this, 56)));
        LinearLayout tc = new LinearLayout(this); tc.setOrientation(LinearLayout.VERTICAL); tc.setPadding(Ui.dp(this, 16), 0, 0, 0);
        tc.addView(Site.label(this, dn, 17f, Site.text(this), 500));
        tc.addView(Site.label(this, un.isEmpty() ? "" : "@" + un, 14f, Site.text2(this), 500));
        mh.addView(tc, new LinearLayout.LayoutParams(0, -2, 1f));
        mh.setOnClickListener(v -> openProfile());
        LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-1, Ui.dp(this, 80)); ml.topMargin = Ui.dp(this, 8); ml.bottomMargin = Ui.dp(this, 12);
        body.addView(mh, ml);

        LinearLayout g = Site.group(this, body, "Аккаунт");
        Site.row(this, g, "person", "Профиль", null, null, v -> openProfile());
        rowSeen = Site.row(this, g, "lock", "Конфиденциальность", null, SEEN_L[seenIdx()], v -> toggle(seenBox));
        seenBox = new LinearLayout(this); seenBox.setOrientation(LinearLayout.VERTICAL); seenBox.setVisibility(View.GONE);
        g.addView(seenBox);
        paintSeenBox();
        rowDevices = Site.row(this, g, "devices", "Устройства", null, getSharedPreferences("ui", 0).getString("dev_count", ""), v -> devices());
        Site.row(this, g, "key", "Это устройство", null, "Приложение · Android", v -> thisDevice());

        g = Site.group(this, body, "Оформление");
        rowTheme = Site.row(this, g, "palette", "Тема", null, THEME_L[Avatars.themeIndex(this)], v -> toggle(themeBox));
        themeBox = new LinearLayout(this); themeBox.setOrientation(LinearLayout.VERTICAL); themeBox.setVisibility(View.GONE);
        g.addView(themeBox);
        paintThemeBox();

        // «Уведомления»: три строки, как на скриншоте Димы — переключатель, «Звук» (системный выбор мелодии канала), «Показывать текст»
        { // заголовок раздела — над карточкой (скриншот Димы): 14px/500 primary, отступ слева 24
            android.widget.TextView nt = Site.lh(Site.label(this, "Уведомления", 14f, Site.primary(this), 500), 18.9f);
            nt.setPadding(Ui.dp(this, 24), Ui.dp(this, 4), Ui.dp(this, 16), Ui.dp(this, 8));
            body.addView(nt, new LinearLayout.LayoutParams(-1, -2));
        }
        g = Site.group(this, body, null);
        LinearLayout nr = Site.row(this, g, "notifications", "Уведомления", null, null, null);
        rowNotif = nr;
        android.widget.CompoundButton sw = Site.toggle(this, Notify.enabled(this) && Notify.allowed(this));
        sw.setOnCheckedChangeListener((bt, on) -> {
            Notify.setEnabled(this, on);
            if (on && !Notify.allowed(this)) requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 7);
            bt.invalidate();
            android.widget.Toast.makeText(this, on ? "Уведомления включены" : "Уведомления выключены", android.widget.Toast.LENGTH_LONG).show();
        });
        LinearLayout.LayoutParams swl = new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 32)); swl.leftMargin = Ui.dp(this, 16);
        nr.addView(sw, swl);
        nr.setOnClickListener(v -> sw.toggle());
        rowSound = Site.row(this, g, "volume_up", "Звук", null, Notify.soundLabel(this), v -> {
            try { startActivity(Notify.soundSettings(this)); } catch (Exception e) { android.widget.Toast.makeText(this, "Не удалось открыть настройки звука", android.widget.Toast.LENGTH_LONG).show(); } });
        LinearLayout tr = Site.row(this, g, "visibility_off", "Показывать текст", null, null, null);
        android.widget.CompoundButton tsw = Site.toggle(this, Notify.showText(this));
        tsw.setOnCheckedChangeListener((bt, on) -> { Notify.setShowText(this, on); bt.invalidate(); });
        LinearLayout.LayoutParams tsl = new LinearLayout.LayoutParams(Ui.dp(this, 52), Ui.dp(this, 32)); tsl.leftMargin = Ui.dp(this, 16);
        tr.addView(tsw, tsl);
        tr.setOnClickListener(v -> tsw.toggle());
        tr.setContentDescription("Показывать текст сообщений в уведомлениях");

        g = Site.group(this, body, "Данные");
        String mail = Api.get(this).email();
        Site.row(this, g, "mail", "Почта", null, mail.isEmpty() ? "—" : mail, v -> Site.sheet(this, "Почта", Site.kv(this, "Почта аккаунта", mail.isEmpty() ? "—" : mail),
                Site.hint(this, "Почта используется для входа. Её видите только вы.")).show());
        rowStorage = Site.row(this, g, "storage", "Хранилище", null, "—", v -> storage());
        rowBackup = Site.row(this, g, "backup", "Резервная копия", backupLine(), null, v -> backup());
        Site.row(this, g, "language", "Язык", null, "Русский", v -> {
            android.widget.RadioButton rb = new android.widget.RadioButton(this); rb.setText("Русский"); rb.setChecked(true); rb.setTextSize(16); rb.setTextColor(Site.text(this));
            Site.sheet(this, "Язык", rb, Site.hint(this, "Другие языки пока недоступны.")).show();
        });

        g = Site.group(this, body, "Прочее");
        Site.row(this, g, "help", "Помощь", null, "о шифровании", v -> Ui.toast(this,
                "Сообщения и файлы шифруются на устройстве (Signal). Сервер их не видит. Профиль виден всем и не шифруется."));
        Site.row(this, g, "group_add", "Новая группа", null, null, v -> toChats("new_group"));
        Site.row(this, g, "qr_code_scanner", "Войти на сайте (QR)", null, null, v -> toChats("qr"));
        Site.row(this, g, "system_update", "Проверить обновления", null, null, v -> Updater.check(this, true));
        Site.row(this, g, "logout", "Выйти", null, null, v -> confirmRed("Выйти из аккаунта? Ключи шифрования этого устройства будут удалены. После входа телефон станет новым устройством.", "Выйти",
                () -> runOnUiThread(() -> ChatsActivity.logout(this))));

        String ver = "?";
        try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
        TextView bi = Site.label(this, "Версия " + ver + " · сборка " + BuildConfig.BUILD_TIME, 12f, Site.text2(this), 400);
        bi.setGravity(Gravity.CENTER); bi.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8));
        body.addView(bi, new LinearLayout.LayoutParams(-1, -2));
        paintStorage();
    }
    private static final String[] THEME_L = {"Тёмная", "Светлая", "Системная"};
    private String notifSub(boolean on) {
        if (!on) return "выключены";
        boolean granted = android.os.Build.VERSION.SDK_INT < 33 || checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED;
        return granted ? "всплывающие и звук" : "звук и счётчик (всплывающие запрещены в настройках)";
    }
    /** askConfirm сайта: текст, «Отмена» и красная кнопка действия. */
    private void confirmRed(String text, String ok, Runnable bg) {
        androidx.appcompat.app.AlertDialog a = new MaterialAlertDialogBuilder(this).setMessage(text).setNegativeButton("Отмена", null)
                .setPositiveButton(ok, (x, w) -> io.execute(bg)).show();
        a.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setTextColor(Site.danger(this));
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
                android.widget.Toast.makeText(this, "Сохранено", android.widget.Toast.LENGTH_LONG).show();
            } catch (Exception e) { android.widget.Toast.makeText(this, "Не сохранено: " + Ui.err(e), android.widget.Toast.LENGTH_LONG).show(); }
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
            } catch (Exception e) { android.widget.Toast.makeText(this, "Не удалось загрузить устройства: " + Ui.err(e), android.widget.Toast.LENGTH_LONG).show(); }
        });
    }
    private void showDevices(JSONArray d) {
        Site.setValue(rowDevices, String.valueOf(d.length()));
        LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setPadding(0, 0, 0, Ui.dp(this, 8));
        final android.app.Dialog[] dlg = new android.app.Dialog[1];
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
                android.widget.Toast.makeText(this, fail > 0 ? "Не удалось завершить " + fail + " из " + others.size() : "Сеансы завершены", android.widget.Toast.LENGTH_LONG).show();
                runOnUiThread(() -> { if (dlg[0] != null) dlg[0].dismiss(); }); loadDevCount();
            }));
            danger(all);
            LinearLayout g2 = Site.group(this, c, "Активные сеансы");
            for (JSONObject o : others) { String[] u = uaName(o.optString("user_agent")); Site.row(this, g2, u[1], u[0], devSub(o), null, v -> session(o, dlg)); }
        }
        dlg[0] = Site.sheet(this, "Устройства", c); dlg[0].show();
    }
    private void danger(LinearLayout row) {
        for (int i = 0; i < row.getChildCount(); i++) { View v = row.getChildAt(i); if (v instanceof TextView) ((TextView) v).setTextColor(Site.danger(this)); }
        TextView l = row.findViewWithTag("label"); if (l != null) l.setTextColor(Site.danger(this));
    }
    private void session(JSONObject d, android.app.Dialog[] parent) {
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
                        android.widget.Toast.makeText(this, "Сеанс завершён", android.widget.Toast.LENGTH_LONG).show(); loadDevCount();
                    } catch (Exception e) { android.widget.Toast.makeText(this, "Не удалось завершить сеанс: " + Ui.err(e), android.widget.Toast.LENGTH_LONG).show(); }
                })).show();
    }
    private void confirm(String text, Runnable bg) { confirmRed(text, "Завершить", bg); }

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
            final String fid = id == null ? "ключи шифрования ещё не созданы" : id, ffp = fp;
            runOnUiThread(() -> Site.sheet(this, "Это устройство", Site.kv(this, "ID устройства", fid), Site.kv(this, "Отпечаток ключа", ffp),
                    Site.kv(this, "Шифрование", "Signal (сквозное): сообщения и файлы шифруются на устройстве." + (E2e.lastError == null ? "" : "\n⚠ " + E2e.lastError))).show());
        });
    }
    static String fingerprint(String pub) throws Exception {
        if (pub == null || pub.isEmpty()) return "—";
        byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(pub.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) { if (i > 0 && i % 2 == 0) sb.append(' '); sb.append(String.format("%02x", d[i] & 0xff)); }
        return sb.toString();
    }

    // ---------- Резервная копия (v15: файл на телефоне, формат docs/backup_format.md / web/backup.js) ----------
    private static final int RQ_SAVE = 7101, RQ_OPEN = 7102;
    private byte[] pendingFile; private com.google.android.material.bottomsheet.BottomSheetDialog bkSheet;
    private String backupLine() {
        android.content.SharedPreferences sp = getSharedPreferences("ui", 0);
        long t = sp.getLong("bk_time", 0);
        if (t == 0) return "Копий ещё нет";
        return "Последняя копия: " + new java.text.SimpleDateFormat("dd.MM, HH:mm", new java.util.Locale("ru")).format(new java.util.Date(t)) + " · " + Ui.size(sp.getLong("bk_size", 0));
    }
    private void loadBackup() { runOnUiThread(() -> { if (rowBackup != null) Site.setSub(rowBackup, backupLine()); }); }
    private void backup() {
        TextView top = Site.label(this, backupLine(), 15f, Site.text(this), 400);
        TextView create = Site.button(this, "Создать копию");
        TextView restore = Site.label(this, "Восстановить из файла", 16f, Site.primary(this), 500);
        restore.setGravity(Gravity.CENTER); restore.setMinHeight(Ui.dp(this, 46));
        android.graphics.drawable.GradientDrawable ob = Site.round(0, Ui.dp(this, 26)); ob.setStroke(Math.max(1, Ui.dp(this, 1)), Site.primary(this)); restore.setBackground(ob);
        TextView cap = Site.label(this, "Копия зашифрована паролем. Без пароля восстановить её нельзя", 13f, Site.text2(this), 400);
        bkSheet = Site.sheet(this, "Резервная копия", top, create, restore, cap);
        create.setOnClickListener(v -> { bkSheet.dismiss(); createDialog(); });
        restore.setOnClickListener(v -> { bkSheet.dismiss();
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
            try { startActivityForResult(i, RQ_OPEN); } catch (Exception e) { android.widget.Toast.makeText(this, "Не удалось открыть выбор файла", android.widget.Toast.LENGTH_LONG).show(); } });
        bkSheet.show();
    }
    private android.widget.EditText passField(String hint) {
        android.widget.EditText e = Site.input(this, hint);
        e.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        return e;
    }
    private static final String[] STR_L = {"Слишком короткий (минимум 12 символов)", "Слабый", "Средний", "Хороший", "Надёжный"};
    private static final int[] STR_C = {0xFFE53935, 0xFFE53935, 0xFFFB8C00, 0xFF43A047, 0xFF2E7D32};
    private void createDialog() {
        android.widget.EditText p1 = passField("Пароль копии"), p2 = passField("Повторите пароль");
        android.widget.ProgressBar bar = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); bar.setMax(4);
        TextView sl = Site.label(this, STR_L[0], 13f, Site.text2(this), 400);
        TextView st = Site.label(this, "", 13f, Site.danger(this), 400);
        TextView go = Site.button(this, "Сохранить в…");
        android.text.TextWatcher w = new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            public void onTextChanged(CharSequence s, int a, int b, int c) { }
            public void afterTextChanged(android.text.Editable s) {
                int k = BackupFile.strength(p1.getText().toString()); bar.setProgress(k == 0 && p1.length() > 0 ? 1 : k);
                bar.setProgressTintList(android.content.res.ColorStateList.valueOf(STR_C[k])); sl.setText(STR_L[k]); st.setText("");
            }
        };
        p1.addTextChangedListener(w); p2.addTextChangedListener(w);
        com.google.android.material.bottomsheet.BottomSheetDialog d = Site.sheet(this, "Создать копию", Site.caption(this, "Пароль"), p1, bar, sl, p2, st, go,
                Site.label(this, "Копия зашифрована паролем. Без пароля восстановить её нельзя", 13f, Site.text2(this), 400));
        go.setOnClickListener(v -> {
            String a = p1.getText().toString(), b = p2.getText().toString();
            if (a.length() < BackupFile.MIN_PASS) { st.setText("Минимум " + BackupFile.MIN_PASS + " символов"); return; }
            if (!a.equals(b)) { st.setText("Пароли не совпадают"); return; }
            char[] pass = a.toCharArray(); p1.setText(""); p2.setText("");
            go.setEnabled(false); go.setText("Создаю копию…");
            io.execute(() -> {
                try {
                    JSONArray msgs = collectMessages(n -> runOnUiThread(() -> go.setText("Сообщений: " + n)));
                    String now = java.time.Instant.now().toString();
                    byte[] file = BackupFile.export(msgs, pass, Api.get(this).uid(), now);
                    runOnUiThread(() -> {
                        d.dismiss(); pendingFile = file;
                        String name = "messenger-backup-" + new java.text.SimpleDateFormat("yyyy-MM-dd-HHmm", java.util.Locale.US).format(new java.util.Date()) + ".msgrbak";
                        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, name);
                        try { startActivityForResult(i, RQ_SAVE); } catch (Exception e) { pendingFile = null; android.widget.Toast.makeText(this, "Не удалось открыть «Сохранить в…»", android.widget.Toast.LENGTH_LONG).show(); }
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> { go.setEnabled(true); go.setText("Сохранить в…"); st.setText("Не удалось создать копию: " + e.getMessage()); });
                } finally { java.util.Arrays.fill(pass, '\0'); }
            });
        });
        d.show();
    }
    /** collectMessages сайта: все сообщения моих чатов, у которых есть расшифрованный текст на этом устройстве. */
    private JSONArray collectMessages(java.util.function.IntConsumer progress) throws Exception {
        Api api = Api.get(this); E2eCore core = E2e.get(this);
        JSONArray out = new JSONArray(); String before = null;
        for (;;) {
            JSONArray page = api.select("messages?select=id,chat_id,sender_id,created_at&order=created_at.desc&limit=1000" + (before == null ? "" : "&created_at=lt." + android.net.Uri.encode(before)));
            for (int i = 0; i < page.length(); i++) {
                JSONObject m = page.getJSONObject(i);
                String pts = core.peekOwn(m.optString("id"));
                if (pts != null) out.put(BackupFile.toEntry(m, pts));
            }
            progress.accept(out.length());
            if (page.length() < 1000) break;
            before = page.getJSONObject(page.length() - 1).optString("created_at");
        }
        return out;
    }
    @Override protected void onActivityResult(int rq, int rc, Intent data) {
        super.onActivityResult(rq, rc, data);
        if (rq == RQ_SAVE) {
            byte[] f = pendingFile; pendingFile = null;
            if (rc != RESULT_OK || data == null || data.getData() == null || f == null) return;
            android.net.Uri u = data.getData();
            io.execute(() -> {
                try (java.io.OutputStream os = getContentResolver().openOutputStream(u, "wt")) {
                    os.write(f);
                    getSharedPreferences("ui", 0).edit().putLong("bk_time", System.currentTimeMillis()).putLong("bk_size", f.length).apply();
                    runOnUiThread(() -> { android.widget.Toast.makeText(this, "Копия сохранена", android.widget.Toast.LENGTH_LONG).show(); loadBackup(); });
                } catch (Exception e) { runOnUiThread(() -> android.widget.Toast.makeText(this, "Не удалось сохранить: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show()); }
            });
        } else if (rq == RQ_OPEN && rc == RESULT_OK && data != null && data.getData() != null) {
            android.net.Uri u = data.getData();
            io.execute(() -> {
                try (java.io.InputStream is = getContentResolver().openInputStream(u)) {
                    java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(); byte[] buf = new byte[65536]; int n; long tot = 0;
                    while ((n = is.read(buf)) > 0) { tot += n; if (tot > 200L * 1024 * 1024) throw new Exception("Файл слишком большой"); bo.write(buf, 0, n); }
                    byte[] file = bo.toByteArray();
                    runOnUiThread(() -> restoreDialog(file));
                } catch (Exception e) { runOnUiThread(() -> android.widget.Toast.makeText(this, "Не удалось прочитать файл: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show()); }
            });
        }
    }
    private void restoreDialog(byte[] file) {
        android.widget.EditText p1 = passField("Пароль копии");
        TextView st = Site.label(this, "", 13f, Site.danger(this), 400);
        TextView go = Site.button(this, "Восстановить");
        com.google.android.material.bottomsheet.BottomSheetDialog d = Site.sheet(this, "Восстановить из файла", Site.caption(this, "Пароль"), p1, st, go);
        go.setOnClickListener(v -> {
            char[] pass = p1.getText().toString().toCharArray();
            go.setEnabled(false); go.setText("Проверяю…"); st.setText("");
            io.execute(() -> {
                try {
                    JSONObject o = BackupFile.read(file, pass, Api.get(this).uid()); // проверка целостности до любой записи
                    JSONArray ms = o.getJSONArray("messages"); java.util.List<String[]> items = new java.util.ArrayList<>();
                    for (int i = 0; i < ms.length(); i++) { String[] x = BackupFile.toCache(ms.optJSONObject(i)); if (x != null) items.add(x); }
                    int n = E2e.get(this).importPlain(items);
                    runOnUiThread(() -> { d.dismiss(); android.widget.Toast.makeText(this, "Восстановлено сообщений: " + n, android.widget.Toast.LENGTH_LONG).show(); });
                } catch (BackupFile.BadPass e) {
                    runOnUiThread(() -> { go.setEnabled(true); go.setText("Восстановить"); st.setText("Неверный пароль"); });
                } catch (Exception e) {
                    runOnUiThread(() -> { go.setEnabled(true); go.setText("Восстановить"); st.setText(e.getMessage()); });
                } finally { java.util.Arrays.fill(pass, '\0'); }
            });
        });
        d.show();
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
            runOnUiThread(() -> {
                TextView btn = Site.label(this, "Очистить кэш", 15f, Site.primary(this), 500);
                btn.setGravity(Gravity.CENTER); btn.setMinHeight(Ui.dp(this, 46));
                android.graphics.drawable.GradientDrawable gb = Site.round(0, Ui.dp(this, 26)); gb.setStroke(Ui.dp(this, 1), Site.divider(this)); btn.setBackground(gb);
                android.app.Dialog[] dd = new android.app.Dialog[1];
                btn.setOnClickListener(x -> io.execute(() -> {
                    java.io.File[] l = getCacheDir().listFiles();
                    if (l != null) for (java.io.File f : l) if (!f.getName().equals("updates")) rm(f);
                    runOnUiThread(() -> { dd[0].dismiss(); android.widget.Toast.makeText(this, "Кэш очищен", android.widget.Toast.LENGTH_LONG).show(); paintStorage(); });
                }));
                dd[0] = Site.sheet(this, "Хранилище", Site.kv(this, "Всего занято приложением", Ui.size(all)), Site.kv(this, "Кэш файлов", Ui.size(cache)),
                        Site.kv(this, "Расшифрованные файлы / профили в памяти", Ui.size(files) + " / " + Avatars.profiles.size()),
                        Site.hint(this, "Ключи шифрования и расшифрованная история хранятся отдельно и при очистке не удаляются."), btn);
                dd[0].show();
            });
        });
    }
    private static void rm(java.io.File f) { java.io.File[] l = f.listFiles(); if (l != null) for (java.io.File x : l) rm(x); f.delete(); }
}
