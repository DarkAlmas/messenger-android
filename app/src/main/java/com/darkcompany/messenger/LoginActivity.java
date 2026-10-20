package com.darkcompany.messenger;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

public class LoginActivity extends AppCompatActivity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private boolean register = false;
    private EditText email, password, username;
    private Button action, toggle;
    private TextView msg;

    @Override
    protected void onCreate(Bundle b) {
        Avatars.applyTheme(this); // тема (по умолчанию тёмная) — до создания окна
        super.onCreate(b);
        if (Api.get(this).hasSession()) {
            startActivity(new Intent(this, ChatsActivity.class));
            finish();
            return;
        }
        LinearLayout root = Ui.column(this);
        root.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        root.setPadding(Ui.dp(this, 24), Ui.dp(this, 56), Ui.dp(this, 24), Ui.dp(this, 24));
        root.addView(Ui.logo(this), new LinearLayout.LayoutParams(Ui.dp(this, 88), Ui.dp(this, 88)));
        TextView ttl = Ui.text(this, "Мессенджер", 30);
        ttl.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        ttl.setGravity(android.view.Gravity.CENTER);
        ttl.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 4));
        root.addView(ttl, Ui.match());
        TextView sub = Ui.text(this, "Быстрые и защищённые чаты", 14);
        sub.setGravity(android.view.Gravity.CENTER);
        sub.setTextColor(Ui.gray());
        sub.setPadding(0, 0, 0, Ui.dp(this, 24));
        root.addView(sub, Ui.match());
        email = Ui.input(this, "Почта");
        email.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        password = Ui.input(this, "Пароль (не короче 10 символов)");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        username = Ui.input(this, "Никнейм: a-z, 0-9, _ (3-32)");
        action = Ui.button(this, "Войти");
        toggle = Ui.textButton(this, "Нет аккаунта? Регистрация");
        msg = Ui.text(this, "", 14);
        msg.setGravity(android.view.Gravity.CENTER);
        msg.setPadding(0, Ui.dp(this, 12), 0, 0);
        TextView qr = Ui.text(this, "Уже вошли на телефоне? Войдите на другом устройстве: в списке чатов откройте меню ⋮ → «Войти на сайте (QR)».", 12);
        qr.setTextColor(Ui.gray());
        qr.setGravity(android.view.Gravity.CENTER);
        qr.setPadding(0, Ui.dp(this, 24), 0, 0);
        root.addView(Ui.field(email), Ui.spaced(this));
        root.addView(Ui.field(password), Ui.spaced(this));
        root.addView(Ui.field(username), Ui.spaced(this));
        ((android.view.View) username.getParent().getParent()).setVisibility(android.view.View.GONE);
        root.addView(action, Ui.spaced(this));
        root.addView(toggle, Ui.match());
        root.addView(msg, Ui.match());
        root.addView(qr, Ui.match());
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setFillViewport(true);
        sv.addView(root);
        setContentView(sv);
        Ui.fitSystemBars(this, sv); // edge-to-edge (Android 15+): не прятать контент под тулбаром/панелями

        toggle.setOnClickListener(v -> {
            register = !register;
            ((android.view.View) username.getParent().getParent()).setVisibility(register ? android.view.View.VISIBLE : android.view.View.GONE);
            action.setText(register ? "Зарегистрироваться" : "Войти");
            toggle.setText(register ? "Уже есть аккаунт? Войти" : "Нет аккаунта? Регистрация");
        });
        action.setOnClickListener(v -> submit());
    }

    private void submit() {
        final String e = email.getText().toString().trim();
        final String p = password.getText().toString();
        final String u = username.getText().toString().trim().toLowerCase();
        if (e.isEmpty() || p.isEmpty()) { msg.setText("Введите почту и пароль"); return; }
        if (register && p.length() < 10) { msg.setText("Пароль должен быть не короче 10 символов"); return; }
        if (register && !Pattern.matches("[a-z0-9_]{3,32}", u)) { msg.setText("Никнейм: латиница, цифры и _, от 3 до 32 символов"); return; }
        action.setEnabled(false);
        msg.setText("Подождите…");
        io.execute(() -> {
            try {
                Api api = Api.get(this);
                if (register) {
                    api.signUp(e, p, u);
                    runOnUiThread(() -> {
                        msg.setText("Письмо отправлено. Подтвердите почту и затем войдите по паролю (так нужно для входа по QR).");
                        register = false;
                        ((android.view.View) username.getParent().getParent()).setVisibility(android.view.View.GONE);
                        action.setText("Войти");
                        toggle.setText("Нет аккаунта? Регистрация");
                        action.setEnabled(true);
                    });
                } else {
                    api.signIn(e, p);
                    runOnUiThread(() -> {
                        startActivity(new Intent(this, ChatsActivity.class));
                        finish();
                    });
                }
            } catch (Exception ex) {
                runOnUiThread(() -> { msg.setText(Ui.err(ex)); action.setEnabled(true); });
            }
        });
    }
}
