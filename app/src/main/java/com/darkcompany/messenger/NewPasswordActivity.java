package com.darkcompany.messenger;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/** v12: экран «Новый пароль» после ссылки из письма (type=recovery): PUT /auth/v1/user {password}, затем обычный вход. */
public class NewPasswordActivity extends AppCompatActivity {
    private EditText p1, p2;
    private TextView save, msg;

    /** null — всё в порядке, иначе текст ошибки (как на сайте: не меньше 10 символов). */
    static String check(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return "Введите новый пароль два раза";
        if (a.length() < 10) return "Пароль должен быть не короче 10 символов";
        if (!a.equals(b)) return "Пароли не совпадают";
        return null;
    }

    @Override
    protected void onCreate(Bundle b) {
        Avatars.applyTheme(this);
        super.onCreate(b);
        if (!Api.get(this).hasSession()) { startActivity(new Intent(this, LoginActivity.class)); finish(); return; }
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Site.round(Site.surface(this), Ui.dp(this, 20)));
        root.setElevation(Ui.dp(this, 8));
        root.setPadding(Ui.dp(this, 24), Ui.dp(this, 24), Ui.dp(this, 24), Ui.dp(this, 24));
        page.addView(root, new LinearLayout.LayoutParams(-1, -2));
        TextView ttl = Site.label(this, "Новый пароль", 24f, Site.text(this), 700);
        LinearLayout.LayoutParams h1 = new LinearLayout.LayoutParams(-1, -2); h1.bottomMargin = Ui.dp(this, 8); root.addView(ttl, h1);
        String em = Api.get(this).email();
        root.addView(Site.label(this, em.isEmpty() ? "Придумайте новый пароль" : "Аккаунт: " + em, 14f, Site.text2(this), 400));
        root.addView(Site.caption(this, "Новый пароль"));
        p1 = Site.input(this, ""); p1.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(p1, new LinearLayout.LayoutParams(-1, -2));
        root.addView(Site.caption(this, "Повторите пароль"));
        p2 = Site.input(this, ""); p2.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(p2, new LinearLayout.LayoutParams(-1, -2));
        root.addView(Site.label(this, "Не меньше 10 символов", 12f, Site.text2(this), 400));
        save = Site.button(this, "Сохранить пароль");
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-1, Ui.dp(this, 46)); al.topMargin = Ui.dp(this, 16);
        root.addView(save, al);
        TextView cancel = Site.link(this, "Отмена");
        root.addView(cancel, new LinearLayout.LayoutParams(-2, Ui.dp(this, 44)));
        msg = Site.label(this, "", 14f, Site.text2(this), 400);
        msg.setMinHeight(Ui.dp(this, 36)); msg.setPadding(0, Ui.dp(this, 6), 0, 0);
        root.addView(msg, Ui.match());
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setFillViewport(true); sv.setBackgroundColor(Site.page(this)); sv.addView(page);
        setContentView(sv);
        Ui.fitSystemBars(this, sv);
        getWindow().setStatusBarColor(Site.page(this));
        save.setOnClickListener(v -> submit());
        cancel.setOnClickListener(v -> new Thread(() -> {
            Api.get(this).logout();
            runOnUiThread(() -> { startActivity(new Intent(this, LoginActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)); finish(); });
        }).start());
    }

    private void submit() {
        final String a = p1.getText().toString(), c = p2.getText().toString();
        String bad = check(a, c);
        if (bad != null) { msg.setText(bad); return; }
        save.setEnabled(false); msg.setText("Подождите…");
        new Thread(() -> {
            try {
                Api api = Api.get(this);
                api.updatePassword(a);
                runOnUiThread(() -> msg.setText("Пароль изменён, входим…"));
                api.reLoginWithPassword(a);
                runOnUiThread(() -> { startActivity(new Intent(this, ChatsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)); finish(); });
            } catch (Exception e) {
                String t = Ui.err(e);
                if (t != null && t.contains("same_password")) t = "Новый пароль должен отличаться от старого";
                final String f = t;
                runOnUiThread(() -> { msg.setText(f); save.setEnabled(true); });
            }
        }, "new-password").start();
    }
}
