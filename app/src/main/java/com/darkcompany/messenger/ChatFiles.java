package com.darkcompany.messenger;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.webkit.MimeTypeMap;
import androidx.core.content.FileProvider;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.UUID;

/** Вложения чата: bucket chat-files, путь chat/<chatId>/<uuid>_<имя>. Вызывать не из UI-потока. */
public final class ChatFiles {
    public static final String BASE = Api.URL;
    public static final String KEY = Api.KEY;
    public static final long MAX = 25L * 1024 * 1024;

    /** Убирает / \ .. и управляющие символы, ограничивает длину. */
    public static String safeName(String n) {
        if (n == null) n = "file";
        n = n.replaceAll("[\\\\/\\p{Cntrl}]", "_").replace("..", "_").trim();
        if (n.isEmpty() || n.equals(".")) n = "file";
        return n.length() > 200 ? n.substring(n.length() - 200) : n;
    }

    /** Загружает файл; возвращает attachment_path. Потом insert в messages: chat_id, sender_id, body, attachment_path/name/size. */
    public static String upload(Context ctx, Uri src, String name, long size, String mime, String chatId, String jwt) throws IOException {
        if (size <= 0 || size > MAX) throw new IOException("Файл больше 25 МБ");
        String path = "chat/" + chatId + "/" + UUID.randomUUID() + "_" + safeName(name);
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + "/storage/v1/object/chat-files/" + enc(path)).openConnection();
        c.setRequestMethod("POST"); c.setDoOutput(true); c.setFixedLengthStreamingMode(size);
        c.setRequestProperty("apikey", KEY);
        c.setRequestProperty("Authorization", "Bearer " + jwt);
        c.setRequestProperty("Content-Type", mime != null ? mime : "application/octet-stream");
        c.setRequestProperty("x-upsert", "false");
        long sent = 0;
        try (InputStream in = ctx.getContentResolver().openInputStream(src); OutputStream out = c.getOutputStream()) {
            byte[] b = new byte[64 * 1024]; int r;
            while ((r = in.read(b)) > 0) { sent += r; if (sent > MAX) throw new IOException("Файл больше 25 МБ"); out.write(b, 0, r); }
        }
        if (c.getResponseCode() / 100 != 2) throw new IOException("Загрузка не удалась: " + c.getResponseCode());
        return path;
    }

    /** Удаляет свой объект (отправка не удалась / сообщение удалено) — как storage.remove на сайте. Ошибки глотаются. */
    public static void remove(String path, String jwt) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(BASE + "/storage/v1/object/chat-files").openConnection();
            c.setRequestMethod("DELETE"); c.setDoOutput(true);
            c.setRequestProperty("apikey", KEY);
            c.setRequestProperty("Authorization", "Bearer " + jwt);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream o = c.getOutputStream()) { o.write(new JSONObject().put("prefixes", new org.json.JSONArray().put(path)).toString().getBytes("UTF-8")); }
            c.getResponseCode();
        } catch (Exception ignored) { }
    }

    /** Подписанная ссылка на 60 секунд. Не логировать. */
    public static String signedUrl(String path, String jwt) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + "/storage/v1/object/sign/chat-files/" + enc(path)).openConnection();
        c.setRequestMethod("POST"); c.setDoOutput(true);
        c.setRequestProperty("apikey", KEY);
        c.setRequestProperty("Authorization", "Bearer " + jwt);
        c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream o = c.getOutputStream()) { o.write("{\"expiresIn\":60}".getBytes("UTF-8")); }
        if (c.getResponseCode() / 100 != 2) throw new IOException("Нет доступа к файлу");
        try (InputStream in = c.getInputStream()) {
            String s = new String(readAll(in), "UTF-8");
            return BASE + "/storage/v1" + new JSONObject(s).getString("signedURL");
        } catch (org.json.JSONException e) { throw new IOException(e); }
    }

    static File cacheDir(Context ctx) { File d = new File(ctx.getCacheDir(), "chat-files"); d.mkdirs(); return d; }

    /** Скачивает в cache/chat-files под последним сегментом attachment_path (с UUID). Частичный файл удаляется при ошибке. */
    public static File download(Context ctx, String path, String jwt) throws IOException {
        File dir = cacheDir(ctx);
        String last = path.substring(path.lastIndexOf('/') + 1);
        File f = new File(dir, safeName(last));
        if (!f.getCanonicalPath().startsWith(dir.getCanonicalPath() + File.separator)) throw new IOException("bad name");
        if (f.isFile() && f.length() > 0) return f;
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(signedUrl(path, jwt)).openConnection();
            if (c.getResponseCode() / 100 != 2) throw new IOException("Скачивание не удалось");
            long got = 0;
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(f)) {
                byte[] b = new byte[64 * 1024]; int r;
                while ((r = in.read(b)) > 0) { got += r; if (got > MAX) throw new IOException("Файл слишком большой"); out.write(b, 0, r); }
            }
            return f;
        } catch (IOException | RuntimeException e) { f.delete(); throw e; }
    }

    /** Открывает файл через FileProvider (authority <applicationId>.files). */
    public static void open(Context ctx, File f, String displayName) {
        String ext = MimeTypeMap.getFileExtensionFromUrl(Uri.fromFile(new File(safeName(displayName))).toString());
        if (ext == null || ext.isEmpty()) { int d = displayName.lastIndexOf('.'); ext = d >= 0 ? displayName.substring(d + 1) : null; }
        String mime = ext != null ? MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase()) : null;
        Uri u = FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".files", f);
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(u, mime != null ? mime : "*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(Intent.createChooser(i, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /** Копирует Uri во временный файл (если размер неизвестен), не больше MAX. */
    public static File copyToTemp(Context ctx, Uri src) throws IOException {
        File t = File.createTempFile("up_", ".tmp", ctx.getCacheDir());
        long n = 0;
        try (InputStream in = ctx.getContentResolver().openInputStream(src); OutputStream out = new FileOutputStream(t)) {
            if (in == null) throw new IOException("Не удалось прочитать файл");
            byte[] b = new byte[64 * 1024]; int r;
            while ((r = in.read(b)) > 0) { n += r; if (n > MAX) throw new IOException("Файл больше 25 МБ"); out.write(b, 0, r); }
        } catch (IOException e) { t.delete(); throw e; }
        return t;
    }

    /** Удаляет файлы старше 24 часов из cache/chat-files. */
    public static void cleanup(Context ctx) {
        File[] fs = new File(ctx.getCacheDir(), "chat-files").listFiles();
        if (fs == null) return;
        long cut = System.currentTimeMillis() - 24L * 3600 * 1000;
        for (File f : fs) if (f.lastModified() < cut) f.delete();
    }

    private static final String[] RISKY = {"apk", "apks", "xapk", "aab", "exe", "msi", "bat", "cmd", "sh", "jar", "dex", "js", "vbs", "ps1", "scr", "com", "html", "htm", "xhtml", "svg", "mht", "mhtml"};
    public static boolean risky(String name) {
        String n = name == null ? "" : name.toLowerCase();
        for (String e : RISKY) if (n.endsWith("." + e)) return true;
        return false;
    }

    private static String enc(String path) throws UnsupportedEncodingException {
        StringBuilder sb = new StringBuilder();
        for (String part : path.split("/")) { if (sb.length() > 0) sb.append('/'); sb.append(URLEncoder.encode(part, "UTF-8").replace("+", "%20")); }
        return sb.toString();
    }
    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int r;
        while ((r = in.read(b)) > 0) o.write(b, 0, r); return o.toByteArray();
    }
}
