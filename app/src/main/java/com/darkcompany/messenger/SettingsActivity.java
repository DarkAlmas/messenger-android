package com.darkcompany.messenger;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.Collections;

/** Настройки: профиль, тема, уведомления, шифрование, выход, версия. */
public class SettingsActivity extends AppCompatActivity {
    private LinearLayout col;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!Api.get(this).hasSession()) { finish(); return; }
        setTitle("Настройки");
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 24));
        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        setContentView(sv);
        Ui.fitSystemBars(this, sv);
    }

    @Override protected void onResume() { super.onResume(); build(); new Thread(() -> { if (Avatars.refresh(this, Collections.singleton(Api.get(this).uid()), 0)) runOnUiThread(this::build); }).start(); }
    @Override public boolean onSupportNavigateUp() { finish(); return true; }

    private void build() {
        col.removeAllViews();
        String uid = Api.get(this).uid();
        org.json.JSONObject p = Avatars.merged(this, uid);
        String dn = p.isNull("display_name") ? "" : p.optString("display_name", "");
        String un = p.optString("username", "");
        // Карточка профиля
        LinearLayout card = new LinearLayout(this);
        card.setGravity(android.view.Gravity.CENTER_VERTICAL);
        card.setPadding(Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 20), Ui.dp(this, 16));
        card.addView(Avatars.view(this, uid, dn.isEmpty() ? un : dn, 64), new LinearLayout.LayoutParams(Ui.dp(this, 64), Ui.dp(this, 64)));
        LinearLayout tc = new LinearLayout(this); tc.setOrientation(LinearLayout.VERTICAL); tc.setPadding(Ui.dp(this, 16), 0, 0, 0);
        TextView t1 = Ui.text(this, dn.isEmpty() ? (un.isEmpty() ? "Мой профиль" : un) : dn, 20); t1.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        TextView t2 = Ui.text(this, (un.isEmpty() ? "" : "@" + un + " · ") + "изменить профиль", 14); t2.setTextColor(Ui.attr(this, androidx.appcompat.R.attr.colorPrimary));
        tc.addView(t1); tc.addView(t2);
        card.addView(tc, new LinearLayout.LayoutParams(0, -2, 1f));
        card.setOnClickListener(v -> startActivity(new Intent(this, ProfileActivity.class)));
        card.setBackgroundResource(ripple());
        col.addView(card);

        section("Оформление");
        row("🎨", "Тема", Avatars.THEMES[Avatars.themeIndex(this)], v ->
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("Тема")
                        .setSingleChoiceItems(Avatars.THEMES, Avatars.themeIndex(this), (d, w) -> {
                            Avatars.prefs(this).edit().putInt("theme", w).apply();
                            d.dismiss();
                            Avatars.applyTheme(this);
                        }).show());

        section("Уведомления");
        row("🔔", "Системные настройки уведомлений", "Push-уведомления пока не поддерживаются: новые сообщения приходят, пока приложение открыто", v -> {
            Intent i = new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName());
            try { startActivity(i); } catch (Exception e) { Ui.toast(this, "Не удалось открыть настройки"); }
        });

        section("Шифрование и безопасность");
        String dev = "—";
        try { dev = E2e.get(this).deviceId(); } catch (Exception ignored) { }
        String st = E2e.lastError == null ? "✅ Ключи этого устройства опубликованы" : "⚠ " + E2e.lastError;
        row("🔒", "Сквозное шифрование", st + "\nВсе сообщения и файлы шифруются на телефоне (Signal Protocol: X3DH/PQXDH, Double Ratchet, Sender Keys в группах). Сервер видит только шифротекст.", null);
        row("📱", "Это устройство", dev == null ? "—" : dev.substring(0, Math.min(8, dev.length())) + "…  · код безопасности сверяйте в чате по QR (кнопка «Код безопасности»)", null);

        section("Аккаунт");
        row("🚪", "Выйти", "Ключи этого устройства будут удалены", v ->
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("Выйти из аккаунта?")
                        .setMessage("Ключи шифрования этого устройства будут удалены. После входа телефон станет новым устройством.")
                        .setPositiveButton("Выйти", (d, w) -> ChatsActivity.logout(this)).setNegativeButton("Отмена", null).show());

        section("О приложении");
        String ver = "?";
        try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
        row("ℹ️", "Версия", ver + " · сборка " + BuildConfig.BUILD_TIME, null);
    }

    private int ripple() {
        android.util.TypedValue tv = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        return tv.resourceId;
    }

    private void section(String s) {
        TextView t = Ui.text(this, s, 13);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setTextColor(Ui.attr(this, androidx.appcompat.R.attr.colorPrimary));
        t.setPadding(Ui.dp(this, 20), Ui.dp(this, 18), Ui.dp(this, 20), Ui.dp(this, 6));
        col.addView(t);
    }

    private void row(String icon, String title, String sub, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(android.view.Gravity.CENTER_VERTICAL);
        r.setPadding(Ui.dp(this, 20), Ui.dp(this, 12), Ui.dp(this, 20), Ui.dp(this, 12));
        TextView ic = Ui.text(this, icon, 20);
        r.addView(ic, new LinearLayout.LayoutParams(Ui.dp(this, 40), -2));
        LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL);
        c.addView(Ui.text(this, title, 16));
        if (sub != null) { TextView s = Ui.text(this, sub, 13); s.setTextColor(Ui.attr(this, com.google.android.material.R.attr.colorOnSurfaceVariant)); c.addView(s); }
        r.addView(c, new LinearLayout.LayoutParams(0, -2, 1f));
        if (click != null) { r.setOnClickListener(click); r.setBackgroundResource(ripple()); }
        col.addView(r);
    }
}
