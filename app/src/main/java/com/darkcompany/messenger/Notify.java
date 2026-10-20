package com.darkcompany.messenger;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Локальные уведомления о новых сообщениях (пока приложение запущено или свёрнуто). Текст сообщения сервер не знает — показываем имя чата. */
final class Notify {
    static final String CH = "messages";
    private Notify() { }
    static boolean enabled(Context c) { return c.getSharedPreferences("ui", 0).getBoolean("notify", true); }
    static void setEnabled(Context c, boolean on) { c.getSharedPreferences("ui", 0).edit().putBoolean("notify", on).apply(); }

    static void message(Context c, String chatId, String title, String kind, String peerId, String text) {
        if (!enabled(c)) return;
        if (Build.VERSION.SDK_INT >= 33 && c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        nm.createNotificationChannel(new NotificationChannel(CH, "Сообщения", NotificationManager.IMPORTANCE_HIGH));
        Intent i = new Intent(c, ChatActivity.class).putExtra("chat_id", chatId).putExtra("title", title).putExtra("kind", kind);
        if (peerId != null) i.putExtra("peer_id", peerId);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pi = PendingIntent.getActivity(c, chatId.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        android.app.Notification n = new android.app.Notification.Builder(c, CH)
                .setSmallIcon(android.R.drawable.sym_action_chat)
                .setContentTitle(title)
                .setContentText(text == null || text.isEmpty() ? "Новое сообщение" : text)
                .setAutoCancel(true).setContentIntent(pi).build();
        nm.notify(chatId.hashCode(), n);
    }
    static void cancel(Context c, String chatId) {
        NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(chatId.hashCode());
    }
}
