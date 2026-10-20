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

    /** notifText() сайта (+ v10: голосовое/кружок, пришедшие как файл «voice_….webm», подписываются как на сайте). */
    static String text(JSONObject pl) {
        if (pl == null) return "Новое сообщение";
        JSONObject f = pl.optJSONObject("f");
        String t = Avatars.str(pl, "t"), cap = Avatars.str(pl, "caption");
        if (f != null) {
            String n = Avatars.str(f, "n").toLowerCase(java.util.Locale.ROOT), m = Avatars.str(f, "m").toLowerCase(java.util.Locale.ROOT);
            String vk = Avatars.str(f, "vk");
            if ("voice".equals(vk) || (vk.isEmpty() && n.startsWith("voice_") && (m.isEmpty() || m.startsWith("audio") || n.endsWith(".webm") || n.endsWith(".ogg") || n.endsWith(".m4a"))))
                return "🎤 Голосовое сообщение" + (f.has("d") ? " " + Cache.fmtDur(f.optDouble("d", 0)) : "");
            if ("round".equals(vk) || (vk.isEmpty() && (n.startsWith("round_") || n.startsWith("video_note")))) return "⭕ Видеосообщение";
            String c = !cap.isEmpty() ? cap : t;
            return Cache.fileLabel(f, c);
        }
        // текст «Файл: voice_123.webm» (старые клиенты) — тоже голосовое
        java.util.regex.Matcher vm = java.util.regex.Pattern.compile("^Файл: (voice_|round_)[^\\s]*$").matcher(t);
        if (vm.matches()) return "voice_".equals(vm.group(1)) ? "🎤 Голосовое сообщение" : "⭕ Видеосообщение";
        return !t.isEmpty() ? t : "Новое сообщение";
    }

    static final String ACT_REPLY = "com.darkcompany.messenger.REPLY", ACT_READ = "com.darkcompany.messenger.READ", KEY_REPLY = "reply_text";

    /** Одна строка уведомления. */
    static final class Line { final String sender, name, text; final long at; Line(String s, String n, String t, long a) { sender = s; name = n; text = t; at = a; } }
    /** Строки по чатам (живут, пока уведомление не снято) и created_at последнего входящего (для «Прочитано»). */
    static final java.util.Map<String, java.util.List<Line>> lines = new java.util.HashMap<>();
    static final java.util.Map<String, String> upto = new java.util.HashMap<>();

    /** Совместимость со старым вызовом. */
    static void message(Context c, String chatId, String title, String kind, String peerId, String who, String text) {
        message(c, chatId, title, kind, peerId, peerId, who, text, null);
    }

    /** Уведомление как у сайта/PWA: «Мессенджер», круглый аватар отправителя, жирное имя, текст, «Ответить» и «Прочитано». */
    static synchronized void message(Context c, String chatId, String title, String kind, String peerId, String senderId, String who, String text, String createdAt) {
        if (!enabled(c) || !allowed(c)) return;
        NotificationManager nm = c.getSystemService(NotificationManager.class); if (nm == null) return;
        channels(c);
        boolean group = "group".equals(kind);
        String name = who == null || who.isEmpty() ? title : who;
        String body = showText(c) ? (text == null || text.isEmpty() ? "Новое сообщение" : text) : "Новое сообщение";
        if (body.length() > 300) body = body.substring(0, 300);
        java.util.List<Line> l = lines.computeIfAbsent(chatId, k -> new java.util.ArrayList<>());
        l.add(new Line(senderId, name, body, createdAt == null ? System.currentTimeMillis() : Ui.instant(createdAt)));
        while (l.size() > 6) l.remove(0);
        if (createdAt != null) upto.put(chatId, createdAt);
        nm.notify(chatId.hashCode(), build(c, chatId, title, kind, peerId, l, null));
        if (lines.size() > 1) {
            Notification sum = new Notification.Builder(c, CH).setSmallIcon(R.drawable.ic_stat_msg).setColor(c.getColor(R.color.web_color_primary))
                    .setContentTitle("Мессенджер").setContentText("Новые сообщения в " + lines.size() + " чатах").setGroup(GROUP).setGroupSummary(true)
                    .setGroupAlertBehavior(Notification.GROUP_ALERT_CHILDREN).setAutoCancel(true)
                    .setContentIntent(PendingIntent.getActivity(c, 1, new Intent(c, ChatsActivity.class), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE)).build();
            nm.notify(1, sum);
        }
    }

    static android.app.Person person(Context c, String uid, String name) {
        android.app.Person.Builder b = new android.app.Person.Builder().setName(name == null || name.isEmpty() ? "?" : name);
        if (uid != null) b.setKey(uid);
        try { b.setIcon(android.graphics.drawable.Icon.createWithBitmap(Avatars.bitmap(c, uid, name, Ui.dp(c, 48)))); } catch (Throwable ignored) { }
        return b.build();
    }

    static Notification build(Context c, String chatId, String title, String kind, String peerId, java.util.List<Line> l, String status) {
        boolean group = "group".equals(kind);
        Intent i = new Intent(c, ChatActivity.class).putExtra("chat_id", chatId).putExtra("title", title).putExtra("kind", kind);
        if (peerId != null) i.putExtra("peer_id", peerId);
        PendingIntent pi = android.app.TaskStackBuilder.create(c).addNextIntent(new Intent(c, ChatsActivity.class)).addNextIntent(i)
                .getPendingIntent(chatId.hashCode(), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        android.app.Person me = new android.app.Person.Builder().setName("Вы").setKey(Api.get(c).uid()).build();
        Notification.MessagingStyle st = new Notification.MessagingStyle(me);
        if (group) st.setConversationTitle(title);
        st.setGroupConversation(group);
        java.util.Map<String, android.app.Person> ps = new java.util.HashMap<>();
        Line last = null;
        for (Line x : l) {
            String k = x.sender + "|" + x.name;
            android.app.Person p = "me".equals(x.sender) ? null : ps.computeIfAbsent(k, kk -> person(c, x.sender, x.name));
            st.addMessage(new Notification.MessagingStyle.Message(x.text, x.at, p));
            last = x;
        }
        Intent ri = new Intent(c, NotifyActions.class).setAction(ACT_REPLY).putExtra("chat_id", chatId).putExtra("kind", kind).putExtra("title", title);
        if (peerId != null) ri.putExtra("peer_id", peerId);
        PendingIntent rp = PendingIntent.getBroadcast(c, chatId.hashCode() * 31 + 1, ri, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        android.app.RemoteInput rin = new android.app.RemoteInput.Builder(KEY_REPLY).setLabel("Сообщение").build();
        Notification.Action reply = new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(c, R.drawable.ic_stat_msg), "Ответить", rp)
                .addRemoteInput(rin).setAllowGeneratedReplies(false).setSemanticAction(Notification.Action.SEMANTIC_ACTION_REPLY).build();
        Intent di = new Intent(c, NotifyActions.class).setAction(ACT_READ).putExtra("chat_id", chatId);
        PendingIntent dp = PendingIntent.getBroadcast(c, chatId.hashCode() * 31 + 2, di, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Action read = new Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(c, R.drawable.ic_stat_msg), "Прочитано", dp)
                .setSemanticAction(Notification.Action.SEMANTIC_ACTION_MARK_AS_READ).build();
        Notification.Builder nb = new Notification.Builder(c, CH)
                .setSmallIcon(R.drawable.ic_stat_msg).setColor(c.getColor(R.color.web_color_primary))
                .setContentTitle(last == null ? title : group ? title : last.name).setContentText(status != null ? status : last == null ? "" : last.text)
                .setStyle(st).setNumber(l.size())
                .setCategory(Notification.CATEGORY_MESSAGE).setGroup(GROUP).setShowWhen(true).setWhen(last == null ? System.currentTimeMillis() : last.at)
                .setVisibility(showText(c) ? Notification.VISIBILITY_PRIVATE : Notification.VISIBILITY_PUBLIC)
                .setAutoCancel(true).setContentIntent(pi).addAction(reply).addAction(read);
        if (status != null) nb.setSubText(status).setOnlyAlertOnce(true);
        if (last != null && !"me".equals(last.sender)) {
            try { nb.setLargeIcon(Avatars.bitmap(c, last.sender, last.name, Ui.dp(c, 48))); } catch (Throwable ignored) { }
        }
        return nb.build();
    }

    /** После ответа из уведомления: свой ответ в ленте уведомления (тихо) или ошибка. */
    static synchronized void replied(Context c, String chatId, String title, String kind, String peerId, String text, String error) {
        NotificationManager nm = c.getSystemService(NotificationManager.class); if (nm == null) return;
        java.util.List<Line> l = lines.computeIfAbsent(chatId, k -> new java.util.ArrayList<>());
        if (error == null) l.add(new Line("me", "Вы", text, System.currentTimeMillis()));
        nm.notify(chatId.hashCode(), build(c, chatId, title, kind, peerId, l, error));
    }

    /** Чат открыт / прочитан — снимаем его уведомление. */
    static synchronized void cancel(Context c, String chatId) {
        lines.remove(chatId); upto.remove(chatId);
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm == null) return;
        nm.cancel(chatId.hashCode());
        if (lines.size() <= 1) nm.cancel(1);
    }
    static synchronized void cancelAll(Context c) {
        lines.clear(); upto.clear();
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm != null) for (android.service.notification.StatusBarNotification s : nm.getActiveNotifications()) if (s.getId() != NotifyService.FG_ID) nm.cancel(s.getId());
    }
}
