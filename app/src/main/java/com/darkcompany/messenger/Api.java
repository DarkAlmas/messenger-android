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
    public String email() { return sp.getString("email", ""); }
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
                .putString("uid", o.getJSONObject("user").getString("id"))
                .putString("email", o.getJSONObject("user").optString("email", sp.getString("email", ""))).apply();
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
        /** Код PostgREST/Postgres из поля code ответа (например RB002), если есть. */
        public final String pgCode;
        public HttpError(int code, String body) { super(parseMsg(body)); this.code = code; this.pgCode = parseCode(body); }
        static String parseCode(String b) { try { Object c = new JSONObject(b).opt("code"); return c instanceof String ? (String) c : null; } catch (Exception e) { return null; } }
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
    /** Edge Function без сессии (qr-create / qr-poll), как fn() на сайте: apikey + Bearer publishable key. */
    public JSONObject fn(String name, JSONObject body) throws Exception {
        String r = send(base(URL + "/functions/v1/" + name).header("Authorization", "Bearer " + KEY)
                .post(RequestBody.create((body == null ? new JSONObject() : body).toString(), JSON)));
        return r.trim().isEmpty() ? new JSONObject() : new JSONObject(r);
    }
    /** Сессия из QR-входа (sb.auth.setSession на сайте): uid и почта — из JWT. */
    public void setSession(String access, String refresh) throws Exception {
        String[] parts = access.split("\\.");
        JSONObject claims = new JSONObject(new String(android.util.Base64.decode(parts[1], android.util.Base64.URL_SAFE | android.util.Base64.NO_WRAP | android.util.Base64.NO_PADDING), java.nio.charset.StandardCharsets.UTF_8));
        save(new JSONObject().put("access_token", access).put("refresh_token", refresh)
                .put("user", new JSONObject().put("id", claims.getString("sub")).put("email", claims.optString("email", ""))));
    }

    public void signIn(String email, String password) throws Exception {
        JSONObject b = new JSONObject().put("email", email).put("password", password);
        String r = send(base(URL + "/auth/v1/token?grant_type=password").post(RequestBody.create(b.toString(), JSON)));
        save(new JSONObject(r));
    }

    // ---------- v11: вход через Google (OAuth PKCE через браузер/Custom Tabs, как signInWithOAuth на сайте) ----------
    public static final String GOOGLE_REDIRECT = "com.darkcompany.messenger.nativeapp://auth-callback";

    /** /auth/v1/settings → external.google; null — нет сети (кнопку не трогаем, как на сайте). */
    public Boolean googleEnabled() {
        try {
            JSONObject j = new JSONObject(send(base(URL + "/auth/v1/settings").get()));
            JSONObject ex = j.optJSONObject("external");
            return ex != null && ex.optBoolean("google", false);
        } catch (Exception e) { return null; }
    }
    public static boolean googleEnabled(String settingsJson) {
        try { JSONObject ex = new JSONObject(settingsJson).optJSONObject("external"); return ex != null && ex.optBoolean("google", false); }
        catch (Exception e) { return false; }
    }

    /** code_verifier PKCE: 32 случайных байта, base64url без '='. */
    public static String pkceVerifier() {
        byte[] b = new byte[32]; new java.security.SecureRandom().nextBytes(b);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
    public static String pkceChallenge(String verifier) throws Exception {
        byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(h);
    }
    public static String googleAuthorizeUrl(String challenge) throws Exception {
        String enc = java.net.URLEncoder.encode(GOOGLE_REDIRECT, "UTF-8");
        return URL + "/auth/v1/authorize?provider=google&redirect_to=" + enc + "&code_challenge=" + challenge + "&code_challenge_method=s256";
    }
    /** Обмен кода на сессию: /auth/v1/token?grant_type=pkce. */
    public void exchangePkce(String code, String verifier) throws Exception {
        JSONObject b = new JSONObject().put("auth_code", code).put("code_verifier", verifier);
        String r = send(base(URL + "/auth/v1/token?grant_type=pkce").post(RequestBody.create(b.toString(), JSON)));
        save(new JSONObject(r));
    }

    /**
     * Перед новым входом (как beforeSignIn сайта a30f605): если сохранённая сессия ещё жива — обновляем её (refresh) и
     * новый вход не нужен (true). Иначе закрываем её на сервере (logout local), чтобы rebind_device смог перенести ключи.
     */
    public boolean beforeSignIn() {
        String rt = sp.getString("refresh", null);
        if (rt == null) return false;
        if (refresh()) { loggingOut = false; return true; }
        revoke(sp.getString("access", null), rt);
        sp.edit().remove("access").remove("refresh").commit();
        return false;
    }

    // ---------- v12: «Забыли пароль?» (как resetPasswordForEmail + updateUser на сайте) ----------
    /** POST /auth/v1/recover c PKCE: письмо со ссылкой на auth-callback?code=… (или #access_token=…&type=recovery). */
    public void recover(String email, String challenge) throws Exception {
        JSONObject b = new JSONObject().put("email", email);
        if (challenge != null) b.put("code_challenge", challenge).put("code_challenge_method", "s256");
        String url = URL + "/auth/v1/recover?redirect_to=" + java.net.URLEncoder.encode(GOOGLE_REDIRECT, "UTF-8");
        send(base(url).post(RequestBody.create(b.toString(), JSON)));
    }
    /** PUT /auth/v1/user {password} с текущей (recovery) сессией. */
    public void updatePassword(String password) throws Exception {
        authed("PUT", "/auth/v1/user", new JSONObject().put("password", password).toString(), (String) null);
    }

    /** После смены пароля: закрываем recovery-сессию и входим обычным образом по новому паролю (нужно для QR). */
    public void reLoginWithPassword(String password) throws Exception {
        String e = email();
        revoke(sp.getString("access", null), sp.getString("refresh", null));
        sp.edit().remove("access").remove("refresh").commit();
        signIn(e, password);
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
