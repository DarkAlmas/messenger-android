package com.darkcompany.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Минимальный клиент Supabase (Auth + REST + RPC) поверх OkHttp. */
public final class Api {
    public static final String URL = "https://tbbaztmtkcrzxnkkmtqv.supabase.co";
    public static final String KEY = "sb_publishable_A3YSJaHKNLWvGTdVvjMrvA_sCJOinDo";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    public static class AuthException extends Exception {
        public AuthException(String m) { super(m); }
    }

    private static Api instance;
    private final OkHttpClient http = new OkHttpClient();
    private final SharedPreferences sp;

    private Api(Context c) {
        sp = openPrefs(c.getApplicationContext());
    }

    private static final String[] KEYS = {"access", "refresh", "uid"};

    /** Сессия в EncryptedSharedPreferences (AES256_GCM); старый незашифрованный msgr_session всегда удаляется; при сбое Keystore — только память (MemPrefs), на диск токены не пишутся. */
    private static SharedPreferences openPrefs(Context c) {
        SharedPreferences enc;
        try {
            enc = create(c);
        } catch (Exception e) {
            // Повреждённый файл/ключ: удаляем зашифрованные prefs и пробуем ещё раз
            try {
                c.deleteSharedPreferences("msgr_session_enc");
                enc = create(c);
            } catch (Exception e2) {
                enc = null;
            }
        }
        SharedPreferences old = c.getSharedPreferences("msgr_session", Context.MODE_PRIVATE);
        if (enc != null && old.getString("refresh", null) != null && enc.getString("refresh", null) == null) {
            SharedPreferences.Editor ed = enc.edit();
            for (String k : KEYS) { String v = old.getString(k, null); if (v != null) ed.putString(k, v); }
            ed.commit();
        }
        old.edit().clear().commit();
        c.deleteSharedPreferences("msgr_session");
        // Keystore недоступен: сессия только в памяти, на диск токены не пишутся
        return enc != null ? enc : new MemPrefs();
    }

    private static SharedPreferences create(Context c) throws Exception {
        androidx.security.crypto.MasterKey mk = new androidx.security.crypto.MasterKey.Builder(c)
                .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM).build();
        return androidx.security.crypto.EncryptedSharedPreferences.create(c, "msgr_session_enc", mk,
                androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
    }

    public static synchronized Api get(Context c) {
        if (instance == null) instance = new Api(c);
        return instance;
    }

    private volatile boolean loggingOut = false;
    public boolean hasSession() { return !loggingOut && sp.getString("refresh", null) != null; }
    public String uid() { return sp.getString("uid", ""); }
    /**
     * Отзывает сессию на сервере (POST /auth/v1/logout?scope=local), затем стирает токены.
     * Если access-токен уже истёк (401) — обновляет его по refresh-токену и повторяет logout.
     * Ошибки сети игнорируются.
     */
    public void logout() {
        final String token = sp.getString("access", null);
        final String rt = sp.getString("refresh", null);
        if ((token == null || token.isEmpty()) && rt == null) { sp.edit().clear().commit(); return; }
        loggingOut = true; // сессия сразу считается закрытой для UI
        Runnable r = () -> {
            try { revoke(token, rt); }          // 1) сначала отзываем сессию на сервере
            finally { sp.edit().clear().commit(); loggingOut = false; } // 2) только потом стираем токены
        };
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) new Thread(r, "logout-revoke").start();
        else r.run();
    }

    /** Синхронный отзыв сессии; возвращает HTTP-код последнего logout (или -1). Публичный для тестов. */
    public int revoke(String token, String rt) {
        int code = -1;
        try {
            if (token != null && !token.isEmpty()) code = postLogout(token);
            if ((code == 401 || code == 403 || token == null || token.isEmpty()) && rt != null) {
                JSONObject b = new JSONObject().put("refresh_token", rt);
                String r = send(base(URL + "/auth/v1/token?grant_type=refresh_token").post(RequestBody.create(b.toString(), JSON)));
                code = postLogout(new JSONObject(r).getString("access_token"));
            }
        } catch (Exception ignored) { }
        return code;
    }

    private int postLogout(String token) throws IOException {
        try (Response r = http.newCall(base(URL + "/auth/v1/logout?scope=local")
                .header("Authorization", "Bearer " + token)
                .post(RequestBody.create("", JSON)).build()).execute()) {
            return r.code();
        }
    }

    /** Запрос к REST с токеном (для E2E). */
    public String raw(String method, String path, String json, boolean minimal) throws Exception {
        return authed(method, path, json, minimal);
    }

    public String rawPrefer(String method, String path, String json, String prefer) throws Exception {
        return authed(method, path, json, prefer);
    }

    /** Текущий access-токен, обновлённый если до истечения меньше минуты. */
    public String accessToken() throws Exception {
        String a = sp.getString("access", "");
        try {
            long exp = new JSONObject(new String(android.util.Base64.decode(a.split("\\.")[1], android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP), "UTF-8")).getLong("exp");
            if (exp * 1000 - System.currentTimeMillis() > 60_000) return a;
        } catch (Exception ignored) { }
        return freshToken();
    }

    /** PATCH строк по фильтру (например messages?id=eq.X). */
    public void update(String pathAndQuery, JSONObject row) throws Exception {
        authed("PATCH", "/rest/v1/" + pathAndQuery, row.toString(), true);
    }

    /** DELETE строк по фильтру. */
    public void delete(String pathAndQuery) throws Exception {
        authed("DELETE", "/rest/v1/" + pathAndQuery, null, true);
    }

    private void save(JSONObject o) throws Exception {
        sp.edit().putString("access", o.getString("access_token"))
                .putString("refresh", o.getString("refresh_token"))
                .putString("uid", o.getJSONObject("user").getString("id")).apply();
    }

    private String send(Request.Builder rb) throws IOException {
        try (Response r = http.newCall(rb.build()).execute()) {
            String b = r.body() != null ? r.body().string() : "";
            if (!r.isSuccessful()) throw new HttpError(r.code(), b);
            return b;
        }
    }

    public static class HttpError extends IOException {
        public final int code;
        public HttpError(int code, String body) { super(parseMsg(body)); this.code = code; }
        private static String parseMsg(String b) {
            try {
                JSONObject o = new JSONObject(b);
                for (String k : new String[]{"msg", "message", "error_description", "error"})
                    if (o.has(k)) return o.getString(k);
            } catch (Exception ignored) { }
            return b;
        }
    }

    private Request.Builder base(String url) {
        return new Request.Builder().url(url).header("apikey", KEY);
    }

    /** Вход строго по паролю: только такая сессия может одобрять QR. */
    public void signIn(String email, String password) throws Exception {
        JSONObject b = new JSONObject().put("email", email).put("password", password);
        String r = send(base(URL + "/auth/v1/token?grant_type=password").post(RequestBody.create(b.toString(), JSON)));
        save(new JSONObject(r));
    }

    public void signUp(String email, String password, String username) throws Exception {
        JSONObject data = new JSONObject().put("username", username).put("display_name", username);
        JSONObject b = new JSONObject().put("email", email).put("password", password).put("data", data);
        send(base(URL + "/auth/v1/signup").post(RequestBody.create(b.toString(), JSON)));
    }

    private boolean refresh() {
        try {
            JSONObject b = new JSONObject().put("refresh_token", sp.getString("refresh", ""));
            String r = send(base(URL + "/auth/v1/token?grant_type=refresh_token").post(RequestBody.create(b.toString(), JSON)));
            save(new JSONObject(r));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Запрос с токеном; при 401 один раз обновляет сессию, иначе выбрасывает AuthException. */
    private String authed(String method, String path, String json, boolean preferMinimal) throws Exception {
        return authed(method, path, json, preferMinimal ? "return=minimal" : null);
    }

    private String authed(String method, String path, String json, String prefer) throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            Request.Builder rb = base(URL + path).header("Authorization", "Bearer " + sp.getString("access", ""));
            RequestBody body = json == null ? null : RequestBody.create(json, JSON);
            if (prefer != null) rb.header("Prefer", prefer);
            rb.method(method, body == null && !method.equals("GET") ? RequestBody.create("", JSON) : body);
            try {
                return send(rb);
            } catch (HttpError e) {
                boolean unauth = e.code == 401 || (e.getMessage() != null && e.getMessage().contains("not authenticated"));
                if (unauth) {
                    if (attempt == 0 && refresh()) continue;
                    logout();
                    throw new AuthException("Сессия закрыта, войдите снова");
                }
                throw e;
            }
        }
        throw new AuthException("Сессия закрыта, войдите снова");
    }

    /** Свежий access token для прямых запросов к Storage (ChatFiles). */
    public String freshToken() throws Exception {
        if (!refresh()) { logout(); throw new AuthException("Сессия закрыта, войдите снова"); }
        return sp.getString("access", "");
    }

    public JSONArray select(String pathAndQuery) throws Exception {
        String r = authed("GET", "/rest/v1/" + pathAndQuery, null, false);
        return new JSONArray(r);
    }

    public void insert(String table, JSONObject row) throws Exception {
        authed("POST", "/rest/v1/" + table, row.toString(), true);
    }

    /** Вызов RPC, возвращает тело ответа как строку (может быть пустой). */
    /** INSERT с возвратом id, выданного сервером. */
    public String insertReturningId(String table, JSONObject row) throws Exception {
        return new JSONArray(authed("POST", "/rest/v1/" + table + "?select=id", row.toString(), "return=representation")).getJSONObject(0).getString("id");
    }

    public String rpc(String fn, JSONObject args) throws Exception {
        return authed("POST", "/rest/v1/rpc/" + fn, args.toString(), false);
    }
}
