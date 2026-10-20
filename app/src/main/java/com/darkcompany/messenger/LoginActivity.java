package com.darkcompany.messenger;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
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
    private TextView action, toggle, qrStatus, qrTimer, qrEmpty, qrRetry;
    private android.widget.FrameLayout qrBox;
    private android.widget.ImageView qrImg;
    private TextView msg, formTitle, pwHint;
    private LinearLayout userBox, googleSep;
    private TextView googleBtn;
    void showGoogle(boolean on) { int v = on ? android.view.View.VISIBLE : android.view.View.GONE; googleSep.setVisibility(v); googleBtn.setVisibility(v); }

    /** Вход через Google: PKCE (verifier только в памяти приложения/prefs), браузер или Custom Tabs — не WebView (Google его блокирует). */
    private void google() {
        googleBtn.setEnabled(false); googleBtn.setAlpha(0.6f);
        io.execute(() -> {
            if (Api.get(this).beforeSignIn()) { runOnUiThread(this::openChats); return; }
            try {
                String ver = Api.pkceVerifier();
                AuthCallbackActivity.saveVerifier(this, ver);
                String url = Api.googleAuthorizeUrl(Api.pkceChallenge(ver));
                runOnUiThread(() -> {
                    try { startActivity(customTab(url)); }
                    catch (Exception e) { msg.setText("Не удалось открыть браузер для входа через Google"); }
                    googleBtn.setEnabled(true); googleBtn.setAlpha(1f);
                });
            } catch (Exception e) {
                runOnUiThread(() -> { msg.setText("Не удалось войти через Google: " + Ui.err(e)); googleBtn.setEnabled(true); googleBtn.setAlpha(1f); });
            }
        });
    }
    /** Intent Custom Tabs без библиотеки androidx.browser: ACTION_VIEW + EXTRA_SESSION (null); без поддержки — обычный браузер. */
    static Intent customTab(String url) {
        Intent i = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
        Bundle bx = new Bundle(); bx.putBinder("android.support.customtabs.extra.SESSION", null);
        i.putExtras(bx);
        i.putExtra("android.support.customtabs.extra.SHARE_STATE", 2);
        i.addCategory(Intent.CATEGORY_BROWSABLE);
        return i;
    }
    private void openChats() { startActivity(new Intent(this, ChatsActivity.class)); finish(); }


    @Override
    protected void onCreate(Bundle b) {
        Avatars.applyTheme(this); // тема (по умолчанию тёмная) — до создания окна
        super.onCreate(b);
        if (Api.get(this).hasSession()) {
            startActivity(new Intent(this, ChatsActivity.class));
            finish();
            return;
        }
        // #auth (padding 16) → .card (surface, r20, padding 24, тень) → h1 24px/700 → «Вход по QR» → форма «Почта и пароль»
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(Site.round(Site.surface(this), Ui.dp(this, 20)));
        root.setElevation(Ui.dp(this, 8));
        root.setPadding(Ui.dp(this, 24), Ui.dp(this, 24), Ui.dp(this, 24), Ui.dp(this, 24));
        page.addView(root, new LinearLayout.LayoutParams(-1, -2));
        TextView ttl = Site.label(this, "Мессенджер", 24f, Site.text(this), 700);
        LinearLayout.LayoutParams h1 = new LinearLayout.LayoutParams(-1, -2); h1.bottomMargin = Ui.dp(this, 16); root.addView(ttl, h1);
        // Вход по QR: этот телефон показывает код, другой вошедший телефон сканирует («Войти на сайте (QR)»)
        TextView qh = Site.label(this, "Вход по QR", 17f, Site.text(this), 700); 
        LinearLayout.LayoutParams h2 = new LinearLayout.LayoutParams(-1, -2); h2.bottomMargin = Ui.dp(this, 10); root.addView(qh, h2);
        qrBox = new android.widget.FrameLayout(this);
        qrBox.setBackground(Site.round(Site.container(this), Ui.dp(this, 14)));
        qrBox.setPadding(Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
        qrImg = new android.widget.ImageView(this);
        qrBox.addView(qrImg, new android.widget.FrameLayout.LayoutParams(-1, -1));
        qrEmpty = Site.label(this, "Загрузка…", 14f, Site.text2(this), 400); qrEmpty.setGravity(android.view.Gravity.CENTER);
        qrBox.addView(qrEmpty, new android.widget.FrameLayout.LayoutParams(-1, -1));
        root.addView(qrBox, new LinearLayout.LayoutParams(Ui.dp(this, 229), Ui.dp(this, 229)));
        qrStatus = Site.label(this, "Загрузка QR…", 14f, Site.text2(this), 400);
        Site.lh(qrStatus, 18.9f); qrStatus.setMinHeight(Ui.dp(this, 112)); qrStatus.setGravity(android.view.Gravity.TOP);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2); sl.topMargin = Ui.dp(this, 8); root.addView(qrStatus, sl);
        qrTimer = Site.label(this, "", 14f, Site.text2(this), 400);
        LinearLayout.LayoutParams tml = new LinearLayout.LayoutParams(-1, Ui.dp(this, 18)); tml.topMargin = Ui.dp(this, 4); root.addView(qrTimer, tml);
        qrRetry = Site.button(this, "Обновить QR"); qrRetry.setTextSize(15); qrRetry.setVisibility(android.view.View.GONE);
        qrRetry.setOnClickListener(v -> { qrFails = 0; startQr(); });
        LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-2, Ui.dp(this, 42)); rl.topMargin = Ui.dp(this, 5); root.addView(qrRetry, rl);

        formTitle = Site.label(this, "Почта и пароль", 17f, Site.text(this), 700);
        LinearLayout.LayoutParams h3 = new LinearLayout.LayoutParams(-1, -2); h3.topMargin = Ui.dp(this, 24); h3.bottomMargin = Ui.dp(this, 10); root.addView(formTitle, h3);
        email = Site.input(this, "name@example.com");
        email.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        password = Site.input(this, "");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        username = Site.input(this, "");
        root.addView(Site.caption(this, "Почта"));
        root.addView(email, new LinearLayout.LayoutParams(-1, -2));
        root.addView(Site.caption(this, "Пароль"));
        root.addView(password, new LinearLayout.LayoutParams(-1, -2));
        pwHint = Site.label(this, "Не меньше 10 символов", 12f, Site.text2(this), 400); pwHint.setVisibility(android.view.View.GONE);
        root.addView(pwHint);
        userBox = new LinearLayout(this); userBox.setOrientation(LinearLayout.VERTICAL); userBox.setVisibility(android.view.View.GONE);
        userBox.addView(Site.caption(this, "Никнейм"));
        userBox.addView(username, new LinearLayout.LayoutParams(-1, -2));
        userBox.addView(Site.label(this, "От 5 до 32 символов: латиница, цифры и _", 12f, Site.text2(this), 400));
        root.addView(userBox);
        action = Site.button(this, "Войти");
        LinearLayout.LayoutParams al = new LinearLayout.LayoutParams(-1, Ui.dp(this, 46)); al.topMargin = Ui.dp(this, 16);
        root.addView(action, al);
        // v11: «или» + «Войти через Google» (как #googleSep/#googleBtn сайта), видны только если /auth/v1/settings → external.google
        googleSep = Site.orSep(this); googleSep.setVisibility(android.view.View.GONE);
        LinearLayout.LayoutParams gsl = new LinearLayout.LayoutParams(-1, -2); gsl.topMargin = gsl.bottomMargin = Ui.dp(this, 12);
        root.addView(googleSep, gsl);
        googleBtn = Site.googleButton(this); googleBtn.setVisibility(android.view.View.GONE);
        root.addView(googleBtn, new LinearLayout.LayoutParams(-1, Ui.dp(this, 40)));
        googleBtn.setOnClickListener(v -> google());
        boolean cached = getSharedPreferences("ui", 0).getBoolean("google_on", false);
        showGoogle(cached);
        io.execute(() -> { Boolean en = Api.get(this).googleEnabled(); if (en != null) { getSharedPreferences("ui", 0).edit().putBoolean("google_on", en).apply(); runOnUiThread(() -> showGoogle(en)); } });
        String gErr = getIntent() == null ? null : getIntent().getStringExtra("google_error");
        toggle = Site.link(this, "Нет аккаунта? Регистрация");
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, Ui.dp(this, 44)); tl.topMargin = Ui.dp(this, 8);
        root.addView(toggle, tl);
        msg = Site.label(this, "", 14f, Site.text2(this), 400);
        msg.setMinHeight(Ui.dp(this, 36)); msg.setPadding(0, Ui.dp(this, 6), 0, 0);
        root.addView(msg, Ui.match());
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setFillViewport(true);
        sv.setBackgroundColor(Site.page(this));
        sv.addView(page);
        setContentView(sv);
        Ui.fitSystemBars(this, sv);
        getWindow().setStatusBarColor(Site.page(this));
        startQr();
        if (gErr != null) msg.setText(gErr);

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
                    if (!api.beforeSignIn()) api.signIn(e, p);
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

    // ---------- вход по QR (как startQr/qrFail на сайте: qr-create → QR → qr-poll каждые 3 с) ----------
    private static final String QR_TEXT = "Откройте приложение на другом телефоне, где вы уже вошли: Настройки → «Войти на сайте (QR)» и отсканируйте код. Входите, только если сами только что открыли этот QR.";
    private final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
    private int qrGen = 0, qrFails = 0;
    private volatile boolean done = false;
    private void qrState(String kind, String status, String timer) {
        boolean ok = kind.equals("ok");
        qrBox.setBackground(Site.round(ok ? 0xFFFFFFFF : Site.container(this), Ui.dp(this, 14)));
        qrImg.setVisibility(ok ? android.view.View.VISIBLE : android.view.View.GONE);
        qrEmpty.setVisibility(ok ? android.view.View.GONE : android.view.View.VISIBLE);
        qrEmpty.setText(kind.equals("loading") ? "Загрузка…" : "⚠");
        qrStatus.setText(status); qrTimer.setText(timer == null ? "" : timer);
        qrRetry.setVisibility(kind.equals("error") ? android.view.View.VISIBLE : android.view.View.GONE);
    }
    private void stopQr() { qrGen++; h.removeCallbacksAndMessages(null); }
    private void qrFail(Exception e) {
        stopQr(); qrFails++;
        int code = e instanceof Api.HttpError ? ((Api.HttpError) e).code : 0;
        String reason = code == 429 ? "Слишком много попыток, попробуйте позже." : code > 0 ? "Сервер QR временно недоступен." : "Нет соединения с сервером.";
        if (qrFails > 5) { qrState("error", reason + " Автоповтор остановлен — нажмите «Обновить QR».", null); return; }
        long wait = Math.min(5000L << (qrFails - 1), 120000L), until = System.currentTimeMillis() + wait;
        final int gen = qrGen;
        Runnable tick = new Runnable() { @Override public void run() {
            if (gen != qrGen) return;
            long sec = Math.max(0, (until - System.currentTimeMillis() + 999) / 1000);
            qrState("error", reason + " Повтор через " + sec + " с (попытка " + qrFails + " из 5).", null);
            if (sec <= 0) startQr(); else h.postDelayed(this, 1000);
        } };
        tick.run();
    }
    private void startQr() {
        stopQr();
        if (done) return;
        final int gen = ++qrGen;
        qrState("loading", "Получаем QR…", null);
        io.execute(() -> {
            try {
                org.json.JSONObject q = Api.get(this).fn("qr-create", null);
                String token = q.optString("token");
                if (!token.matches("[0-9a-f]{64}")) throw new java.io.IOException("bad token");
                String payload = new org.json.JSONObject().put("t", "msgr-login").put("v", 1).put("token", token).toString();
                android.graphics.Bitmap bm = new com.journeyapps.barcodescanner.BarcodeEncoder().encodeBitmap(payload, com.google.zxing.BarcodeFormat.QR_CODE, 600, 600,
                        java.util.Collections.singletonMap(com.google.zxing.EncodeHintType.MARGIN, 0));
                long exp = q.has("expires_at") ? Ui.instant(q.optString("expires_at")) : System.currentTimeMillis() + 110000;
                runOnUiThread(() -> {
                    if (gen != qrGen) return;
                    qrFails = 0; qrImg.setImageBitmap(bm); qrState("ok", QR_TEXT, null);
                    countdown(gen, exp);
                    poll(gen, token, q.optString("poll_secret"), exp, 0);
                });
            } catch (Exception e) { runOnUiThread(() -> { if (gen == qrGen) qrFail(e); }); }
        });
    }
    private void countdown(int gen, long exp) {
        if (gen != qrGen) return;
        long sec = Math.max(0, (exp - System.currentTimeMillis() + 999) / 1000);
        qrTimer.setText("Код действует ещё " + sec / 60 + ":" + String.format(java.util.Locale.ROOT, "%02d", sec % 60));
        if (sec <= 0) { stopQr(); qrState("error", "Срок действия QR истёк.", null); return; }
        h.postDelayed(() -> countdown(gen, exp), 1000);
    }
    private void poll(int gen, String token, String secret, long exp, int fails) {
        h.postDelayed(() -> io.execute(() -> {
            if (gen != qrGen || System.currentTimeMillis() >= exp) return;
            try {
                org.json.JSONObject p = Api.get(this).fn("qr-poll", new org.json.JSONObject().put("token", token).put("poll_secret", secret));
                String st = p.optString("status");
                if (st.equals("approved")) {
                    org.json.JSONObject ss = p.optJSONObject("session");
                    runOnUiThread(() -> { stopQr(); qrState("loading", "Вход подтверждён, входим…", null); });
                    if (ss != null) { Api.get(this).setSession(ss.getString("access_token"), ss.getString("refresh_token")); done = true; runOnUiThread(() -> { startActivity(new Intent(this, ChatsActivity.class)); finish(); }); }
                } else if (st.equals("denied")) runOnUiThread(() -> { stopQr(); qrState("error", "Вход отклонён на телефоне.", null); });
                else if (st.equals("expired")) runOnUiThread(() -> { stopQr(); qrState("error", "Срок действия QR истёк.", null); });
                else poll(gen, token, secret, exp, 0);
            } catch (Exception e) {
                int code = e instanceof Api.HttpError ? ((Api.HttpError) e).code : 0;
                if (code == 429 || fails + 1 >= 5) runOnUiThread(() -> { if (gen == qrGen) qrFail(e); });
                else poll(gen, token, secret, exp, fails + 1);
            }
        }), 3000);
    }
    @Override protected void onDestroy() { stopQr(); super.onDestroy(); }
}
