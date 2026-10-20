package com.darkcompany.messenger;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.LinearLayout;

import androidx.activity.result.ActivityResultLauncher;

import androidx.appcompat.app.AppCompatActivity;

import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

public class ChatsActivity extends AppCompatActivity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    /** Проверка ключей — в отдельном потоке: медленная генерация/сеть не должна задерживать загрузку списка чатов. */
    private final ExecutorService keysIo = Executors.newSingleThreadExecutor();
    private volatile boolean rowErrShown = false;
    private final List<String> ids = new ArrayList<>();
    private final List<String> titles = new ArrayList<>();
    private final List<JSONObject> rows = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private android.widget.TextView empty;
    private static final Pattern TOKEN = Pattern.compile("^[0-9a-f]{64}$");

    private final ActivityResultLauncher<ScanOptions> scanner = registerForActivityResult(new ScanContract(), result -> {
        if (result.getContents() != null) handleQr(result.getContents());
    });

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!Api.get(this).hasSession()) { startActivity(new Intent(this, LoginActivity.class)); finish(); return; }
        io.execute(() -> ChatFiles.cleanup(this));
        keysIo.execute(() -> keysCheck(true)); // ключи E2E для этого устройства
        ListView list = new ListView(this);
        adapter = new ArrayAdapter<String>(this, 0, titles) {
            @Override public android.view.View getView(int pos, android.view.View cv, android.view.ViewGroup parent) {
                try {
                    return Ui.chatRow(ChatsActivity.this, rows.get(pos), Api.get(ChatsActivity.this).uid());
                } catch (Exception e) { // одна «плохая» строка не должна оставлять экран пустым
                    if (!rowErrShown) { rowErrShown = true; Ui.toast(ChatsActivity.this, "Ошибка отображения чата: " + e); }
                    android.widget.TextView t = Ui.text(ChatsActivity.this, pos < titles.size() ? titles.get(pos) : "Чат", 17);
                    t.setPadding(Ui.dp(ChatsActivity.this, 16), Ui.dp(ChatsActivity.this, 20), Ui.dp(ChatsActivity.this, 16), Ui.dp(ChatsActivity.this, 20));
                    return t;
                }
            }
        };
        list.setAdapter(adapter);
        list.setOnTouchListener((v, e) -> { if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) { Site.lastTouch[0] = e.getRawX(); Site.lastTouch[1] = e.getRawY(); } return false; });
        list.setOnItemLongClickListener((p, v, pos, id) -> { JSONObject row = rows.get(pos); if (row.optBoolean("_archive")) return false; chatMenu(v, row); return true; });
        list.setOnItemClickListener((p, v, pos, id) -> {
            if (rows.get(pos).optBoolean("_archive")) { showArchive = !showArchive; try { applyChats(lastArr); } catch (Exception ignored) { } loadChats(); return; }
            Intent i = new Intent(this, ChatActivity.class);
            i.putExtra("chat_id", ids.get(pos));
            i.putExtra("title", titles.get(pos));
            JSONObject row = rows.get(pos);
            i.putExtra("kind", row.optString("kind", ""));
            if (!row.isNull("peer_id")) i.putExtra("peer_id", row.optString("peer_id"));
            startActivity(i);
        });
        list.setDivider(null);
        list.setPadding(0, 0, 0, Ui.dp(this, 8));
        list.setClipToPadding(false);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Site.surface(this));
        // .bar: аватар и имя (→ профиль), справа меню
        LinearLayout top = Site.bar(this);
        String myId = Api.get(this).uid();
        JSONObject meP = Avatars.merged(this, myId);
        String myName = meP.isNull("display_name") || meP.optString("display_name").isEmpty() ? meP.optString("username", "") : meP.optString("display_name");
        LinearLayout meBtn = new LinearLayout(this); meBtn.setGravity(android.view.Gravity.CENTER_VERTICAL);
        meBtn.addView(Avatars.view(this, myId, myName, 42), new LinearLayout.LayoutParams(Ui.dp(this, 42), Ui.dp(this, 42)));
        meName = Site.label(this, myName, 15f, Site.text(this), 700); meName.setPadding(Ui.dp(this, 10), 0, 0, 0);
        meBtn.addView(meName);
        meBtn.setOnClickListener(v -> startActivity(new Intent(this, ProfileActivity.class)));
        meBtn.setPadding(Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4));
        top.addView(meBtn, new LinearLayout.LayoutParams(0, Ui.dp(this, 50), 1f));
        root.addView(top);
        // «Чаты ( ◌ Соединение… )»
        LinearLayout tr = new LinearLayout(this); tr.setGravity(android.view.Gravity.CENTER_VERTICAL);
        tr.setPadding(Ui.dp(this, 14), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 4));
        tr.addView(Site.label(this, "Чаты", 20f, Site.text(this), 600));
        connRow = new LinearLayout(this); connRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        connRow.addView(Site.label(this, " (", 15f, Site.text2(this), 400));
        connRow.addView(Site.spinner(this));
        connTxt = Site.label(this, "Соединение…", 15f, Site.text2(this), 400); connTxt.setPadding(Ui.dp(this, 2), 0, 0, 0);
        connRow.addView(connTxt);
        connRow.addView(Site.label(this, ")", 15f, Site.text2(this), 400));
        tr.addView(connRow);
        root.addView(tr);
        // поиск собеседника + «Написать»
        LinearLayout find = new LinearLayout(this); find.setGravity(android.view.Gravity.CENTER_VERTICAL);
        find.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        final android.widget.EditText findIn = Site.input(this, "Никнейм собеседника (@nick)");
        findIn.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_GO);
        find.addView(findIn, new LinearLayout.LayoutParams(0, Ui.dp(this, 46), 1f));
        android.widget.TextView go = Site.button(this, "Написать"); go.setTextSize(15);
        Runnable doFind = () -> { String n = findIn.getText().toString(); findIn.setText(""); createDirect(n); };
        go.setOnClickListener(v -> doFind.run());
        findIn.setOnEditorActionListener((v, id, e) -> { doFind.run(); return true; });
        LinearLayout.LayoutParams gl = new LinearLayout.LayoutParams(-2, Ui.dp(this, 46)); gl.leftMargin = Ui.dp(this, 8);
        find.addView(go, gl);
        root.addView(find);
        android.widget.FrameLayout frame = new android.widget.FrameLayout(this);
        frame.addView(list);
        empty = Ui.text(this, "Загрузка чатов…", 16);
        empty.setGravity(android.view.Gravity.CENTER);
        empty.setTextColor(Site.text2(this));
        empty.setPadding(Ui.dp(this, 32), 0, Ui.dp(this, 32), 0);
        frame.addView(empty, new android.widget.FrameLayout.LayoutParams(-1, -1));
        root.addView(frame, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(Site.bottomNav(this, 0), new LinearLayout.LayoutParams(-1, Ui.dp(this, 56)));
        setContentView(root);
        Ui.fitSystemBars(this, root); // edge-to-edge (Android 15+): не прятать контент под тулбаром/панелями
        // Офлайн-кэш: старые чаты видны сразу, даже без интернета
        JSONArray cached = Cache.chats(this);
        if (cached != null) { try { applyChats(cached); } catch (Exception ignored) { } }
        watchNetwork();
        paintTitle();
        if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 7);
        NotifyService.start(this);
        askBattery();
        listH.post(listPoll);
    }

    /** Один раз: просим не ограничивать работу в фоне, иначе система усыпит службу уведомлений. */
    private void askBattery() {
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        if (pm == null || pm.isIgnoringBatteryOptimizations(getPackageName()) || !Notify.enabled(this) || Notify.sp(this).getBoolean("asked_battery", false)) return;
        Notify.sp(this).edit().putBoolean("asked_battery", true).apply();
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("Уведомления в фоне")
                .setMessage("Чтобы сообщения приходили, когда приложение закрыто, разрешите ему работать в фоне без ограничений батареи.")
                .setNegativeButton("Не сейчас", null)
                .setPositiveButton("Разрешить", (d, w) -> { try { startActivity(new android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:" + getPackageName()))); } catch (Exception e) { Ui.toast(this, "Откройте настройки батареи вручную"); } })
                .show();
    }
    @Override public void onRequestPermissionsResult(int rc, String[] p, int[] r) { super.onRequestPermissionsResult(rc, p, r); if (rc == 7) NotifyService.start(this); }

    // ---- «Чаты (Соединение…)» / «Чаты (Ожидание сети…)» ----
    private volatile boolean online = true, connected = false;
    private android.net.ConnectivityManager.NetworkCallback netCb;
    private void watchNetwork() {
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return;
        online = cm.getActiveNetwork() != null;
        netCb = new android.net.ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(android.net.Network n) { online = true; runOnUiThread(() -> { paintTitle(); listH.removeCallbacks(listPoll); listH.post(listPoll); }); }
            @Override public void onLost(android.net.Network n) {
                android.net.ConnectivityManager c2 = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
                online = c2 != null && c2.getActiveNetwork() != null; if (!online) connected = false; runOnUiThread(ChatsActivity.this::paintTitle);
            }
        };
        try { cm.registerDefaultNetworkCallback(netCb); } catch (Exception ignored) { }
    }
    static String titleFor(boolean online, boolean connected) {
        return !online ? "Чаты (Ожидание сети…)" : !connected ? "Чаты (Соединение…)" : "Чаты";
    }
    private LinearLayout connRow; private android.widget.TextView connTxt, meName;
    private void paintTitle() {
        if (connRow == null) return;
        connRow.setVisibility(online && connected ? android.view.View.GONE : android.view.View.VISIBLE);
        connTxt.setText(!online ? "Ожидание сети…" : "Соединение…");
    }
    @Override protected void onDestroy() {
        super.onDestroy(); destroyed = true; listH.removeCallbacks(listPoll);
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm != null && netCb != null) try { cm.unregisterNetworkCallback(netCb); } catch (Exception ignored) { }
    }
    private volatile boolean destroyed = false, visible = false;
    /** last_at по чатам при прошлой проверке: новое входящее → mark_delivered + уведомление. */
    private final Map<String, String> lastSeenAt = new HashMap<>();
    private boolean firstLoad = true;

    private final android.os.Handler listH = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable listPoll = new Runnable() { @Override public void run() { if (adapter != null) loadChats(); keysIo.execute(() -> keysCheck(false)); if (!destroyed) listH.postDelayed(this, visible ? 5000 : 15000); } };
    private volatile String shownKeyErr = null;
    /** Регистрация ключей устройства: при старте, при возврате в приложение и раз в 10 минут; ошибка не глотается. */
    private void keysCheck(boolean force) {
        boolean ok = E2e.ensureFresh(this, force);
        String err = ok ? null : E2e.lastError;
        if (err != null && !err.equals(shownKeyErr)) Ui.toast(this, err + " Повторю автоматически.");
        shownKeyErr = err;
    }
    @Override
    protected void onNewIntent(Intent i) { super.onNewIntent(i); setIntent(i); }
    /** Действия из «Настроек» (на сайте нет меню ⋮): новая группа, вход на сайте по QR. */
    private void pendingAction() {
        String a = getIntent() == null ? null : getIntent().getStringExtra("action");
        if (a == null) return;
        getIntent().removeExtra("action");
        if (a.equals("new_group")) newGroup();
        else if (a.equals("qr")) onOptionsItemSelected2(2);
    }
    @Override
    protected void onResume() {
        super.onResume(); visible = true;
        pendingAction();
        Updater.resume(this);
        Updater.check(this, false);
        listH.removeCallbacks(listPoll); listH.post(listPoll); // новые чаты и сообщения появляются без перезахода
    }
    @Override
    protected void onPause() { super.onPause(); visible = false; } // опрос продолжается реже: доставка и уведомления, пока приложение свёрнуто

    @Override
    public boolean onCreateOptionsMenu(Menu m) { return false; }
    public boolean oldOptionsMenu(Menu m) {
        m.add(0, 1, 0, "Новый чат").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        m.add(0, 4, 1, "Новая группа").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        m.add(0, 2, 1, "Войти на сайте (QR)").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        m.add(0, 5, 0, "⚙").setTitleCondensed("Настройки").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        m.add(0, 6, 1, "Профиль").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        m.add(0, 7, 2, "Проверить обновления").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        m.add(0, 3, 2, "Выйти").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem it) { return onOptionsItemSelected2(it.getItemId()) || super.onOptionsItemSelected(it); }
    private boolean onOptionsItemSelected2(int itemId) {
        switch (itemId) {
            case 1: newChat(); return true;
            case 2:
                ScanOptions o = new ScanOptions();
                o.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
                o.setPrompt("Наведите на QR на странице входа");
                o.setBeepEnabled(false);
                o.setOrientationLocked(false);
                scanner.launch(o);
                return true;
            case 3:
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("Выйти из аккаунта?")
                        .setMessage("Ключи шифрования этого устройства будут удалены. Прочитанная история и ваши отправленные сообщения останутся на этом телефоне. "
                                + "После входа телефон станет новым устройством: собеседники увидят, что ваш ключ изменился, а новые сообщения в группах пойдут с новым ключом.")
                        .setPositiveButton("Выйти", (d, w) -> logout()).setNegativeButton("Отмена", null).show();
                return true;
            case 4: newGroup(); return true;
            case 5: startActivity(new Intent(this, SettingsActivity.class)); return true;
            case 7: Updater.check(this, true); return true;
            case 6: startActivity(new Intent(this, ProfileActivity.class)); return true;
        }
        return false;
    }

    private void logout() { logout(this); }

    /** Выход: удалить ключи устройства, отозвать сессию, очистить кэш файлов. */
    static void logout(android.app.Activity a) {
        E2e.get(a).wipe();
        Cache.wipe(a);
        NotifyService.stop(a); Notify.cancelAll(a); a.getSharedPreferences("notif_seen", 0).edit().clear().apply();
        Api.get(a).logout();
        new Thread(() -> {
            java.io.File[] fs = new java.io.File(a.getCacheDir(), "chat-files").listFiles();
            if (fs != null) for (java.io.File f : fs) f.delete();
        }).start();
        a.startActivity(new Intent(a, LoginActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        a.finish();
    }

    private void authFail(Exception e) {
        if (e instanceof Api.AuthException) {
            runOnUiThread(() -> logout());
        } else {
            Ui.toast(this, Ui.err(e));
        }
    }

    private void loadChats() {
        io.execute(() -> {
            try {
                if (!online) { connected = false; runOnUiThread(this::paintTitle); return; }
                String r = Api.get(this).rpc("list_chats", new JSONObject()).trim();
                JSONArray arr = r.isEmpty() ? new JSONArray() : new JSONArray(r);
                // настройки чатов (закреп, без звука, архив, роль) — как на сайте
                try {
                    JSONArray prefs = Api.get(this).select("chat_members?select=chat_id,pinned,muted_until,archived,role&user_id=eq." + Api.get(this).uid());
                    java.util.Map<String, JSONObject> pm = new java.util.HashMap<>();
                    for (int i = 0; i < prefs.length(); i++) pm.put(prefs.getJSONObject(i).optString("chat_id"), prefs.getJSONObject(i));
                    for (int i = 0; i < arr.length(); i++) { JSONObject o = arr.getJSONObject(i); JSONObject q = pm.get(o.optString("chat_id"));
                        if (q != null) for (String k : new String[]{"pinned", "muted_until", "archived", "role"}) o.put(k, q.opt(k)); }
                } catch (Exception ignored) { }
                Cache.putChats(this, arr.toString());
                if (!connected) { connected = true; runOnUiThread(this::paintTitle); }
                delivered(arr);
                // Аватары собеседников (профили кэшируются на минуту)
                java.util.Set<String> peers = new java.util.HashSet<>();
                for (int i = 0; i < arr.length(); i++) { JSONObject o = arr.getJSONObject(i); if (!o.isNull("peer_id")) peers.add(o.optString("peer_id")); }
                Avatars.refresh(this, peers, 60000);
                runOnUiThread(() -> { try { applyChats(arr); } catch (Exception ignored) { } });
            } catch (Exception e) {
                if (!(e instanceof Api.AuthException)) { connected = false; runOnUiThread(this::paintTitle); }
                if (!(e instanceof Api.AuthException)) {
                    final String m = "Не удалось загрузить чаты: " + Ui.err(e) + "\nПовторяю каждые 5 секунд.";
                    runOnUiThread(() -> { if (rows.isEmpty()) { empty.setText(m); empty.setVisibility(android.view.View.VISIBLE); } });
                }
                authFail(e);
            }
        });
    }

    /** «Написать»: найти по никнейму, создать (или открыть) личный чат и перейти в него. */
    private void createDirect(String raw) {
        final String nick = raw.trim().replaceFirst("^@+", "").trim().toLowerCase(java.util.Locale.ROOT);
        if (nick.isEmpty()) return;
        io.execute(() -> {
            try {
                Api api = Api.get(this);
                JSONArray r = api.select("profiles?select=id,username&username=eq." + java.net.URLEncoder.encode(nick, "UTF-8"));
                if (r.length() == 0) { Ui.toast(this, "Пользователь @" + nick + " не найден"); return; }
                String peer = r.getJSONObject(0).getString("id");
                String cid = api.rpc("create_direct_chat", new JSONObject().put("p_other", peer)).trim().replace("\"", "");
                loadChats();
                if (cid.matches("[0-9a-f-]{36}")) runOnUiThread(() -> startActivity(new Intent(this, ChatActivity.class).putExtra("chat_id", cid)
                        .putExtra("title", "@" + nick).putExtra("kind", "direct").putExtra("peer_id", peer)));
            } catch (Exception e) { authFail(e); }
        });
    }

    /** chatMenuItems сайта: закрепить, без звука (1 ч / 8 ч / 1 день / навсегда) или включить звук, архив. */
    private void chatMenu(android.view.View anchor, JSONObject c) {
        String id = c.optString("chat_id");
        java.util.List<Site.Item> it = new java.util.ArrayList<>();
        boolean pinned = c.optBoolean("pinned"), arch = c.optBoolean("archived");
        it.add(new Site.Item("push_pin", pinned ? "Открепить" : "Закрепить", false, () -> prefs(id, "p_pinned", !pinned, pinned ? "Чат откреплён" : "Чат закреплён")));
        long H = 3600000L;
        if (muted(c)) it.add(new Site.Item("notifications", "Включить звук", false, () -> prefs(id, "p_unmute", true, "Звук включён")));
        else {
            it.add(new Site.Item("notifications_off", "Без звука на 1 час", false, () -> prefs(id, "p_muted_until", iso(System.currentTimeMillis() + H), "Уведомления отключены")));
            it.add(new Site.Item("notifications_off", "Без звука на 8 часов", false, () -> prefs(id, "p_muted_until", iso(System.currentTimeMillis() + 8 * H), "Уведомления отключены")));
            it.add(new Site.Item("notifications_off", "Без звука на 1 день", false, () -> prefs(id, "p_muted_until", iso(System.currentTimeMillis() + 24 * H), "Уведомления отключены")));
            it.add(new Site.Item("notifications_off", "Без звука навсегда", false, () -> prefs(id, "p_muted_until", "9999-12-31T00:00:00Z", "Уведомления отключены")));
        }
        it.add(new Site.Item(arch ? "unarchive" : "archive", arch ? "Вернуть из архива" : "В архив", false, () -> prefs(id, "p_archived", !arch, arch ? "Чат возвращён из архива" : "Чат перенесён в архив")));
        Site.menu(anchor, Site.lastTouch[0], Site.lastTouch[1], it);
    }
    private static String iso(long ms) { java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.ROOT); f.setTimeZone(java.util.TimeZone.getTimeZone("UTC")); return f.format(new java.util.Date(ms)); }
    private void prefs(String chat, String k, Object v, String done) {
        io.execute(() -> {
            try { Api.get(this).rpc("set_chat_prefs", new JSONObject().put("p_chat", chat).put(k, v)); Ui.toast(this, done); loadChats(); }
            catch (Exception e) { Ui.toast(this, Ui.err(e)); }
        });
    }

    private void newChat() {
        final android.widget.EditText in = Ui.input(this, "Никнейм собеседника (можно с @)");
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("Новый личный чат").setView(in)
                .setPositiveButton("Создать", (d, w) -> {
                    final String nick = in.getText().toString().trim().replaceFirst("^@+", "").trim().toLowerCase(java.util.Locale.ROOT);
                    if (nick.isEmpty()) return;
                    io.execute(() -> {
                        try {
                            Api api = Api.get(this);
                            JSONArray r = api.select("profiles?select=id&username=eq." + java.net.URLEncoder.encode(nick, "UTF-8"));
                            if (r.length() == 0) { Ui.toast(this, "Пользователь не найден"); return; }
                            api.rpc("create_direct_chat", new JSONObject().put("p_other", r.getJSONObject(0).getString("id")));
                            loadChats();
                        } catch (Exception e) {
                            authFail(e);
                        }
                    });
                }).setNegativeButton("Отмена", null).show();
    }

    /** Группа (сквозное шифрование Sender Keys): название + никнеймы через запятую. */
    private void newGroup() {
        final android.widget.LinearLayout col = Ui.column(this);
        final android.widget.EditText title = Ui.input(this, "Название группы");
        final android.widget.EditText nicks = Ui.input(this, "Никнеймы участников через запятую");
        col.addView(title); col.addView(nicks);
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("Новая группа").setView(col)
                .setPositiveButton("Создать", (d, w) -> {
                    final String t = title.getText().toString().trim();
                    final String raw = nicks.getText().toString();
                    if (t.isEmpty()) { Ui.toast(this, "Введите название"); return; }
                    io.execute(() -> {
                        try {
                            Api api = Api.get(this);
                            JSONArray ids = new JSONArray();
                            List<String> missing = new ArrayList<>();
                            for (String n : raw.split("[,\\s]+")) {
                                String nick = n.trim().replaceFirst("^@+", "").toLowerCase(java.util.Locale.ROOT);
                                if (nick.isEmpty()) continue;
                                JSONArray r = api.select("profiles?select=id&username=eq." + java.net.URLEncoder.encode(nick, "UTF-8"));
                                if (r.length() == 0) missing.add("@" + nick); else ids.put(r.getJSONObject(0).getString("id"));
                            }
                            if (!missing.isEmpty()) { Ui.toast(this, "Не найдены: " + String.join(", ", missing)); return; }
                            api.rpc("create_group_chat", new JSONObject().put("p_title", t).put("p_members", ids));
                            loadChats();
                        } catch (Exception e) {
                            authFail(e);
                        }
                    });
                }).setNegativeButton("Отмена", null).show();
    }

    // ---- вход на сайте по QR ----
    private void handleQr(String content) {
        String token;
        try {
            JSONObject o = new JSONObject(content);
            if (!"msgr-login".equals(o.optString("t")) || o.optInt("v") != 1) throw new Exception();
            token = o.getString("token");
            if (!TOKEN.matcher(token).matches()) throw new Exception();
        } catch (Exception e) {
            Ui.toast(this, "Это не QR для входа в мессенджер");
            return;
        }
        final String tk = token;
        io.execute(() -> {
            try {
                String d = Api.get(this).rpc("qr_describe", new JSONObject().put("p_token", tk)).trim();
                if (d.isEmpty() || d.equals("null")) { Ui.toast(this, "QR устарел, обновите страницу"); return; }
                if (d.startsWith("\"")) d = new org.json.JSONTokener(d).nextValue().toString();
                final String desc = d;
                runOnUiThread(() -> new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle("Разрешить вход?")
                        .setMessage(desc + "\n\nВходите только если сами только что открыли этот QR на странице входа. Иначе нажмите «Запретить».")
                        .setPositiveButton("Разрешить", (x, y) -> approve(tk, true))
                        .setNegativeButton("Запретить", (x, y) -> approve(tk, false)).show());
            } catch (Exception e) {
                authFail(e);
            }
        });
    }

    private void approve(String token, boolean allow) {
        io.execute(() -> {
            try {
                Api.get(this).rpc("qr_approve", new JSONObject().put("p_token", token).put("p_allow", allow));
                Ui.toast(this, allow ? "Вход разрешён" : "Вход запрещён");
            } catch (Exception e) {
                authFail(e);
            }
        });
    }

    static String chatTitle(JSONObject o) {
        String t = o.isNull("title") ? "" : o.optString("title", "");
        if (t.isEmpty()) t = o.isNull("peer_username") ? "Личный чат" : "@" + o.optString("peer_username", "");
        return t;
    }
    /** Рисует список (из сервера или из офлайн-кэша). UI-поток. */
    private boolean showArchive = false;
    private JSONArray lastArr = new JSONArray();
    static boolean muted(JSONObject c) { return !c.isNull("muted_until") && c.has("muted_until") && Ui.instant(c.optString("muted_until")) > System.currentTimeMillis(); }
    private void applyChats(JSONArray arr) throws Exception {
        lastArr = arr;
        JSONObject pv = Cache.previews(this);
        List<String> cid = new ArrayList<>(), ct = new ArrayList<>();
        List<JSONObject> rs = new ArrayList<>();
        List<JSONObject> sorted = new ArrayList<>();
        int nArch = 0, unArch = 0;
        for (int i = 0; i < arr.length(); i++) { JSONObject o = arr.getJSONObject(i); if (o.optBoolean("archived")) { nArch++; unArch += o.optInt("unread"); } if (o.optBoolean("archived") == showArchive) sorted.add(o); }
        sorted.sort((x, y) -> { int pp = Boolean.compare(y.optBoolean("pinned"), x.optBoolean("pinned")); return pp != 0 ? pp : Long.compare(Ui.instant(y.optString("last_at")), Ui.instant(x.optString("last_at"))); });
        if (showArchive || nArch > 0) {
            JSONObject a = new JSONObject().put("_archive", true).put("chat_id", "").put("_title", "Архив")
                    .put("_sub", showArchive ? "Назад ко всем чатам" : nArch + " " + Site.plural(nArch, "чат", "чата", "чатов")).put("unread", showArchive ? 0 : unArch);
            cid.add(""); ct.add("Архив"); rs.add(a);
        }
        for (JSONObject o : sorted) {
            String t = chatTitle(o);
            o.put("_title", t);
            JSONObject p = pv.optJSONObject(o.getString("chat_id"));
            // Расшифрованное превью (сохраняет экран чата), если оно про то же последнее сообщение
            if (p != null && !o.isNull("last_at") && Ui.instantMicros(p.optString("at")) == Ui.instantMicros(o.optString("last_at"))) o.put("_preview", p.optString("t"));
            cid.add(o.getString("chat_id")); ct.add(t); rs.add(o);
        }
        ids.clear(); ids.addAll(cid);
        titles.clear(); titles.addAll(ct);
        rows.clear(); rows.addAll(rs);
        adapter.notifyDataSetChanged();
        empty.setText(showArchive ? "В архиве пусто." : "Чатов пока нет. Найдите собеседника по никнейму.");
        empty.setVisibility(sorted.isEmpty() ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    /** Новое входящее: rpc mark_delivered (две серые галочки у отправителя) и локальное уведомление. Фоновый поток. */
    private void delivered(JSONArray arr) {
        String me = Api.get(this).uid();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null || o.isNull("last_at")) continue;
            String id = o.optString("chat_id"), at = o.optString("last_at");
            String prev = lastSeenAt.put(id, at);
            boolean incoming = !me.equals(o.optString("last_sender"));
            if (!incoming || at.equals(prev)) continue;
            if (prev != null || o.optInt("unread", 0) > 0) {
                try { Api.get(this).rpc("mark_delivered", new JSONObject().put("p_chat", id).put("p_upto", at)); } catch (Exception ignored) { }
            }
            // уведомления показывает NotifyService (и в фоне, и при открытом списке) — здесь не дублируем
        }
        firstLoad = false;
    }
}
