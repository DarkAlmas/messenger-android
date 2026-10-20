package com.darkcompany.messenger;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

/** Офлайн-кэш (как на сайте): список чатов и последние 100 сообщений каждого чата с расшифрованным содержимым. Только во внутренней памяти приложения. */
final class Cache {
    static final int MAX_MSGS = 100;
    private Cache() { }
    private static File dir(Context c) { File d = new File(c.getFilesDir(), "offline"); d.mkdirs(); return d; }
    private static String safe(String id) { return id.replaceAll("[^0-9a-zA-Z-]", "_"); }

    static synchronized void put(Context c, String name, String data) {
        File f = new File(dir(c), name), tmp = new File(dir(c), name + ".tmp");
        try (FileOutputStream o = new FileOutputStream(tmp)) { o.write(data.getBytes("UTF-8")); }
        catch (Exception e) { tmp.delete(); return; }
        if (!tmp.renameTo(f)) tmp.delete();
    }
    static synchronized String get(Context c, String name) {
        File f = new File(dir(c), name);
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(); byte[] b = new byte[8192]; int r;
            while ((r = in.read(b)) > 0) bo.write(b, 0, r);
            return bo.toString("UTF-8");
        } catch (Exception e) { return null; }
    }

    static void putChats(Context c, String raw) { put(c, "chats.json", raw); }
    static JSONArray chats(Context c) { try { String s = get(c, "chats.json"); return s == null ? null : new JSONArray(s); } catch (Exception e) { return null; } }

    /** msgs — по возрастанию времени; сохраняем хвост из 100 штук и расшифровку только для них. */
    static void putChat(Context c, String chatId, JSONArray msgs, java.util.Map<String, JSONObject> dec) {
        try {
            JSONArray tail = new JSONArray(); JSONObject d = new JSONObject();
            for (int i = Math.max(0, msgs.length() - MAX_MSGS); i < msgs.length(); i++) {
                JSONObject m = msgs.getJSONObject(i); tail.put(m);
                JSONObject p = dec.get(m.optString("id"));
                if (p != null && !p.has("err")) d.put(m.optString("id"), p);
            }
            put(c, "chat_" + safe(chatId) + ".json", new JSONObject().put("m", tail).put("d", d).toString());
        } catch (Exception ignored) { }
    }
    /** {m: [...], d: {id: payload}} или null. */
    static JSONObject chat(Context c, String chatId) { try { String s = get(c, "chat_" + safe(chatId) + ".json"); return s == null ? null : new JSONObject(s); } catch (Exception e) { return null; } }

    /** Превью последнего сообщения (расшифрованное), для списка чатов. */
    static void putPreview(Context c, String chatId, String at, String text) {
        try {
            JSONObject all = previews(c); all.put(chatId, new JSONObject().put("at", at).put("t", text));
            put(c, "previews.json", all.toString());
        } catch (Exception ignored) { }
    }
    static JSONObject previews(Context c) { try { String s = get(c, "previews.json"); return s == null ? new JSONObject() : new JSONObject(s); } catch (Exception e) { return new JSONObject(); } }

    static void wipe(Context c) { File[] fs = dir(c).listFiles(); if (fs != null) for (File f : fs) f.delete(); }

    static String fmtDur(double d) { long s = Math.max(0, Math.round(d)); return (s / 60) + ":" + String.format(java.util.Locale.ROOT, "%02d", s % 60); }
    /** Текст превью, как prevOf на сайте. */
    static String previewOf(JSONObject pl) {
        if (pl == null) return "";
        JSONObject f = pl.optJSONObject("f");
        if (f != null && "voice".equals(f.optString("vk"))) return "🎤 Голосовое " + fmtDur(f.optDouble("d", 0));
        if (f != null && "round".equals(f.optString("vk"))) return "⭕ Видеосообщение";
        String t = pl.optString("t", "");
        if (f == null) return t;
        String cap = pl.optString("caption", "");
        return fileLabel(f, cap.isEmpty() ? t : cap);
    }
    /** v14: «🖼 Фото», «🎥 Видео», «📎 имя» и « · подпись», если она есть. */
    static String fileLabel(JSONObject f, String cap) {
        String n = f.optString("n", "");
        String base = ChatActivity.isImage(f) || f.optString("m", "").startsWith("image/") ? "🖼 Фото"
                : ChatActivity.avKind(f) == 2 ? "🎥 Видео" : "📎 " + (n.isEmpty() ? "Файл" : n);
        cap = cap == null ? "" : cap.trim();
        return cap.isEmpty() ? base : base + " · " + cap;
    }
}
