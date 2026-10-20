package com.darkcompany.messenger;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
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
                if (url == null || !allowedHost(url)) return;
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
            } catch (Exception e) { if (BuildConfig.DEBUG) android.util.Log.w("Upd", "check: " + e); }
        }, "updater").start();
    }

    private static File target(Context c, int code) { File d = new File(c.getCacheDir(), "updates"); d.mkdirs(); return new File(d, "update-" + code + ".apk"); }

    private static void download(Activity a, String url, int code) {
        if (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls()) {
            // Разрешение «Установка неизвестных приложений» для этого приложения
            Ui.toast(a, "Разрешите установку из этого приложения и нажмите «Назад» — загрузка начнётся");
            pending = new Object[]{url, code};
            a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
            return;
        }
        Context app = a.getApplicationContext();
        Ui.toast(a, "Загружаю обновление…");
        // Скачивание в cacheDir (не во внешнюю память), без DownloadManager и без экспортируемого ресивера
        new Thread(() -> {
            File dst = target(app, code);
            try {
                fetch(url, dst);
                String why = verify(app, dst, code);
                if (why != null) { dst.delete(); toastMain(app, "Обновление отклонено: " + why); return; }
                install(app, dst);
            } catch (Exception e) { dst.delete(); toastMain(app, "Не удалось загрузить обновление"); }
        }, "update-dl").start();
    }

    private static final long MAX_APK = 200L * 1024 * 1024;
    private static void fetch(String url, File dst) throws Exception {
        for (int hop = 0; hop < 5; hop++) {
            if (!allowedHost(url)) throw new java.io.IOException("host");
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setInstanceFollowRedirects(false); c.setConnectTimeout(20000); c.setReadTimeout(60000);
            int code = c.getResponseCode();
            if (code / 100 == 3) {
                String loc = c.getHeaderField("Location"); c.disconnect();
                if (!allowedHost(loc)) throw new java.io.IOException("redirect");
                url = loc; continue;
            }
            if (code != 200) throw new java.io.IOException("HTTP " + code);
            long n = 0;
            try (java.io.InputStream in = c.getInputStream(); java.io.OutputStream out = new java.io.FileOutputStream(dst)) {
                byte[] b = new byte[65536]; int r;
                while ((r = in.read(b)) > 0) { n += r; if (n > MAX_APK) throw new java.io.IOException("too big"); out.write(b, 0, r); }
            }
            return;
        }
        throw new java.io.IOException("too many redirects");
    }

    private static final java.util.Set<String> HOSTS = new java.util.HashSet<>(java.util.Arrays.asList(
            "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com"));
    /** Скачивание и редиректы — только https и только GitHub (релиз → release-assets.githubusercontent.com). */
    static boolean allowedHost(String url) {
        try { java.net.URI u = new java.net.URI(url); return "https".equals(u.getScheme()) && u.getHost() != null && HOSTS.contains(u.getHost().toLowerCase(java.util.Locale.ROOT)) && u.getPort() == -1; }
        catch (Exception e) { return false; }
    }

    /** null — можно ставить; иначе причина отказа. Та же подпись, тот же пакет, версия новее установленной. */
    static String verify(Context c, File apk, int expectCode) {
        try {
            android.content.pm.PackageManager pm = c.getPackageManager();
            int fl = android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES;
            android.content.pm.PackageInfo upd = pm.getPackageArchiveInfo(apk.getAbsolutePath(), fl);
            if (upd == null) return "файл повреждён";
            if (!c.getPackageName().equals(upd.packageName)) return "другое приложение";
            android.content.pm.PackageInfo cur = pm.getPackageInfo(c.getPackageName(), fl);
            if (upd.getLongVersionCode() <= cur.getLongVersionCode()) return "версия не новее установленной";
            if (upd.signingInfo == null || cur.signingInfo == null || upd.signingInfo.hasMultipleSigners() || cur.signingInfo.hasMultipleSigners()) return "нет подписи";
            android.content.pm.Signature[] a = upd.signingInfo.getApkContentsSigners(), b = cur.signingInfo.getApkContentsSigners();
            if (a == null || b == null || a.length != 1 || b.length != 1 || !java.util.Arrays.equals(a[0].toByteArray(), b[0].toByteArray())) return "подпись не совпадает";
            return null;
        } catch (Exception e) { return "не удалось проверить подпись"; }
    }

    private static void toastMain(Context app, String m) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> android.widget.Toast.makeText(app, m, android.widget.Toast.LENGTH_LONG).show());
    }

    /** После возврата из настроек «неизвестные источники» — продолжить загрузку. */
    private static Object[] pending;
    static void resume(Activity a) {
        Object[] p = pending;
        if (p == null || (Build.VERSION.SDK_INT >= 26 && !a.getPackageManager().canRequestPackageInstalls())) return;
        pending = null; download(a, (String) p[0], (Integer) p[1]);
    }

    private static void install(Context app, File dst) {
        try {
            Uri u = androidx.core.content.FileProvider.getUriForFile(app, app.getPackageName() + ".files", dst);
            Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(u, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            app.startActivity(i);
        } catch (Exception e) { toastMain(app, "Не удалось открыть установщик"); }
    }
}
