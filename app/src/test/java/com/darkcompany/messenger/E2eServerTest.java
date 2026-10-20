package com.darkcompany.messenger;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Два симулированных устройства обмениваются сообщениями через настоящий REST Supabase.
 * Нужны переменные окружения E2E_A_EMAIL/E2E_B_EMAIL/E2E_PASSWORD (временные тестовые пользователи).
 * Без них тест пропускается.
 */
public class E2eServerTest {
    static final String URL = "https://tbbaztmtkcrzxnkkmtqv.supabase.co";
    static final String KEY = "sb_publishable_A3YSJaHKNLWvGTdVvjMrvA_sCJOinDo";
    static final MediaType JSON = MediaType.parse("application/json");
    static final OkHttpClient http = new OkHttpClient();

    static class MemKv implements E2eStore.Kv {
        final Map<String, String> m = new HashMap<>();
        public String get(String k) { return m.get(k); }
        public void put(String k, String v) { m.put(k, v); }
        public void remove(String k) { m.remove(k); }
        public Set<String> keys() { return new java.util.HashSet<>(m.keySet()); }
    }

    static class Dev implements E2eCore.Net {
        String access, uid;
        final E2eCore core = new E2eCore(new E2eStore(new MemKv()), this);
        Dev(String email, String pw) throws Exception { login(email, pw); }
        void login(String email, String pw) throws Exception {
            JSONObject r = new JSONObject(req("POST", "/auth/v1/token?grant_type=password", new JSONObject().put("email", email).put("password", pw).toString(), null, false));
            access = r.getString("access_token"); uid = r.getJSONObject("user").getString("id");
        }
        public String call(String m, String p, String j, boolean min) throws Exception { return req(m, p, j, access, min); }
        public String callPrefer(String m, String p, String j, String pref) throws Exception { return reqPrefer(m, p, j, access, pref); }
        public String accessToken() { return access; }
        public String uid() { return uid; }
    }

    static String reqPrefer(String method, String path, String json, String token, String prefer) throws Exception {
        Request.Builder rb = new Request.Builder().url(URL + path).header("apikey", KEY);
        if (token != null) rb.header("Authorization", "Bearer " + token);
        if (prefer != null) rb.header("Prefer", prefer);
        rb.method(method, json == null ? (method.equals("GET") ? null : RequestBody.create("", JSON)) : RequestBody.create(json, JSON));
        try (Response r = http.newCall(rb.build()).execute()) {
            String b = r.body() != null ? r.body().string() : "";
            if (!r.isSuccessful()) throw new java.io.IOException(r.code() + " " + b);
            return b;
        }
    }

    static String req(String method, String path, String json, String token, boolean minimal) throws Exception {
        Request.Builder rb = new Request.Builder().url(URL + path).header("apikey", KEY);
        if (token != null) rb.header("Authorization", "Bearer " + token);
        if (minimal) rb.header("Prefer", "return=minimal");
        else if (method.equals("POST") && path.contains("select=")) rb.header("Prefer", "return=representation");
        rb.method(method, json == null ? (method.equals("GET") ? null : RequestBody.create("", JSON)) : RequestBody.create(json, JSON));
        try (Response r = http.newCall(rb.build()).execute()) {
            String b = r.body() != null ? r.body().string() : "";
            if (!r.isSuccessful()) throw new java.io.IOException(r.code() + " " + b);
            return b;
        }
    }

    static String sendE2e(Dev from, String peer, String chat, String text) throws Exception {
        JSONObject ct = from.core.encrypt(peer, new JSONObject().put("v", 1).put("t", text).toString());
        assertNotNull("у собеседника должны быть ключи", ct);
        // id выдаёт сервер (клиенту колонка id не разрешена)
        String r = from.call("POST", "/rest/v1/messages?select=id", new JSONObject().put("chat_id", chat).put("sender_id", from.uid)
                .put("body", "").put("ciphertext", ct).toString(), false);
        String id = new JSONArray(r).getJSONObject(0).getString("id");
        from.core.rememberOwn(id, new JSONObject().put("v", 1).put("t", text).toString());
        return id;
    }

    static String readE2e(Dev to, String id) throws Exception {
        JSONObject row = new JSONArray(to.call("GET", "/rest/v1/messages?select=*&id=eq." + id, null, false)).getJSONObject(0);
        assertEquals("в базе нет открытого текста", "", row.getString("body"));
        assertFalse(row.isNull("ciphertext"));
        String body = row.getJSONObject("ciphertext").toString();
        String pt = to.core.decrypt(id, row.getString("sender_id"), row.getJSONObject("ciphertext"));
        assertNotNull("сообщение должно быть зашифровано для этого устройства", pt);
        String t = new JSONObject(pt).getString("t");
        assertFalse("шифротекст не содержит открытого текста", body.contains(t));
        return t;
    }

    @Test
    public void twoDevicesExchangeThroughRealServer() throws Exception {
        String a = System.getenv("E2E_A_EMAIL"), b = System.getenv("E2E_B_EMAIL"), pw = System.getenv("E2E_PASSWORD");
        assumeTrue("нет тестовых пользователей", a != null && b != null && pw != null);
        Dev A = new Dev(a, pw), B = new Dev(b, pw);
        A.core.ensureReady();
        B.core.ensureReady();
        String chat = A.call("POST", "/rest/v1/rpc/create_direct_chat", new JSONObject().put("p_other", B.uid).toString(), false).trim().replace("\"", "");

        String secret = "Привет, это секрет 🔒 " + UUID.randomUUID();
        String m1 = sendE2e(A, B.uid, chat, secret);
        // Сервер хранит только шифротекст
        String raw = B.call("GET", "/rest/v1/messages?select=body,ciphertext&id=eq." + m1, null, false);
        assertFalse("открытый текст не должен попадать на сервер", raw.contains("секрет") || raw.contains(secret.substring(secret.length() - 36)));
        assertEquals(secret, readE2e(B, m1));
        assertEquals("повторное чтение берётся из кэша", secret, readE2e(B, m1));

        String reply = "Ответ B → A " + UUID.randomUUID();
        String m2 = sendE2e(B, A.uid, chat, reply);
        assertEquals(reply, readE2e(A, m2));
        String m3 = sendE2e(A, B.uid, chat, "третье");
        assertEquals("третье", readE2e(B, m3));

        // TOFU: B входит заново (новое устройство) — у A отправка блокируется до подтверждения
        B.login(b, pw);
        B.core.wipe();
        B.core.ensureReady();
        A.core.forgetClaims(B.uid);
        try { A.core.encrypt(B.uid, "{\"t\":\"x\"}"); fail("ожидалось предупреждение о смене ключа"); }
        catch (E2eCore.KeyChangeException expected) { }
        A.core.acceptKeys(B.uid);
        String m4 = sendE2e(A, B.uid, chat, "после смены ключа");
        assertEquals("после смены ключа", readE2e(B, m4));
        System.out.println("E2E OK: chat " + chat + ", messages " + m1 + " " + m2 + " " + m3 + " " + m4);
    }

    @Test
    public void recoversAfterDeviceRowRemoved() throws Exception {
        String a = System.getenv("E2E_A_EMAIL"), b = System.getenv("E2E_B_EMAIL"), pw = System.getenv("E2E_PASSWORD");
        assumeTrue("нет тестовых пользователей", a != null && b != null && pw != null);
        Dev A = new Dev(a, pw), B = new Dev(b, pw);
        A.core.ensureReady(); B.core.ensureReady();
        String chat = A.call("POST", "/rest/v1/rpc/create_direct_chat", new JSONObject().put("p_other", B.uid).toString(), false).trim().replace("\"", "");
        assertEquals("до", readE2e(B, sendE2e(A, B.uid, chat, "до")));
        // Имитация очистки: строка устройства B исчезла с сервера
        B.call("DELETE", "/rest/v1/devices_keys?user_id=eq." + B.uid, null, true);
        A.core.forgetClaims(B.uid);
        assertNull("без устройства у B шифровать некуда — отправка должна блокироваться", A.core.encrypt(B.uid, "{\"t\":\"x\"}"));
        // B: проверка при старте/onResume/таймере — ключи публикуются заново тем же ключом идентичности
        B.core.ensureReady();
        assertEquals(1, B.core.republished);
        B.core.ensureReady();
        assertEquals("повторная публикация не нужна", 1, B.core.republished);
        A.core.forgetClaims(B.uid);
        String id = sendE2e(A, B.uid, chat, "после восстановления"); // без KeyChangeException: идентичность та же
        assertEquals("после восстановления", readE2e(B, id));
        String back = sendE2e(B, A.uid, chat, "ответ");
        assertEquals("ответ", readE2e(A, back));
        System.out.println("RECOVERY OK " + chat);
    }

    @Test
    public void profileAvatarAndReadReceipts() throws Exception {
        String a = System.getenv("E2E_A_EMAIL"), b = System.getenv("E2E_B_EMAIL"), pw = System.getenv("E2E_PASSWORD");
        assumeTrue(a != null && b != null && pw != null);
        Dev A = new Dev(a, pw), B = new Dev(b, pw);
        // Профиль: bio, буква, цвет — видны собеседнику
        A.call("PATCH", "/rest/v1/profiles?id=eq." + A.uid, new JSONObject().put("bio", "Тест био").put("avatar_letter", "Ж").put("avatar_color", "#FF4081").toString(), true);
        JSONObject pa = new JSONArray(B.call("GET", "/rest/v1/profiles?select=*&id=eq." + A.uid, null, false)).getJSONObject(0);
        assertEquals("Тест био", pa.getString("bio"));
        assertEquals("Ж", pa.getString("avatar_letter"));
        assertEquals("#FF4081", pa.getString("avatar_color"));
        // Чужой профиль менять нельзя
        B.call("PATCH", "/rest/v1/profiles?id=eq." + A.uid, new JSONObject().put("bio", "взлом").toString(), true);
        assertEquals("Тест био", new JSONArray(A.call("GET", "/rest/v1/profiles?select=bio&id=eq." + A.uid, null, false)).getJSONObject(0).getString("bio"));
        // Фото: загрузка в avatars/{uid}/имя, подписанная ссылка для собеседника
        String path = A.uid + "/t" + System.currentTimeMillis() + ".jpg";
        byte[] jpg = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1, 0, 1, 0, 0, (byte) 0xFF, (byte) 0xD9};
        Request up = new Request.Builder().url(URL + "/storage/v1/object/avatars/" + path).header("apikey", KEY).header("Authorization", "Bearer " + A.access)
                .header("x-upsert", "true").post(RequestBody.create(jpg, MediaType.parse("image/jpeg"))).build();
        try (Response r = http.newCall(up).execute()) { assertTrue("upload " + r.code() + " " + r.body().string(), r.isSuccessful()); }
        A.call("PATCH", "/rest/v1/profiles?id=eq." + A.uid, new JSONObject().put("avatar_url", path).toString(), true);
        String sig = B.call("POST", "/storage/v1/object/sign/avatars/" + path, "{\"expiresIn\":60}", false);
        assertTrue(new JSONObject(sig).getString("signedURL").contains("avatars"));
        Request del = new Request.Builder().url(URL + "/storage/v1/object/avatars/" + path).header("apikey", KEY).header("Authorization", "Bearer " + A.access).delete().build();
        try (Response r = http.newCall(del).execute()) { assertTrue(r.isSuccessful()); }
        A.call("PATCH", "/rest/v1/profiles?id=eq." + A.uid, new JSONObject().put("avatar_url", JSONObject.NULL).toString(), true);
        // Прочтение: mark_read(p_upto) — ✓✓ только для сообщений не позже отметки
        A.core.ensureReady(); B.core.ensureReady();
        String chat = A.call("POST", "/rest/v1/rpc/create_direct_chat", new JSONObject().put("p_other", B.uid).toString(), false).trim().replace("\"", "");
        String m1 = sendE2e(A, B.uid, chat, "раз");
        String t1 = new JSONArray(A.call("GET", "/rest/v1/messages?select=created_at&id=eq." + m1, null, false)).getJSONObject(0).getString("created_at");
        Thread.sleep(50);
        String m2 = sendE2e(A, B.uid, chat, "два");
        String t2 = new JSONArray(A.call("GET", "/rest/v1/messages?select=created_at&id=eq." + m2, null, false)).getJSONObject(0).getString("created_at");
        B.call("POST", "/rest/v1/rpc/mark_read", new JSONObject().put("p_chat", chat).put("p_upto", t1).toString(), false);
        JSONArray rs = new JSONArray(A.call("POST", "/rest/v1/rpc/chat_read_state", new JSONObject().put("p_chat", chat).toString(), false));
        long br = 0;
        for (int i = 0; i < rs.length(); i++) if (B.uid.equals(rs.getJSONObject(i).getString("user_id"))) br = Ui.instant(rs.getJSONObject(i).getString("last_read_at"));
        assertTrue("первое прочитано", Ui.instant(t1) <= br);
        assertTrue("второе ещё не прочитано", Ui.instant(t2) > br);
    }

    @Test
    public void tamperedSignedPrekeyIsRejected() throws Exception {
        final org.signal.libsignal.protocol.IdentityKeyPair idk = org.signal.libsignal.protocol.IdentityKeyPair.generate();
        final org.signal.libsignal.protocol.ecc.ECKeyPair spk = org.signal.libsignal.protocol.ecc.ECKeyPair.generate();
        final org.signal.libsignal.protocol.ecc.ECKeyPair evil = org.signal.libsignal.protocol.ecc.ECKeyPair.generate();
        final org.signal.libsignal.protocol.kem.KEMKeyPair kp = org.signal.libsignal.protocol.kem.KEMKeyPair.generate(org.signal.libsignal.protocol.kem.KEMKeyType.KYBER_1024);
        final String dev = UUID.randomUUID().toString(), peer = UUID.randomUUID().toString();
        E2eCore.Net fake = new E2eCore.Net() {
            public String call(String m, String p, String j, boolean min) throws Exception {
                // Сервер подсовывает чужой signed prekey с подписью от настоящего
                JSONObject row = new JSONObject().put("user_id", peer).put("device_id", dev).put("registration_id", 7)
                        .put("identity_pub", E2eCore.b64(idk.getPublicKey().serialize()))
                        .put("signed_prekey_id", 1).put("signed_prekey", E2eCore.b64(evil.getPublicKey().serialize()))
                        .put("signed_prekey_sig", E2eCore.b64(idk.getPrivateKey().calculateSignature(spk.getPublicKey().serialize())))
                        .put("otk_id", JSONObject.NULL).put("otk_pub", JSONObject.NULL)
                        .put("kyber_prekey_id", 1).put("kyber_prekey", E2eCore.b64(kp.getPublicKey().serialize()))
                        .put("kyber_prekey_sig", E2eCore.b64(idk.getPrivateKey().calculateSignature(kp.getPublicKey().serialize())));
                return new JSONArray().put(row).toString();
            }
            public String callPrefer(String m, String p, String j, String pref) throws Exception { return call(m, p, j, false); }
            public String accessToken() { return ""; }
            public String uid() { return UUID.randomUUID().toString(); }
        };
        E2eCore c = new E2eCore(new E2eStore(new MemKv()), fake);
        assertTrue("устройство с поддельной подписью не должно использоваться", c.devicesFor(peer).isEmpty());
        assertTrue(c.rejected.contains(dev));
        assertNull("и не должно попасть в доверенные", c.encrypt(peer, "x"));
    }

    @Test
    public void fileAesGcmRoundTripAndTamper() throws Exception {
        byte[] data = "файл".getBytes("UTF-8");
        byte[][] enc = E2eCore.encryptFile(data);
        String h = E2eCore.sha256hex(data);
        assertArrayEquals(data, E2eCore.decryptFile(enc[0], enc[1], enc[2], h));
        enc[0][0] ^= 1;
        try { E2eCore.decryptFile(enc[0], enc[1], enc[2], h); fail(); } catch (Exception expected) { }
    }
}
