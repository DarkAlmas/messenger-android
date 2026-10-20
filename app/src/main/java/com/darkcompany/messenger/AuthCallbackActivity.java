package com.darkcompany.messenger;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

/** v11: возврат из Google (deep link com.darkcompany.messenger.nativeapp://auth-callback?code=…), обмен кода PKCE на сессию. */
public class AuthCallbackActivity extends AppCompatActivity {
    private static final String PREFS = "oauth_pkce";

    static void saveVerifier(Context c, String v) {
        c.getSharedPreferences(PREFS, 0).edit().putString("v", v).putLong("t", System.currentTimeMillis()).commit();
    }
    /** Verifier одноразовый и живёт 10 минут. */
    static String takeVerifier(Context c) {
        android.content.SharedPreferences sp = c.getSharedPreferences(PREFS, 0);
        String v = sp.getString("v", null); long t = sp.getLong("t", 0);
        sp.edit().clear().commit();
        return v != null && System.currentTimeMillis() - t < 10 * 60 * 1000 ? v : null;
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
        if (err != null || code == null) { fail(err != null ? err : "нет кода авторизации"); return; }
        final String ver = takeVerifier(this);
        if (ver == null) { fail("вход устарел, нажмите «Войти через Google» ещё раз"); return; }
        new Thread(() -> {
            try {
                Api.get(this).exchangePkce(code, ver);
                runOnUiThread(() -> {
                    startActivity(new Intent(this, ChatsActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                    finish();
                });
            } catch (Exception e) { runOnUiThread(() -> fail(Ui.err(e))); }
        }, "google-pkce").start();
    }

    private void fail(String why) {
        startActivity(new Intent(this, LoginActivity.class).putExtra("google_error", "Вход через Google не удался: " + why)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }
}
