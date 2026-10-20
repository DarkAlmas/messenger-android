package com.darkcompany.messenger;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Аватары и профили. Автоаватар: буква + цвет фона; фото — квадрат 256×256 JPEG.
 * Серверные поля: bio, avatar_letter, avatar_color, avatar_url (путь {uid}/{имя} в приватном бакете avatars, показ по подписанной ссылке);
 * Свои буква/цвет/фото дублируются на устройстве (показ сразу, до ответа сервера).
 */
final class Avatars {
    static final int[] PALETTE = {0xFF5C6BC0, 0xFF26A69A, 0xFFEF6C00, 0xFFAB47BC, 0xFF42A5F5, 0xFFEC407A, 0xFF7CB342, 0xFF8D6E63};

    /** Кэш профилей: id -> строка profiles (select=*), с отметкой времени. */
    static final Map<String, JSONObject> profiles = new ConcurrentHashMap<>();
    private static final Map<String, Long> loadedAt = new ConcurrentHashMap<>();
    private static final Map<String, Bitmap> bitmaps = new ConcurrentHashMap<>();
    /** Сервер уже знает поля профиля (bio/avatar_*)? null — ещё не проверяли. */
    static volatile Boolean serverHasFields = null;

    static SharedPreferences prefs(Context c) { return c.getApplicationContext().getSharedPreferences("msgr_profile", Context.MODE_PRIVATE); }

    // ---------- свой профиль (локально) ----------
    static String myLetter(Context c) { return prefs(c).getString("letter", ""); }
    static int myColor(Context c) { return prefs(c).getInt("color", 0); }
    static File myPhotoFile(Context c) { return new File(c.getFilesDir(), "my_avatar.jpg"); }

    /** Профиль с учётом локальных настроек, если это я. */
    static JSONObject merged(Context c, String uid) {
        JSONObject p = profiles.get(uid);
        JSONObject o = new JSONObject();
        try {
            if (p != null) o = new JSONObject(p.toString());
            if (uid != null && uid.equals(Api.get(c).uid())) {
                if (o.optString("avatar_letter", "").isEmpty() && !myLetter(c).isEmpty()) o.put("avatar_letter", myLetter(c));
                if (o.optString("avatar_color", "").isEmpty() && myColor(c) != 0) o.put("avatar_color", String.format("#%06X", myColor(c) & 0xFFFFFF));
                if (myPhotoFile(c).isFile()) o.put("_local_photo", myPhotoFile(c).getAbsolutePath());
            }
        } catch (Exception ignored) { }
        return o;
    }

    static int colorFor(JSONObject p, String seed) {
        String hex = p == null ? "" : p.optString("avatar_color", "");
        if (hex.matches("#[0-9A-Fa-f]{6}")) return Color.parseColor(hex);
        String id = seed == null ? "" : seed; int h = 0; // спецификация avatar_color.md: сумма кодов UTF-16 mod 8 — как на сайте
        for (int i = 0; i < id.length(); i++) h += id.charAt(i);
        return PALETTE[h % PALETTE.length];
    }

    static String letterFor(JSONObject p, String name) {
        String l = p == null ? "" : p.optString("avatar_letter", "").trim();
        if (!l.isEmpty()) return l.substring(0, Math.min(l.length(), l.offsetByCodePoints(0, Math.min(2, l.codePointCount(0, l.length())))));
        String n = name == null ? "" : name.trim().replaceFirst("^@", "");
        return n.isEmpty() ? "?" : n.substring(0, n.offsetByCodePoints(0, 1)).toUpperCase();
    }

    /** Круглый аватар: фото (если есть в кэше) или буква на цветном круге. */
    static View view(Context c, String uid, String name, int sizeDp) {
        JSONObject p = uid == null ? new JSONObject() : merged(c, uid);
        return render(c, p, name, uid, sizeDp, photoFor(c, uid, p));
    }

    static View render(Context c, JSONObject p, String name, String seed, int sizeDp, Bitmap photo) {
        int px = Ui.dp(c, sizeDp);
        if (photo != null) {
            ImageView iv = new ImageView(c);
            iv.setImageBitmap(circle(photo, px));
            iv.setLayoutParams(new FrameLayout.LayoutParams(px, px));
            return iv;
        }
        TextView a = new TextView(c);
        a.setText(letterFor(p, name));
        a.setGravity(Gravity.CENTER);
        a.setTextColor(Color.WHITE);
        a.setTextSize(sizeDp * 0.4f);
        a.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        int col = colorFor(p, seed);
        g.setColor(col); // сплошной цвет, как .ava на сайте
        a.setBackground(g);
        return a;
    }

    static int lighten(int c) {
        int r = Math.min(255, Color.red(c) + 40), g = Math.min(255, Color.green(c) + 40), b = Math.min(255, Color.blue(c) + 40);
        return Color.rgb(r, g, b);
    }

    static Bitmap circle(Bitmap src, int px) {
        Bitmap out = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(out);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        cv.drawOval(new RectF(0, 0, px, px), p);
        p.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        int s = Math.min(src.getWidth(), src.getHeight());
        Rect from = new Rect((src.getWidth() - s) / 2, (src.getHeight() - s) / 2, (src.getWidth() + s) / 2, (src.getHeight() + s) / 2);
        cv.drawBitmap(src, from, new Rect(0, 0, px, px), p);
        return out;
    }

    /** Фото из памяти/диска (без сети). Сеть — в {@link #refresh}. */
    static Bitmap photoFor(Context c, String uid, JSONObject p) {
        if (uid == null) return null;
        String local = p.optString("_local_photo", "");
        String key = !local.isEmpty() ? "local:" + new File(local).lastModified() : p.optString("avatar_url", "");
        if (key.isEmpty()) return null;
        Bitmap b = bitmaps.get(uid + "|" + key);
        if (b != null) return b;
        File f = !local.isEmpty() ? new File(local) : cacheFile(c, p.optString("avatar_url"));
        if (!f.isFile()) return null;
        b = BitmapFactory.decodeFile(f.getAbsolutePath());
        if (b != null) bitmaps.put(uid + "|" + key, b);
        return b;
    }

    static File cacheFile(Context c, String path) {
        File d = new File(c.getCacheDir(), "avatars"); d.mkdirs();
        return new File(d, path.replaceAll("[^A-Za-z0-9._-]", "_"));
    }

    /** Подгружает профили (и фото) указанных пользователей; вызывать в фоновом потоке. Возвращает true, если что-то изменилось. */
    static boolean refresh(Context c, java.util.Collection<String> ids, long maxAgeMs) {
        StringBuilder need = new StringBuilder();
        long now = System.currentTimeMillis();
        for (String id : ids) {
            if (id == null || id.isEmpty()) continue;
            Long t = loadedAt.get(id);
            if (t != null && now - t < maxAgeMs) continue;
            if (need.length() > 0) need.append(',');
            need.append(id);
        }
        if (need.length() == 0) return false;
        boolean changed = false;
        try {
            JSONArray arr = Api.get(c).select("profiles?select=*&id=in.(" + need + ")");
            for (int i = 0; i < arr.length(); i++) {
                JSONObject p = arr.getJSONObject(i);
                String id = p.getString("id");
                if (serverHasFields == null) serverHasFields = p.has("avatar_color");
                JSONObject old = profiles.put(id, p);
                loadedAt.put(id, now);
                if (old == null || !old.toString().equals(p.toString())) changed = true;
                String ap = p.optString("avatar_url", "");
                if (!ap.isEmpty() && !p.isNull("avatar_url") && !cacheFile(c, ap).isFile()) { downloadPhoto(c, ap); changed = true; }
            }
        } catch (Exception e) {
            if (BuildConfig.DEBUG) android.util.Log.w("Avatars", "refresh: " + e);
        }
        return changed;
    }

    static void downloadPhoto(Context c, String path) {
        File f = cacheFile(c, path);
        try {
            HttpURLConnection h = (HttpURLConnection) new URL(signed(c, path)).openConnection();
            if (h.getResponseCode() / 100 != 2) return;
            try (InputStream in = h.getInputStream(); OutputStream out = new FileOutputStream(f)) {
                byte[] b = new byte[16 * 1024]; int r; long got = 0;
                while ((r = in.read(b)) > 0) { got += r; if (got > 1024 * 1024) throw new java.io.IOException("too big"); out.write(b, 0, r); }
            }
        } catch (Exception e) { f.delete(); }
    }

    /** Подписанная ссылка (бакет avatars приватный). */
    static String signed(Context c, String path) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(Api.URL + "/storage/v1/object/sign/avatars/" + path).openConnection();
        h.setRequestMethod("POST"); h.setDoOutput(true);
        h.setRequestProperty("apikey", Api.KEY);
        h.setRequestProperty("Authorization", "Bearer " + Api.get(c).freshToken());
        h.setRequestProperty("Content-Type", "application/json");
        try (OutputStream o = h.getOutputStream()) { o.write("{\"expiresIn\":300}".getBytes("UTF-8")); }
        if (h.getResponseCode() / 100 != 2) throw new java.io.IOException("sign " + h.getResponseCode());
        try (InputStream in = h.getInputStream()) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(); byte[] b = new byte[4096]; int r;
            while ((r = in.read(b)) > 0) bo.write(b, 0, r);
            return Api.URL + "/storage/v1" + new JSONObject(bo.toString("UTF-8")).getString("signedURL");
        }
    }

    /** Удаляет старое фото (своё). Ошибки не критичны. */
    static void deletePhoto(Context c, String path) {
        if (path == null || path.isEmpty() || !path.startsWith(Api.get(c).uid() + "/")) return;
        try {
            HttpURLConnection h = (HttpURLConnection) new URL(Api.URL + "/storage/v1/object/avatars/" + path).openConnection();
            h.setRequestMethod("DELETE");
            h.setRequestProperty("apikey", Api.KEY);
            h.setRequestProperty("Authorization", "Bearer " + Api.get(c).freshToken());
            h.getResponseCode();
        } catch (Exception ignored) { }
    }

    /** Загружает своё фото в бакет avatars/<uid>/<время>.jpg. Возвращает путь или бросает исключение. */
    static String uploadMyPhoto(Context c, File jpg) throws Exception {
        String uid = Api.get(c).uid();
        String path = uid + "/" + System.currentTimeMillis() + ".jpg";
        HttpURLConnection h = (HttpURLConnection) new URL(Api.URL + "/storage/v1/object/avatars/" + path).openConnection();
        h.setRequestMethod("POST"); h.setDoOutput(true);
        h.setRequestProperty("apikey", Api.KEY);
        h.setRequestProperty("Authorization", "Bearer " + Api.get(c).freshToken());
        h.setRequestProperty("Content-Type", "image/jpeg");
        h.setRequestProperty("x-upsert", "true");
        byte[] data = java.nio.file.Files.readAllBytes(jpg.toPath());
        h.setFixedLengthStreamingMode(data.length);
        try (OutputStream o = h.getOutputStream()) { o.write(data); }
        int code = h.getResponseCode();
        if (code / 100 != 2) throw new java.io.IOException("Сервер пока не принимает аватары (бакет avatars, код " + code + ")");
        return path;
    }

    /** Квадрат 256×256 JPEG из выбранной картинки. */
    static void saveSquare(Context c, android.net.Uri uri, File out) throws Exception {
        Bitmap src;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 1;
            src = BitmapFactory.decodeStream(in, null, o);
        }
        if (src == null) throw new java.io.IOException("Не удалось прочитать изображение");
        int s = Math.min(src.getWidth(), src.getHeight());
        Bitmap sq = Bitmap.createBitmap(src, (src.getWidth() - s) / 2, (src.getHeight() - s) / 2, s, s);
        Bitmap sc = Bitmap.createScaledBitmap(sq, 256, 256, true);
        try (FileOutputStream fo = new FileOutputStream(out)) { sc.compress(Bitmap.CompressFormat.JPEG, 88, fo); }
        bitmaps.clear();
    }

    // ---------- тема ----------
    static final String[] THEMES = {"Тёмная", "Светлая", "Как в системе"};
    static int themeIndex(Context c) { return prefs(c).getInt("theme", 0); }
    static void applyTheme(Context c) {
        int i = themeIndex(c);
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(i == 0 ? androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                : i == 1 ? androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO : androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }

    static void setHeader(Activity a, String uid, String title, String subtitle, View.OnClickListener click) {
        androidx.appcompat.app.ActionBar ab = ((androidx.appcompat.app.AppCompatActivity) a).getSupportActionBar();
        if (ab == null) return;
        android.widget.LinearLayout row = new android.widget.LinearLayout(a);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        View av = view(a, uid, title, 38);
        row.addView(av, new android.widget.LinearLayout.LayoutParams(Ui.dp(a, 38), Ui.dp(a, 38)));
        android.widget.LinearLayout col = new android.widget.LinearLayout(a);
        col.setOrientation(android.widget.LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(a, 12), 0, 0, 0);
        TextView t = Ui.text(a, title, 18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setSingleLine(true);
        col.addView(t);
        if (subtitle != null) {
            TextView s = Ui.text(a, subtitle, 12);
            s.setTextColor(Ui.attr(a, com.google.android.material.R.attr.colorOnSurfaceVariant));
            s.setSingleLine(true);
            col.addView(s);
        }
        row.addView(col);
        if (click != null) row.setOnClickListener(click);
        ab.setDisplayShowTitleEnabled(false);
        ab.setDisplayShowCustomEnabled(true);
        ab.setCustomView(row);
    }
}
