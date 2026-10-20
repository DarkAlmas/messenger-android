package com.darkcompany.messenger;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Обновления из GitHub Releases: тег вида v<versionCode>, вложение .apk. Проверка при запуске, не чаще раза в 3 часа. */
final class Updater {
    private static final long EVERY_MS = 3 * 3600_000L;
    private static final Pattern TAG = Pattern.compile("^v?(\\d+)");
    private Updater() { }

    static int codeOf(String tag) { Matcher m = TAG.matcher(tag == null ? "" : tag.trim()); return m.find() ? Integer.parseInt(m.group(1)) : -1; }

    static void check(Activity a, boolean force) {
        android.content.SharedPreferences sp = a.getSharedPreferences("upd", 0);
        if (!force && System.currentTimeMillis() - sp.getLong("last", 0) < EVERY_MS) return;
        sp.edit().putLong("last", System.currentTimeMillis()).apply();
        new Thread(() -> {
            try {
                okhttp3.Request r = new okhttp3.Request.Builder().url("https://api.github.com/repos/" + BuildConfig.UPDATE_REPO + "/releases/latest")
                        .header("Accept", "application/vnd.github+json").build();
                JSONObject rel;
                try (okhttp3.Response resp = new okhttp3.OkHttpClient().newCall(r).execute()) {
                    if (!resp.isSuccessful() || resp.body() == null) return;
                    rel = new JSONObject(resp.body().string());
                }
                String tag = rel.optString("tag_name");
                int code = codeOf(tag);
                if (code <= BuildConfig.VERSION_CODE) { if (force) Ui.toast(a, "Установлена последняя версия"); return; }
                String url = null, name = null;
                JSONArray as = rel.optJSONArray("assets");
                for (int i = 0; as != null && i < as.length(); i++) {
                    JSONObject x = as.getJSONObject(i);
                    if (x.optString("name").endsWith(".apk")) { url = x.optString("browser_download_url"); name = x.optString("name"); break; }
                }
                if (url == null || !url.startsWith("https://github.com/")) return;
                String ver = rel.optString("name", tag); if (ver.isEmpty()) ver = tag;
                final String fu = url, fver = ver;
                a.runOnUiThread(() -> {
                    if (a.isFinishing()) return;
                    new com.google.android.material.dialog.MaterialAlertDialogBuilder(a)
                            .setTitle("Обновление")
                            .setMessage("Доступна новая версия " + fver + ". Установить?")
                            .setPositiveButton("Установить", (d, w) -> download(a, fu, code))
                            .setNegativeButton("Позже", null).show();
                });
            } catch (Exception e) { android.util.Log.w("Upd", "check: " + e); }
        }, "updater").start();
    }

    private static File target(Context c, int code) { File d = new File(c.getCacheDir(), "chat-files"); d.mkdirs(); return new File(d, "update-" + code + ".apk"); }

    private static void download(Activity a, String url, int code) {
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            // Разрешение «Установка неизвестных приложений» для этого приложения
            Ui.toast(a, "Разрешите установку из этого приложения и нажмите «Назад» — загрузка начнётся");
            pending = new Object[]{url, code};
            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
            return;
        }
        Context app = a.getApplicationContext();
        File ext = new File(app.getExternalFilesDir(null), "update-" + code + ".apk");
        ext.delete();
        DownloadManager dm = (DownloadManager) app.getSystemService(Context.DOWNLOAD_SERVICE);
        DownloadManager.Request rq = new DownloadManager.Request(Uri.parse(url))
                .setTitle("Мессенджер: обновление").setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setDestinationUri(Uri.fromFile(ext));
        final long id = dm.enqueue(rq);
        Ui.toast(a, "Загружаю обновление…");
        BroadcastReceiver br = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (i.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != id) return;
                try { app.unregisterReceiver(this); } catch (Exception ignored) { }
                try (android.database.Cursor cu = dm.query(new DownloadManager.Query().setFilterById(id))) {
                    if (cu == null || !cu.moveToFirst() || cu.getInt(cu.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) != DownloadManager.STATUS_SUCCESSFUL) {
                        android.widget.Toast.makeText(app, "Не удалось загрузить обновление", android.widget.Toast.LENGTH_LONG).show(); return;
                    }
                }
                install(app, ext, code);
            }
        };
        IntentFilter f = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(br, f, Context.RECEIVER_EXPORTED); else app.registerReceiver(br, f);
    }

    /** После возврата из настроек «неизвестные источники» — продолжить загрузку. */
    private static Object[] pending;
    static void resume(Activity a) {
        Object[] p = pending;
        if (p == null || (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls())) return;
        pending = null; download(a, (String) p[0], (Integer) p[1]);
    }

    private static void install(Context app, File ext, int code) {
        try {
            File dst = target(app, code); // FileProvider отдаёт cache/chat-files
            try (java.io.InputStream in = new java.io.FileInputStream(ext); java.io.OutputStream out = new java.io.FileOutputStream(dst)) {
                byte[] b = new byte[65536]; int r; while ((r = in.read(b)) > 0) out.write(b, 0, r);
            }
            ext.delete();
            Uri u = androidx.core.content.FileProvider.getUriForFile(app, app.getPackageName() + ".files", dst);
            Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(i);
        } catch (Exception e) { android.widget.Toast.makeText(app, "Не удалось открыть установщик: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show(); }
    }
}
