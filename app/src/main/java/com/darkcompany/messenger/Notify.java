package com.darkcompany.messenger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

/** Уведомления о новых сообщениях — как notifyMsg() сайта: заголовок — чат, текст «Имя: текст» (или «Новое сообщение», если
 *  «Показывать текст» выключено), голосовое/кружок/фото/файл — подписи сайта. По одному уведомлению на чат (до 6 строк), группа «Сообщения». */
final class Notify {
    static final String CH = "messages2", CH_BG = "background", GROUP = "chats";
    private Notify() { }
    static android.content.SharedPreferences sp(Context c) { return c.getSharedPreferences("ui", 0); }
    static boolean enabled(Context c) { return sp(c).getBoolean("notify", true); }
    static void setEnabled(Context c, boolean on) { sp(c).edit().putBoolean("notify", on).apply(); if (on) NotifyService.start(c); else { NotifyService.stop(c); cancelAll(c); } }
    static boolean showText(Context c) { return sp(c).getBoolean("notify_text", true); }
    static void setShowText(Context c, boolean on) { sp(c).edit().putBoolean("notify_text", on).apply(); }
    static boolean allowed(Context c) {
        return Build.VERSION.SDK_INT < 33 || c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    static void channels(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class); if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(CH, "Сообщения", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("Новые сообщения в чатах"); ch.enableVibration(true);
        nm.createNotificationChannel(ch); // звук — системный по умолчанию; меняется в настройках канала (строка «Звук»)
        NotificationChannel bg = new NotificationChannel(CH_BG, "Работа в фоне", NotificationManager.IMPORTANCE_MIN);
        bg.setDescription("Постоянное уведомление, чтобы сообщения приходили, когда приложение закрыто"); bg.setShowBadge(false);
        nm.createNotificationChannel(bg);
        nm.deleteNotificationChannel("messages"); // старый канал v7
    }
    /** Значение строки «Звук»: «Системный» / «Без звука» / название мелодии канала. */
    static String soundLabel(Context c) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        channels(c);
        NotificationChannel ch = nm == null ? null : nm.getNotificationChannel(CH);
        if (ch == null) return "Системный";
        if (ch.getImportance() < NotificationManager.IMPORTANCE_DEFAULT) return "Без звука";
        android.net.Uri u = ch.getSound();
        if (u == null) return "Без звука";
        if (u.equals(android.provider.Settings.System.DEFAULT_NOTIFICATION_URI)) return "Системный";
        try { android.media.Ringtone r = android.media.RingtoneManager.getRingtone(c, u); if (r != null) return r.getTitle(c); } catch (Exception ignored) { }
        return "Системный";
    }
    static Intent soundSettings(Context c) {
        channels(c);
        return new Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, c.getPackageName()).putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, CH);
    }

    /** notifText() сайта. */
    static String text(JSONObject pl) {
        if (pl == null) return "Новое сообщение";
        JSONObject f = pl.optJSONObject("f");
        if (f != null && "voice".equals(f.optString("vk"))) return "🎤 Голосовое сообщение " + Cache.fmtDur(f.optDouble("d", 0));
        if (f != null && "round".equals(f.optString("vk"))) return "⭕ Видеосообщение";
        String t = pl.optString("t", "");
        return !t.isEmpty() ? t : f != null ? (ChatActivity.isImage(f) ? "Фото" : "Файл") : "Новое сообщение";
    }

    /** Строки по чатам (живут, пока уведомление не снято). */
    private static final java.util.Map<String, java.util.List<String>> lines = new java.util.HashMap<>();

    static synchronized void message(Context c, String chatId, String title, String kind, String peerId, String who, String text) {
        if (!enabled(c) || !allowed(c)) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class); if (nm == null) return;
        channels(c);
        String body = showText(c) ? ((who == null || who.isEmpty() ? "" : who + ": ") + (text == null || text.isEmpty() ? "Новое сообщение" : text)) : "Новое сообщение";
        if (body.length() > 200) body = body.substring(0, 200);
        java.util.List<String> l = lines.computeIfAbsent(chatId, k -> new java.util.ArrayList<>());
        l.add(body); while (l.size() > 6) l.remove(0);
        Intent i = new Intent(c, ChatActivity.class).putExtra("chat_id", chatId).putExtra("title", title).putExtra("kind", kind);
        if (peerId != null) i.putExtra("peer_id", peerId);
        PendingIntent pi = android.app.TaskStackBuilder.create(c).addNextIntent(new Intent(c, ChatsActivity.class)).addNextIntent(i)
                .getPendingIntent(chatId.hashCode(), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.InboxStyle st = new Notification.InboxStyle(); for (String s : l) st.addLine(s);
        if (l.size() > 1) st.setSummaryText(l.size() + " " + plural(l.size()));
        Notification n = new Notification.Builder(c, CH)
                .setSmallIcon(R.drawable.ic_stat_msg).setColor(c.getColor(R.color.web_color_primary))
                .setContentTitle(title).setContentText(body).setStyle(st).setNumber(l.size())
                .setCategory(Notification.CATEGORY_MESSAGE).setGroup(GROUP).setShowWhen(true).setWhen(System.currentTimeMillis())
                .setVisibility(showText(c) ? Notification.VISIBILITY_PRIVATE : Notification.VISIBILITY_PUBLIC)
                .setAutoCancel(true).setContentIntent(pi).build();
        nm.notify(chatId.hashCode(), n);
        if (lines.size() > 1) {
            Notification sum = new Notification.Builder(c, CH).setSmallIcon(R.drawable.ic_stat_msg).setColor(c.getColor(R.color.web_color_primary))
                    .setContentTitle("Мессенджер").setContentText("Новые сообщения в " + lines.size() + " чатах").setGroup(GROUP).setGroupSummary(true)
                    .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN).setAutoCancel(true)
                    .setContentIntent(PendingIntent.getActivity(c, 1, new Intent(c, ChatsActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)).build();
            nm.notify(1, sum);
        }
    }
    private static String plural(int n) { int m = n % 10, h = n % 100; return m == 1 && h != 11 ? "сообщение" : m >= 2 && m <= 4 && (h < 12 || h > 14) ? "сообщения" : "сообщений"; }

    /** Чат открыт / прочитан — снимаем его уведомление. */
    static synchronized void cancel(Context c, String chatId) {
        lines.remove(chatId);
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.cancel(chatId.hashCode());
        if (lines.size() <= 1) nm.cancel(1);
    }
    static synchronized void cancelAll(Context c) {
        lines.clear();
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) for (android.service.notification.StatusBarNotification s : nm.getActiveNotifications()) if (s.getId() != NotifyService.FG_ID) nm.cancel(s.getId());
    }
}
