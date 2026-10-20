package com.darkcompany.messenger;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.UUID;

/**
 * Групповое E2E (Sender Keys) через настоящий Supabase: три пользователя A, B, C.
 * Проверяет раздачу SKDM, шифрование, ротацию эпохи при повторном входе B (новое устройство),
 * сохранение старых ключей для истории, правку, выход участника C.
 * Нужны E2E_A_EMAIL/E2E_B_EMAIL/E2E_C_EMAIL/E2E_PASSWORD; без них тест пропускается.
 */
public class E2eGroupServerTest {
    static class GDev extends E2eServerTest.Dev {
        final E2eGroup g = new E2eGroup(core, this);
        GDev(String e, String p) throws Exception { super(e, p); }
        String dev() { return core.deviceId(); }
        E2eStore store() { return core.store; }
    }

    /** Та же логика, что ChatActivity.groupSend: distribute → encrypt → insert/update, на stale epoch — ротация и повтор. */
    static boolean rpcEditOk = false;
    static String[] send(GDev d, String chat, String text, String editId) throws Exception {
        int rotations = 0;
        for (int attempt = 0; attempt < 3; attempt++) {
            int epoch = d.g.distribute(chat);
            String pl = new JSONObject().put("v", 1).put("t", text).toString();
            JSONObject ct = d.g.encrypt(chat, epoch, pl);
            JSONObject row = new JSONObject().put("ciphertext", ct).put("key_epoch", epoch).put("sender_device", d.dev());
            try {
                String id;
                if (editId != null) {
                    try {
                        d.call("POST", "/rest/v1/rpc/edit_message", new JSONObject().put("p_id", editId).put("p_ciphertext", ct)
                                .put("p_key_epoch", epoch).put("p_sender_device", d.dev()).toString(), false);
                        rpcEditOk = true;
                    } catch (java.io.IOException ex) {
                        if (!String.valueOf(ex.getMessage()).contains("invalid input syntax for type uuid")) throw ex;
                        System.out.println("NOTE: edit_message падает на сервере: " + ex.getMessage() + " — фолбэк PATCH");
                        d.call("PATCH", "/rest/v1/messages?id=eq." + editId, row.toString(), true);
                    }
                    id = editId;
                }
                else {
                    row.put("chat_id", chat).put("sender_id", d.uid).put("body", "");
                    id = new JSONArray(d.call("POST", "/rest/v1/messages?select=id", row.toString(), false)).getJSONObject(0).getString("id");
                }
                d.g.rememberOwn(id, ct.getString("sk"), pl);
                return new String[]{id, String.valueOf(epoch), String.valueOf(rotations)};
            } catch (java.io.IOException e) {
                if (!String.valueOf(e.getMessage()).contains(E2eGroup.STALE)) throw e;
                rotations++;
            }
        }
        throw new AssertionError("не удалось отправить после ротаций");
    }

    static String read(GDev d, String chat, String id) throws Exception {
        d.g.receive(chat);
        JSONObject row = new JSONArray(d.call("GET", "/rest/v1/messages?select=*&id=eq." + id, null, false)).getJSONObject(0);
        assertEquals("в базе нет открытого текста", "", row.getString("body"));
        String pt = d.g.decrypt(chat, id, row.getString("sender_id"), row.getString("sender_device"), row.getInt("key_epoch"), row.getJSONObject("ciphertext"));
        String t = new JSONObject(pt).getString("t");
        assertFalse(row.getJSONObject("ciphertext").toString().contains(t));
        return t;
    }

    static int epoch(GDev d, String chat) throws Exception {
        return new JSONArray(d.call("GET", "/rest/v1/chats?select=key_epoch&id=eq." + chat, null, false)).getJSONObject(0).getInt("key_epoch");
    }

    @Test
    public void groupSenderKeysThroughRealServer() throws Exception {
        String a = System.getenv("E2E_A_EMAIL"), b = System.getenv("E2E_B_EMAIL"), cc = System.getenv("E2E_C_EMAIL"), pw = System.getenv("E2E_PASSWORD");
        assumeTrue("нет тестовых пользователей", a != null && b != null && cc != null && pw != null);
        GDev A = new GDev(a, pw), B = new GDev(b, pw), C = new GDev(cc, pw);
        A.core.ensureReady(); B.core.ensureReady(); C.core.ensureReady();
        String chat = A.call("POST", "/rest/v1/rpc/create_group_chat", new JSONObject().put("p_title", "e2e-test " + UUID.randomUUID().toString().substring(0, 8))
                .put("p_members", new JSONArray().put(B.uid).put(C.uid)).toString(), false).trim().replace("\"", "");
        int e0 = epoch(A, chat);

        // 1. Обычный обмен: A → B, C; B → A, C
        String s1 = "групповой секрет " + UUID.randomUUID();
        String m1 = send(A, chat, s1, null)[0];
        assertEquals(s1, read(B, chat, m1));
        assertEquals(s1, read(C, chat, m1));
        assertEquals("повтор — из кэша", s1, read(B, chat, m1));
        String m2 = send(B, chat, "от B", null)[0];
        assertEquals("от B", read(C, chat, m2));
        // A забирает ключ B, но само m2 пока не расшифровывает — проверим историю после ротации.
        // (Если не забрать до отзыва устройства B, сервер удаляет его неполученные SKDM — см. отчёт.)
        assertEquals(1, A.g.receive(chat));

        // 2. Сообщение со старой эпохой сервер отвергает
        try {
            A.call("POST", "/rest/v1/messages", new JSONObject().put("chat_id", chat).put("sender_id", A.uid).put("body", "")
                    .put("ciphertext", A.g.encrypt(chat, e0, "{\"t\":\"x\"}")).put("key_epoch", e0 - 1).put("sender_device", A.dev()).toString(), true);
            fail("stale epoch должен отвергаться");
        } catch (java.io.IOException ex) { assertTrue(ex.getMessage(), ex.getMessage().contains(E2eGroup.STALE)); }

        // 3. Повторная раздача (ignore-duplicates) не падает
        A.store().kv.remove("skd:" + chat + ":" + e0);
        A.g.distribute(chat);

        // 4. B выходит и входит снова (новое устройство) → эпоха растёт
        String oldBdev = B.dev();
        E2eServerTest.req("POST", "/auth/v1/logout?scope=local", null, B.access, false);
        B.login(b, pw); B.core.wipe(); B.core.ensureReady();
        assertNotEquals(oldBdev, B.dev());
        int e1 = epoch(A, chat);
        assertTrue("эпоха должна вырасти после отзыва устройства: " + e0 + " → " + e1, e1 > e0);
        // A: TOFU видит новое устройство B → до подтверждения ключ группы ему не уходит
        String[] r3 = send(A, chat, "после ротации (B ещё не подтверждён)", null);
        assertEquals(String.valueOf(e1), r3[1]);
        assertFalse("предупреждение о смене ключа", A.g.warnings(chat).isEmpty());
        A.core.acceptKeys(B.uid);
        String s4 = "после подтверждения " + UUID.randomUUID();
        String m4 = send(A, chat, s4, null)[0];
        assertEquals(s4, read(B, chat, m4));
        assertEquals(s4, read(C, chat, m4));
        // История: A читает старое сообщение B (эпоха e0) — чужие старые ключи сохранены
        assertEquals("от B", read(A, chat, m2));
        // H1: после выхода/входа прочитанная история остаётся из локального кэша…
        assertEquals(s1, read(B, chat, m1));
        // …но ключа старой эпохи у нового устройства нет (криптографически прочитать заново нельзя)
        assertNull(B.store().loadSenderKey(new org.signal.libsignal.protocol.SignalProtocolAddress(A.dev(), 1), E2eGroup.distId(chat, e0)));
        String m5 = send(B, chat, "B с нового устройства", null)[0];
        assertEquals("B с нового устройства", read(A, chat, m5));
        // C до этого не видел новое устройство B: SKDM откладывается до подтверждения ключа
        try { read(C, chat, m5); fail("C не должен принимать ключ неподтверждённого устройства"); } catch (E2eGroup.NoKeyException expected) { }
        assertTrue(C.core.hasPendingChange(B.uid));
        C.core.acceptKeys(B.uid);
        assertEquals("B с нового устройства", read(C, chat, m5));

        // 5. Правка — новым шифротекстом текущей эпохи
        send(A, chat, "исправленный текст", m4);
        assertEquals("исправленный текст", read(B, chat, m4));
        JSONObject er = new JSONArray(B.call("GET", "/rest/v1/messages?select=body,edited_at&id=eq." + m4, null, false)).getJSONObject(0);
        assertTrue("правка должна пройти через RPC edit_message", rpcEditOk);
        assertFalse("edit_message ставит edited_at", er.isNull("edited_at"));

        // 6. C выходит → эпоха растёт, новые сообщения C не получает
        C.call("POST", "/rest/v1/rpc/leave_chat", new JSONObject().put("p_chat", chat).toString(), false);
        int e2 = epoch(A, chat);
        assertTrue("эпоха должна вырасти после выхода", e2 > e1);
        String s6 = "после выхода C " + UUID.randomUUID();
        String[] r6 = send(A, chat, s6, null);
        assertEquals(String.valueOf(e2), r6[1]);
        assertEquals(s6, read(B, chat, r6[0]));
        assertEquals("C не видит сообщений группы", "[]", C.call("GET", "/rest/v1/messages?select=id&id=eq." + r6[0], null, false).trim());
        assertEquals("C не получает SKDM", 0, C.g.receive(chat));
        assertNull("у C нет ключа новой эпохи", C.store().loadSenderKey(new org.signal.libsignal.protocol.SignalProtocolAddress(A.dev(), 1), E2eGroup.distId(chat, e2)));
        // 7. Открытый текст: сервер по-прежнему принимает (только клиентская защита) — фиксируем для отчёта
        try {
            A.call("POST", "/rest/v1/messages", new JSONObject().put("chat_id", chat).put("sender_id", A.uid).put("body", "plain").toString(), true);
            fail("сервер должен отвергать открытый текст в группе");
        } catch (java.io.IOException ex) { assertTrue(ex.getMessage(), ex.getMessage().contains("e2e required")); }
        // 8. Upsert ON CONFLICT DO NOTHING теперь разрешён: повтор той же раздачи без ошибки и без дубликата
        A.store().kv.remove("skd:" + chat + ":" + e2);
        A.g.distribute(chat);
        A.store().kv.remove("skd:" + chat + ":" + e2);
        A.g.distribute(chat); // те же строки ещё раз — дубликаты молча пропускаются
        System.out.println("upsert path used: " + A.g.lastUploadMode);
        assertEquals("upsert", A.g.lastUploadMode);
        // 9. Исчерпанный лимит одноразовых prekey: claim_prekeys отдаёт null otk, сессия строится без OTK
        for (int i = 0; i < 31; i++) A.call("POST", "/rest/v1/rpc/claim_prekeys", new JSONObject().put("p_user", B.uid).toString(), false);
        String last = A.call("POST", "/rest/v1/rpc/claim_prekeys", new JSONObject().put("p_user", B.uid).toString(), false);
        JSONObject r0 = new JSONArray(last).getJSONObject(0);
        assertTrue("после лимита otk = null", r0.isNull("otk_id"));
        GDev C2 = new GDev(cc, pw); C2.core.ensureReady();
        String dchat = C2.call("POST", "/rest/v1/rpc/create_direct_chat", new JSONObject().put("p_other", B.uid).toString(), false).trim().replace("\"", "");
        String dm = E2eServerTest.sendE2e(C2, B.uid, dchat, "без OTK");
        B.core.forgetClaims(C2.uid);
        assertEquals("без OTK", E2eServerTest.readE2e(B, dm));
        System.out.println("GROUP E2E OK: chat " + chat + " epochs " + e0 + "→" + e1 + "→" + e2);
    }
}
