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
    private TextView msg, formTitle, pwHint;
    private LinearLayout userBox;

    private EditText siteInput(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint); e.setSingleLine(true); e.setTextSize(16);
        e.setTextColor(Site.text(this)); e.setHintTextColor(Site.text2(this));
        android.graphics.drawable.GradientDrawable g = Site.round(Site.container(this), Ui.dp(this, 12));
        g.setStroke(Math.max(1, Ui.dp(this, 1)), Site.col(this, R.color.site_divider));
        e.setBackground(g);
        e.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0);
        return e;
    }
    private TextView caption(String s) { TextView t = Site.label(this, s, 13, Site.text2(this), false); t.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 6)); return t; }
    private LinearLayout.LayoutParams fieldLp() { return new LinearLayout.LayoutParams(-1, Ui.dp(this, 50)); }

    @Override
    protected void onCreate(Bundle b) {
        Avatars.applyTheme(this); // тема (по умолчанию тёмная) — до создания окна
        super.onCreate(b);
        if (Api.get(this).hasSession()) {
            startActivity(new Intent(this, ChatsActivity.class));
            finish();
            return;
        }
        // Как #auth .card на сайте: «Мессенджер», «Почта и пароль», подписи над полями, синяя кнопка-пилюля
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Site.page(this));
        page.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Site.round(Site.surface(this), Ui.dp(this, 24)));
        root.setElevation(Ui.dp(this, 1));
        root.setPadding(Ui.dp(this, 28), Ui.dp(this, 28), Ui.dp(this, 28), Ui.dp(this, 28));
        page.addView(root, new LinearLayout.LayoutParams(-1, -2));
        TextView ttl = Site.label(this, "Мессенджер", 26, Site.text(this), true);
        root.addView(ttl);
        formTitle = Site.label(this, "Почта и пароль", 18, Site.text(this), true);
        formTitle.setPadding(0, Ui.dp(this, 24), 0, Ui.dp(this, 4));
        root.addView(formTitle);
        email = siteInput("name@example.com");
        email.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        password = siteInput("");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        username = siteInput("");
        root.addView(caption("Почта"));
        root.addView(email, fieldLp());
        root.addView(caption("Пароль"));
        root.addView(password, fieldLp());
        pwHint = Site.label(this, "Не меньше 10 символов", 13, Site.text2(this), false); pwHint.setVisibility(android.view.View.GONE);
        root.addView(pwHint);
        userBox = new LinearLayout(this); userBox.setOrientation(LinearLayout.VERTICAL); userBox.setVisibility(android.view.View.GONE);
        userBox.addView(caption("Никнейм"));
        userBox.addView(username, fieldLp());
        userBox.addView(Site.label(this, "От 5 до 32 символов: латиница, цифры и _", 13, Site.text2(this), false));
        root.addView(userBox);
        action = new Button(this);
        action.setText("Войти"); action.setAllCaps(false); action.setTextSize(16); action.setTextColor(0xFFFFFFFF);
        action.setBackground(Site.round(Site.col(this, R.color.site_out), Ui.dp(this, 23)));
        action.setStateListAnimator(null);
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-1, Ui.dp(this, 48)); al.topMargin = Ui.dp(this, 18);
        root.addView(action, al);
        toggle = new Button(this, null, 0);
        toggle.setText("Нет аккаунта? Регистрация"); toggle.setAllCaps(false); toggle.setTextSize(15); toggle.setTextColor(Site.primary(this));
        toggle.setBackground(null); toggle.setGravity(android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL); toggle.setPadding(0, 0, 0, 0);
        root.addView(toggle, new LinearLayout.LayoutParams(-1, Ui.dp(this, 44)));
        msg = Site.label(this, "", 14, Site.text2(this), false);
        root.addView(msg, Ui.match());
        TextView qr = Site.label(this, "Уже вошли на телефоне? Войдите на другом устройстве: Настройки → «Войти на сайте (QR)».", 13, Site.text2(this), false);
        qr.setPadding(0, Ui.dp(this, 16), 0, 0);
        root.addView(qr, Ui.match());
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setFillViewport(true);
        sv.setBackgroundColor(Site.page(this));
        sv.addView(page);
        setContentView(sv);
        Ui.fitSystemBars(this, sv);

        toggle.setOnClickListener(v -> {
            register = !register;
            userBox.setVisibility(register ? android.view.View.VISIBLE : android.view.View.GONE);
            pwHint.setVisibility(register ? android.view.View.VISIBLE : android.view.View.GONE);
            formTitle.setText(register ? "Регистрация" : "Почта и пароль");
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
        if (register && !Pattern.matches("[a-z0-9_]{5,32}", u)) { msg.setText("Никнейм: от 5 до 32 символов: латиница, цифры и _"); return; }
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
                        userBox.setVisibility(android.view.View.GONE); pwHint.setVisibility(android.view.View.GONE); formTitle.setText("Почта и пароль");
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
