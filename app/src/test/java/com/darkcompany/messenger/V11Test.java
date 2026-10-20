package com.darkcompany.messenger;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** v11: вход через Google (PKCE), rebind_device с кодами RB001–RB003, без лишних устройств. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "w412dp-h915dp-night-420dpi")
public class V11Test {
    static final String UID = "11111111-1111-4111-8111-111111111111";
    static final String OLD = "aaaaaaaa-0000-4000-8000-000000000001", NEW = "bbbbbbbb-0000-4000-8000-000000000002";

    static String jwt(String sid) {
        String p = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(("{\"session_id\":\"" + sid + "\",\"sub\":\"" + UID + "\",\"exp\":9999999999}").getBytes());
        return "eyJhbGciOiJIUzI1NiJ9." + p + ".sig";
    }

    /** Поддельный сервер: записывает вызовы, rebind_device отвечает заданным образом. */
    static final class FakeNet implements E2eCore.Net {
        String sid = OLD; final List<String> calls = new ArrayList<>(); Exception rebind; String rebindOk;
        final Map<String, String> rows = new HashMap<>(); // device_id → identity_pub
        public String call(String m, String p, String j, boolean min) throws Exception {
            calls.add(m + " " + p);
            if (p.startsWith("/rest/v1/devices_keys?select=device_id&device_id=eq.")) { String d = p.substring(p.indexOf("eq.") + 3); return rows.containsKey(d) ? "[{\"device_id\":\"" + d + "\"}]" : "[]"; }
            if (p.startsWith("/rest/v1/devices_keys?select=identity_pub")) { String d = p.substring(p.indexOf("eq.") + 3); return rows.containsKey(d) ? new JSONArray().put(new JSONObject().put("identity_pub", rows.get(d))).toString() : "[]"; }
            if (p.equals("/rest/v1/devices_keys") && m.equals("POST")) { JSONObject o = new JSONObject(j); rows.put(o.getString("device_id"), o.getString("identity_pub")); return ""; }
            if (p.equals("/rest/v1/rpc/rebind_device")) { if (rebind != null) throw rebind; rows.put(rebindOk, rows.remove(new JSONObject(j).getString("p_old_device"))); return "\"" + rebindOk + "\""; }
            if (p.equals("/rest/v1/rpc/my_prekey_count")) return "50";
            return "";
        }
        public String callPrefer(String m, String p, String j, String pref) throws Exception { return call(m, p, j, false); }
        public String accessToken() { return jwt(sid); }
        public String uid() { return UID; }
        int count(String s) { int n = 0; for (String c : calls) if (c.equals(s)) n++; return n; }
    }
    static E2eStore.Kv mem() {
        final Map<String, String> m = new HashMap<>();
        return new E2eStore.Kv() {
            public String get(String k) { return m.get(k); }
            public void put(String k, String v) { m.put(k, v); }
            public void remove(String k) { m.remove(k); }
            public Set<String> keys() { return new java.util.HashSet<>(m.keySet()); }
        };
    }
    /** Устройство OLD зарегистрировано, выход (wipe сохраняет ключи), вход → новая сессия NEW. */
    static E2eCore loggedOutThenIn(FakeNet net) throws Exception {
        E2eCore c = new E2eCore(new E2eStore(mem()), net);
        c.ensureReady();
        assertEquals(OLD, c.deviceId());
        assertNotNull("секрет устройства задан через set_device_secret", c.store.kv.get("dsec"));
        assertEquals(1, net.count("POST /rest/v1/rpc/set_device_secret"));
        c.wipe();
        assertNotNull("v11: выход не удаляет ключи устройства", c.store.kv.get("idk"));
        net.sid = NEW; net.calls.clear();
        return c;
    }

    @Test public void rebindKindsByCodeAndText() {
        assertEquals("denied", E2eCore.rebindKind("RB001", "whatever", true));
        assertEquals("active", E2eCore.rebindKind("RB002", "", true));
        assertEquals("has", E2eCore.rebindKind("RB003", "", true));
        assertEquals("denied", E2eCore.rebindKind("P0001", "rebind denied", true));
        assertEquals("active", E2eCore.rebindKind(null, "old device still has an active session", true));
        assertEquals("has", E2eCore.rebindKind(null, "current session already has a device", true));
        assertEquals("other", E2eCore.rebindKind("P0001", "not authenticated", true));
        assertEquals("", E2eCore.rebindKind(null, "timeout", false));
        Api.HttpError e = new Api.HttpError(400, "{\"code\":\"RB002\",\"message\":\"old device still has an active session\"}");
        assertEquals("RB002", e.pgCode);
        assertEquals("active", E2eCore.rebindKind(e));
        assertTrue(E2eCore.rebindMessage("active").contains("не завершён"));
        assertTrue(E2eCore.rebindMessage("denied").contains("новое устройство"));
    }

    @Test public void rebindSuccessKeepsKeysNoNewDevice() throws Exception {
        FakeNet net = new FakeNet(); net.rebindOk = NEW;
        E2eCore c = loggedOutThenIn(net);
        String idk = c.store.kv.get("idk");
        c.ensureReady();
        assertEquals(NEW, c.deviceId());
        assertEquals("identity не меняется", idk, c.store.kv.get("idk"));
        assertEquals("новых ключей не публикуем", 0, net.count("POST /rest/v1/devices_keys"));
        assertEquals(1, c.rebound);
        assertEquals("секрет сменён после rebind", 1, net.count("POST /rest/v1/rpc/set_device_secret"));
        // сообщение, зашифрованное для прежнего id, — наше
        assertFalse(c.notForMe(new JSONObject().put(OLD, new JSONObject())));
        assertTrue(c.notForMe(new JSONObject().put("cccccccc-0000-4000-8000-000000000003", new JSONObject())));
    }

    @Test public void rb002AsksInsteadOfNewDevice() throws Exception {
        FakeNet net = new FakeNet();
        net.rebind = new Api.HttpError(400, "{\"code\":\"RB002\",\"message\":\"old device still has an active session\"}");
        E2eCore c = loggedOutThenIn(net);
        try { c.ensureReady(); fail("ожидали RebindBlocked"); } catch (E2eCore.RebindBlocked e) { assertEquals("active", e.kind); }
        assertEquals("новое устройство молча не заводится", 0, net.count("POST /rest/v1/devices_keys"));
        assertEquals(OLD, c.deviceId());
        // пользователь выбрал «Новое устройство»
        c.allowNewDevice(); c.ensureReady();
        assertEquals(NEW, c.deviceId());
        assertEquals(1, net.count("POST /rest/v1/devices_keys"));
    }

    @Test public void networkErrorDoesNotRegister() throws Exception {
        FakeNet net = new FakeNet(); net.rebind = new java.io.IOException("timeout");
        E2eCore c = loggedOutThenIn(net);
        try { c.ensureReady(); fail(); } catch (java.io.IOException expected) { }
        assertEquals("", c.lastRebindKind);
        assertEquals(0, net.count("POST /rest/v1/devices_keys"));
        assertEquals(OLD, c.deviceId());
    }

    @Test public void rb001RegistersNewDevice() throws Exception {
        FakeNet net = new FakeNet(); net.rebind = new Api.HttpError(400, "{\"code\":\"RB001\",\"message\":\"rebind denied\"}");
        E2eCore c = loggedOutThenIn(net);
        c.ensureReady();
        assertEquals("denied", c.lastRebindKind);
        assertEquals(NEW, c.deviceId());
        assertEquals(1, net.count("POST /rest/v1/devices_keys"));
    }

    @Test public void pkceAndCallback() throws Exception {
        String v = Api.pkceVerifier();
        assertTrue(v.matches("[A-Za-z0-9_-]{43}"));
        // RFC 7636, приложение B
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Api.pkceChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
        String u = Api.googleAuthorizeUrl("abc");
        assertTrue(u.startsWith(Api.URL + "/auth/v1/authorize?provider=google&redirect_to=com.darkcompany.messenger.nativeapp%3A%2F%2Fauth-callback"));
        assertTrue(u.contains("code_challenge=abc&code_challenge_method=s256"));
        assertTrue(Api.googleEnabled("{\"external\":{\"google\":true}}"));
        assertFalse(Api.googleEnabled("{\"external\":{\"google\":false}}"));
        assertNull(AuthCallbackActivity.error(android.net.Uri.parse("com.darkcompany.messenger.nativeapp://auth-callback?code=x")));
        assertEquals("access_denied", AuthCallbackActivity.error(android.net.Uri.parse("com.darkcompany.messenger.nativeapp://auth-callback#error=access_denied")));
        android.content.Context app = org.robolectric.RuntimeEnvironment.getApplication();
        AuthCallbackActivity.saveVerifier(app, "ver");
        assertEquals("ver", AuthCallbackActivity.takeVerifier(app));
        assertNull("одноразовый", AuthCallbackActivity.takeVerifier(app));
        android.content.Intent ct = LoginActivity.customTab(u);
        assertEquals(android.content.Intent.ACTION_VIEW, ct.getAction());
        assertTrue(ct.getExtras().containsKey("android.support.customtabs.extra.SESSION"));
    }

    @Test public void deepLinkRegistered() {
        android.content.Context app = org.robolectric.RuntimeEnvironment.getApplication();
        android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(Api.GOOGLE_REDIRECT + "?code=1"));
        i.addCategory(android.content.Intent.CATEGORY_BROWSABLE);
        java.util.List<android.content.pm.ResolveInfo> r = app.getPackageManager().queryIntentActivities(i, 0);
        assertFalse(r.isEmpty());
        assertEquals(AuthCallbackActivity.class.getName(), r.get(0).activityInfo.name);
    }

    @Test public void nickHelpers() {
        assertEquals("dima_k1", NickDialog.clean("Dima_K1!"));
        java.util.Set<String> s = NickDialog.suggest("Дмитрий Иванов", "dima@example.com", new java.util.Random(1));
        assertEquals(3, s.size());
        for (String x : s) assertTrue(x, x.startsWith("user")); // кириллица → user + цифры, как на сайте
        java.util.Set<String> e = NickDialog.suggest(null, "dark.almas@example.com", new java.util.Random(1));
        assertTrue(e.contains("dark_almas"));
        assertEquals("Это имя уже занято", NickDialog.message("taken", "x"));
        assertEquals("@dima_k свободно", NickDialog.message("ok", "dima_k"));
    }

    @Test public void loginShowsGoogleButtonLikeSite() throws Exception {
        android.content.Context app = org.robolectric.RuntimeEnvironment.getApplication();
        ChatMsgShotTest.noNet(app);
        java.lang.reflect.Field f = Api.class.getDeclaredField("sp"); f.setAccessible(true);
        android.content.SharedPreferences sp = (android.content.SharedPreferences) f.get(Api.get(app));
        // beforeSignIn: сохранённая сессия не обновилась (нет сети) — закрываем её, новый вход нужен
        assertFalse(Api.get(app).beforeSignIn());
        assertNull(sp.getString("refresh", null));
        assertFalse(Api.get(app).hasSession());
        app.getSharedPreferences("ui", 0).edit().putBoolean("google_on", true).commit();
        LoginActivity a = Robolectric.buildActivity(LoginActivity.class).setup().get();
        android.widget.TextView g = (android.widget.TextView) ChatMsgShotTest.field(a, "googleBtn");
        assertEquals("Войти через Google", g.getText().toString());
        assertEquals(android.view.View.VISIBLE, g.getVisibility());
        a.showGoogle(false);
        assertEquals(android.view.View.GONE, g.getVisibility());
        assertEquals(android.view.View.GONE, ((android.view.View) ChatMsgShotTest.field(a, "googleSep")).getVisibility());
    }
}
