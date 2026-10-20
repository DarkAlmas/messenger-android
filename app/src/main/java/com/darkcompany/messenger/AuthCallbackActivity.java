package com.darkcompany.messenger;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

/** v11: возврат из Google (deep link com.darkcompany.messenger.nativeapp://auth-callback?code=…), обмен кода PKCE на сессию. */
public class AuthCallbackActivity extends AppCompatActivity {
    private static final String PREFS = "oauth_pkce";

    static void saveVerifier(Context c, String v) { saveVerifier(c, v, "google"); }
    /** kind: "google" или "recovery" (v12, сброс пароля; живёт 1 час — столько действует ссылка из письма). */
    static void saveVerifier(Context c, String v, String kind) {
        c.getSharedPreferences(PREFS, 0).edit().putString("v", v).putString("k", kind).putLong("t", System.currentTimeMillis()).commit();
    }
    static String kind(Context c) { return c.getSharedPreferences(PREFS, 0).getString("k", "google"); }
    /** Параметр из query или из fragment. */
    static String param(Uri u, String name) {
        if (u == null) return null;
        String v = u.getQueryParameter(name);
        if (v == null && u.getFragment() != null) v = Uri.parse("x://y?" + u.getFragment()).getQueryParameter(name);
        return v;
    }
    static boolean isRecovery(Uri u) { return "recovery".equals(param(u, "type")); }
    /** Verifier одноразовый и живёт 10 минут. */
    static String takeVerifier(Context c) {
        android.content.SharedPreferences sp = c.getSharedPreferences(PREFS, 0);
        String v = sp.getString("v", null); long t = sp.getLong("t", 0);
        long ttl = "recovery".equals(sp.getString("k", "google")) ? 60 * 60 * 1000 : 10 * 60 * 1000;
        sp.edit().clear().commit();
        return v != null && System.currentTimeMillis() - t < ttl ? v : null;
    }
    /** Ошибка из redirect (?error_description= или #error_description=), иначе null. */
    static String error(Uri u) {
        if (u == null) return "пустой ответ";
        String e = u.getQueryParameter("error_description"); if (e == null) e = u.getQueryParameter("error");
        if (e == null && u.getFragment() != null) {
            Uri f = Uri.parse("x://y?" + u.getFragment());
            e = f.getQueryParameter("error_description"); if (e == null) e = f.getQueryParameter("error");
        }
        return e;
    }

    @Override
    protected void onCreate(Bundle b) {
        Avatars.applyTheme(this);
        super.onCreate(b);
        handle(getIntent());
    }
    @Override
    protected void onNewIntent(Intent i) { super.onNewIntent(i); handle(i); }

    private void handle(Intent i) {
        final Uri u = i == null ? null : i.getData();
        final String err = error(u);
        final String code = u == null ? null : u.getQueryParameter("code");
        final String at = param(u, "access_token"), rt = param(u, "refresh_token");
        final boolean recovery = isRecovery(u) || (code != null && "recovery".equals(kind(this)));
        if (err != null) { fail(recovery ? "Ссылка для сброса пароля недействительна: " + err : null, err); return; }
        if (recovery && at != null && rt != null) {
            new Thread(() -> {
                try { Api.get(this).setSession(at, rt); runOnUiThread(this::openNewPassword); }
                catch (Exception e) { runOnUiThread(() -> fail("Ссылка для сброса пароля недействительна: " + Ui.err(e), null)); }
            }, "recovery").start();
            return;
        }
        if (code == null) { fail(recovery ? "Ссылка для сброса пароля недействительна" : null, "нет кода авторизации"); return; }
        final String ver = takeVerifier(this);
        if (ver == null) { fail(recovery ? "Ссылка устарела или открыта не на этом телефоне. Запросите сброс пароля ещё раз." : null, "вход устарел, нажмите «Войти через Google» ещё раз"); return; }
        new Thread(() -> {
            try {
                Api.get(this).exchangePkce(code, ver);
                runOnUiThread(() -> {
                    if (recovery) { openNewPassword(); return; }
                    startActivity(new Intent(this, ChatsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                    finish();
                });
            } catch (Exception e) { runOnUiThread(() -> fail(recovery ? "Ссылка для сброса пароля недействительна: " + Ui.err(e) : null, Ui.err(e))); }
        }, "google-pkce").start();
    }

    private void openNewPassword() {
        startActivity(new Intent(this, NewPasswordActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }

    /** full — готовый текст (сброс пароля), иначе «Вход через Google не удался: why». */
    private void fail(String full, String why) {
        startActivity(new Intent(this, LoginActivity.class).putExtra("google_error", full != null ? full : "Вход через Google не удался: " + why)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }
}
