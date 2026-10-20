package com.darkcompany.messenger;

import org.json.JSONArray;
import org.json.JSONObject;
import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.SessionBuilder;
import org.signal.libsignal.protocol.SessionCipher;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.ecc.ECKeyPair;
import org.signal.libsignal.protocol.ecc.ECPublicKey;
import org.signal.libsignal.protocol.fingerprint.Fingerprint;
import org.signal.libsignal.protocol.fingerprint.NumericFingerprintGenerator;
import org.signal.libsignal.protocol.kem.KEMKeyPair;
import org.signal.libsignal.protocol.kem.KEMKeyType;
import org.signal.libsignal.protocol.kem.KEMPublicKey;
import org.signal.libsignal.protocol.message.CiphertextMessage;
import org.signal.libsignal.protocol.message.PreKeySignalMessage;
import org.signal.libsignal.protocol.message.SignalMessage;
import org.signal.libsignal.protocol.state.KyberPreKeyRecord;
import org.signal.libsignal.protocol.state.PreKeyBundle;
import org.signal.libsignal.protocol.state.PreKeyRecord;
import org.signal.libsignal.protocol.state.SignedPreKeyRecord;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Сквозное шифрование личных чатов: X3DH/PQXDH + Double Ratchet из libsignal.
 * Устройство = сессия Supabase (session_id из JWT). Без Android-зависимостей — тестируется на JVM.
 * Своей криптографии здесь нет: только libsignal и стандартный AES-GCM из JCA для файлов.
 */
public final class E2eCore {
    public interface Net {
        /** REST-запрос к Supabase с текущим токеном; бросает исключение на не-2xx. */
        String call(String method, String path, String json, boolean minimal) throws Exception;
        /** Как call, но с произвольным заголовком Prefer (upsert с ignore-duplicates). */
        String callPrefer(String method, String path, String json, String prefer) throws Exception;
        String accessToken() throws Exception;
        String uid();
    }

    /** Ключи собеседника изменились/появилось новое устройство: отправка заблокирована до подтверждения. */
    public static class KeyChangeException extends Exception {
        public final String userId;
        public KeyChangeException(String u) { super("Ключ безопасности собеседника изменился"); userId = u; }
    }

    public static final int OTK_BATCH = 100, OTK_LOW = 20;
    private static final long CLAIM_TTL_MS = 10 * 60 * 1000;

    public final E2eStore store;
    private final Net net;
    private final SecureRandom rnd = new SecureRandom();
    private final Map<String, Long> claimedAt = new HashMap<>();
    /** Устройства с отвергнутой подписью prekey (сервер подписи не проверяет). */
    public final List<String> rejected = new ArrayList<>();

    public E2eCore(E2eStore store, Net net) { this.store = store; this.net = net; }

    static String b64(byte[] b) { return Base64.getEncoder().encodeToString(b); }
    static byte[] unb64(String s) { return Base64.getDecoder().decode(s); }

    /** session_id из access-токена = id устройства. */
    public static String sessionId(String jwt) throws Exception {
        String[] p = jwt.split("\\.");
        String json = new String(Base64.getUrlDecoder().decode(p[1].replace("=", "")), StandardCharsets.UTF_8);
        return new JSONObject(json).getString("session_id");
    }

    public String deviceId() { return store.kv.get("dev"); }

    /** Генерирует ключи для текущей сессии (если сессия новая) и публикует их; добивает одноразовые prekey. */
    public synchronized void ensureReady() throws Exception {
        String dev = sessionId(net.accessToken());
        // Сохранённые кэши/доверие принадлежат одному аккаунту: другой аккаунт на этом телефоне начинает с чистого листа
        String owner = store.kv.get("owner");
        if (owner == null) store.kv.put("owner", net.uid()); // установка до этой версии: ничего не стираем
        else if (!net.uid().equals(owner)) {
            for (String k : new ArrayList<>(store.kv.keys())) store.kv.remove(k);
            store.kv.put("owner", net.uid());
        }
        if (!dev.equals(store.kv.get("dev"))) {
            // Новая сессия = новое устройство: старые ключи и сессии не годятся
            for (String k : new ArrayList<>(store.kv.keys()))
                if (!k.startsWith("pt") && !k.startsWith("trust:") && !k.startsWith("pending:") && !k.startsWith("verified:")) store.kv.remove(k);
            IdentityKeyPair idk = IdentityKeyPair.generate();
            store.kv.put("idk", b64(idk.serialize()));
            store.kv.put("reg", String.valueOf(1 + rnd.nextInt(16380)));
            ECKeyPair spk = ECKeyPair.generate();
            byte[] sig = idk.getPrivateKey().calculateSignature(spk.getPublicKey().serialize());
            store.storeSignedPreKey(1, new SignedPreKeyRecord(1, System.currentTimeMillis(), spk, sig));
            KEMKeyPair kp = KEMKeyPair.generate(KEMKeyType.KYBER_1024);
            byte[] ksig = idk.getPrivateKey().calculateSignature(kp.getPublicKey().serialize());
            store.storeKyberPreKey(1, new KyberPreKeyRecord(1, System.currentTimeMillis(), kp, ksig));
            store.kv.put("otk_next", "1");
            store.kv.put("dev", dev);
        }
        // Самовосстановление: строка устройства могла пропасть на сервере (очистка, сбой) — проверяем и публикуем заново тем же ключом
        if ("1".equals(store.kv.get("uploaded")) && !serverHasDevice(dev)) {
            store.kv.remove("uploaded");
            republished++;
        }
        if (!"1".equals(store.kv.get("uploaded"))) {
            IdentityKeyPair idk = store.getIdentityKeyPair();
            SignedPreKeyRecord spk = store.loadSignedPreKey(1);
            KyberPreKeyRecord kpk = store.loadKyberPreKey(1);
            JSONObject row = new JSONObject().put("device_id", dev).put("user_id", net.uid())
                    .put("registration_id", store.getLocalRegistrationId())
                    .put("identity_pub", b64(idk.getPublicKey().serialize()))
                    .put("signed_prekey_id", 1).put("signed_prekey", b64(spk.getKeyPair().getPublicKey().serialize()))
                    .put("signed_prekey_sig", b64(spk.getSignature()))
                    .put("kyber_prekey_id", 1).put("kyber_prekey", b64(kpk.getKeyPair().getPublicKey().serialize()))
                    .put("kyber_prekey_sig", b64(kpk.getSignature()));
            try {
                net.call("POST", "/rest/v1/devices_keys", row.toString(), true);
            } catch (Exception e) {
                String m = String.valueOf(e.getMessage());
                if (!m.contains("duplicate")) throw e;
                // UPDATE разрешён только по колонкам ключей (без device_id/user_id)
                row.remove("device_id"); row.remove("user_id");
                net.call("PATCH", "/rest/v1/devices_keys?device_id=eq." + dev, row.toString(), true);
            }
            store.kv.put("uploaded", "1");
        }
        topUpPrekeys();
    }

    /** Сколько раз за жизнь процесса ключи публиковались повторно (диагностика/тесты). */
    public volatile int republished = 0;

    /** Есть ли на сервере строка devices_keys этого устройства. */
    public boolean serverHasDevice(String dev) throws Exception {
        String r = net.call("GET", "/rest/v1/devices_keys?select=device_id&device_id=eq." + dev, null, false).trim();
        return r.length() > 2; // "[]" — строки нет
    }

    public void topUpPrekeys() throws Exception {
        String r = net.call("POST", "/rest/v1/rpc/my_prekey_count", "{}", false).trim();
        int have = r.isEmpty() ? 0 : Integer.parseInt(r);
        if (have >= OTK_LOW) return;
        int next = Integer.parseInt(store.kv.get("otk_next"));
        JSONArray rows = new JSONArray();
        List<PreKeyRecord> recs = new ArrayList<>();
        for (int i = 0; i < OTK_BATCH; i++) {
            int id = next + i;
            PreKeyRecord pr = new PreKeyRecord(id, ECKeyPair.generate());
            recs.add(pr);
            rows.put(new JSONObject().put("device_id", deviceId()).put("key_id", id).put("pub", b64(pr.getKeyPair().getPublicKey().serialize())));
        }
        for (PreKeyRecord pr : recs) store.storePreKey(pr.getId(), pr); // сначала локально, иначе сообщение на prekey не расшифровать
        store.kv.put("otk_next", String.valueOf(next + OTK_BATCH));
        net.call("POST", "/rest/v1/one_time_prekeys", rows.toString(), true);
    }

    private static SignalProtocolAddress addr(String dev) { return new SignalProtocolAddress(dev, 1); }

    private JSONObject jget(String k) {
        String s = store.kv.get(k);
        try { return s == null ? new JSONObject() : new JSONObject(s); } catch (Exception e) { return new JSONObject(); }
    }

    /**
     * Устройства пользователя, которым можно отправлять. Проверяет подписи prekey против identity,
     * применяет TOFU: при первом контакте запоминает ключи; новое устройство или другой ключ позже —
     * KeyChangeException, пока пользователь не примет изменение.
     */
    public synchronized List<String> devicesFor(String user) throws Exception {
        JSONObject trust = jget("trust:" + user);
        Long at = claimedAt.get(user);
        if (at == null || System.currentTimeMillis() - at > CLAIM_TTL_MS) {
            String r = net.call("POST", "/rest/v1/rpc/claim_prekeys", new JSONObject().put("p_user", user).toString(), false).trim();
            JSONArray rows = r.isEmpty() ? new JSONArray() : new JSONArray(r);
            // Пустой ответ (сбой/лимит/собеседник вышел со всех устройств) не сбрасывает доверие:
            // иначе следующий успешный ответ дал бы ложное «ключ изменился». Отправлять просто некому.
            if (rows.length() == 0) {
                if (store.kv.get("pending:" + user) != null) throw new KeyChangeException(user);
                return new ArrayList<>();
            }
            claimedAt.put(user, System.currentTimeMillis());
            Map<String, JSONObject> valid = new HashMap<>();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject o = rows.getJSONObject(i);
                String dev = o.getString("device_id");
                if (dev.equals(deviceId())) continue;
                try {
                    IdentityKey ik = new IdentityKey(unb64(o.getString("identity_pub")));
                    ECPublicKey spk = new ECPublicKey(unb64(o.getString("signed_prekey")));
                    if (!ik.getPublicKey().verifySignature(spk.serialize(), unb64(o.getString("signed_prekey_sig"))))
                        throw new SecurityException("bad signed prekey signature");
                    if (o.isNull("kyber_prekey")) continue; // старый клиент без PQXDH — пропускаем
                    KEMPublicKey kk = new KEMPublicKey(unb64(o.getString("kyber_prekey")));
                    if (!ik.getPublicKey().verifySignature(kk.serialize(), unb64(o.getString("kyber_prekey_sig"))))
                        throw new SecurityException("bad kyber prekey signature");
                    valid.put(dev, o);
                } catch (Exception e) {
                    if (!rejected.contains(dev)) rejected.add(dev);
                }
            }
            // Вышедшие устройства убираем из доверенных
            for (Iterator<String> it = trust.keys(); it.hasNext(); ) { String d = it.next(); if (!valid.containsKey(d)) it.remove(); }
            JSONObject pending = jget("pending:" + user);
            boolean first = store.kv.get("trust:" + user) == null;
            for (Map.Entry<String, JSONObject> e : valid.entrySet()) {
                String dev = e.getKey(), ik = e.getValue().getString("identity_pub");
                if (first) trust.put(dev, ik);
                else if (!ik.equals(trust.optString(dev, null))) pending.put(dev, ik);
            }
            store.kv.put("trust:" + user, trust.toString());
            if (pending.length() > 0) store.kv.put("pending:" + user, pending.toString());
            // Строим сессии с доверенными устройствами без сессии
            for (Map.Entry<String, JSONObject> e : valid.entrySet()) {
                String dev = e.getKey();
                JSONObject o = e.getValue();
                if (!o.getString("identity_pub").equals(trust.optString(dev, null))) continue;
                store.kv.put("devuser:" + dev, user);
                if (store.containsSession(addr(dev))) continue;
                PreKeyBundle b = new PreKeyBundle(o.getInt("registration_id"), 1,
                        o.isNull("otk_id") ? PreKeyBundle.NULL_PRE_KEY_ID : o.getInt("otk_id"),
                        o.isNull("otk_pub") ? null : new ECPublicKey(unb64(o.getString("otk_pub"))),
                        o.getInt("signed_prekey_id"), new ECPublicKey(unb64(o.getString("signed_prekey"))),
                        unb64(o.getString("signed_prekey_sig")), new IdentityKey(unb64(o.getString("identity_pub"))),
                        o.getInt("kyber_prekey_id"), new KEMPublicKey(unb64(o.getString("kyber_prekey"))),
                        unb64(o.getString("kyber_prekey_sig")));
                new SessionBuilder(store, addr(dev)).process(b);
            }
        }
        if (store.kv.get("pending:" + user) != null) throw new KeyChangeException(user);
        List<String> out = new ArrayList<>();
        for (Iterator<String> it = trust.keys(); it.hasNext(); ) { String d = it.next(); if (store.containsSession(addr(d)) || store.kv.get("sess:" + d + ".1") != null) out.add(d); }
        return out;
    }

    /** Сбросить кэш claim, чтобы при следующей отправке заново запросить устройства. */
    public synchronized void forgetClaims(String user) { claimedAt.remove(user); }

    public boolean hasPendingChange(String user) { return store.kv.get("pending:" + user) != null; }

    /** Пользователь принял новые ключи (после проверки кода безопасности). */
    public synchronized void acceptKeys(String user) {
        JSONObject trust = jget("trust:" + user), pending = jget("pending:" + user);
        try {
            for (Iterator<String> it = pending.keys(); it.hasNext(); ) {
                String d = it.next();
                trust.put(d, pending.getString(d));
                store.deleteAllSessions(d);
                store.kv.remove("ident:" + d);
            }
        } catch (Exception ignored) { }
        store.kv.put("trust:" + user, trust.toString());
        store.kv.remove("pending:" + user);
        store.kv.remove("verified:" + user);
        claimedAt.remove(user);
    }

    /** Можно ли шифровать этот чат: наши ключи опубликованы и у собеседника есть хотя бы одно устройство с ключами. */
    public boolean peerHasKeys(String peer) throws Exception {
        try { return !devicesFor(peer).isEmpty(); }
        catch (KeyChangeException e) { return true; }
    }

    /** Шифрует для всех устройств собеседника и своих других устройств. null — у собеседника нет ключей. */
    public synchronized JSONObject encrypt(String peer, String plaintext) throws Exception {
        List<String> peerDevs = devicesFor(peer);
        if (peerDevs.isEmpty()) return null;
        List<String> all = new ArrayList<>(peerDevs);
        try { for (String d : devicesFor(net.uid())) if (!all.contains(d)) all.add(d); }
        catch (KeyChangeException e) { if (e.userId.equals(net.uid())) throw e; }
        JSONObject ct = new JSONObject();
        byte[] pt = plaintext.getBytes(StandardCharsets.UTF_8);
        for (String d : all) {
            CiphertextMessage m = new SessionCipher(store, addr(d)).encrypt(pt);
            ct.put(d, new JSONObject().put("f", deviceId()).put("t", m.getType()).put("b", b64(m.serialize())));
        }
        return ct;
    }

    /** Запоминает открытый текст своего сообщения (свои сообщения себе не шифруются). */
    public void rememberOwn(String msgId, String plaintext) { store.kv.put("pts:" + msgId, plaintext); }

    /** Расшифровка; результат кэшируется (Double Ratchet расшифровывает сообщение только один раз). null — не для этого устройства. */
    public synchronized String decrypt(String msgId, String sender, JSONObject ct) throws Exception {
        String own = store.kv.get("pts:" + msgId);
        if (own != null) return own;
        JSONObject e = ct.optJSONObject(deviceId());
        if (e == null) return null;
        String ck = "pt:" + msgId + ":" + sha256hex(e.getString("b").getBytes(StandardCharsets.UTF_8)).substring(0, 16);
        String cached = store.kv.get(ck);
        if (cached != null) return cached;
        String from = e.getString("f");
        byte[] body = unb64(e.getString("b"));
        SessionCipher sc = new SessionCipher(store, addr(from));
        byte[] pt = e.getInt("t") == CiphertextMessage.PREKEY_TYPE ? sc.decrypt(new PreKeySignalMessage(body)) : sc.decrypt(new SignalMessage(body));
        String s = new String(pt, StandardCharsets.UTF_8);
        store.kv.put(ck, s);
        // TOFU для входящих: незнакомое устройство после первого контакта — предупреждение
        if (sender != null) {
            store.kv.put("devuser:" + from, sender);
            JSONObject trust = jget("trust:" + sender);
            IdentityKey ik = store.getIdentity(addr(from));
            String ikb = ik == null ? null : b64(ik.serialize());
            if (ikb != null && !ikb.equals(trust.optString(from, null))) {
                // Первый контакт фиксирует devicesFor() по полному списку устройств; здесь только сверяем с уже доверенными
                if (store.kv.get("trust:" + sender) != null) { JSONObject p = jget("pending:" + sender); p.put(from, ikb); store.kv.put("pending:" + sender, p.toString()); }
            }
        }
        return s;
    }

    /** Коды безопасности (по одному на устройство собеседника): [deviceId, digits, scannable-b64]. */
    public List<String[]> safetyNumbers(String peer) throws Exception {
        List<String[]> out = new ArrayList<>();
        JSONObject trust = jget("trust:" + peer), pend = jget("pending:" + peer);
        for (Iterator<String> it = pend.keys(); it.hasNext(); ) { String d = it.next(); trust.put(d, pend.getString(d)); }
        IdentityKey mine = store.getIdentityKeyPair().getPublicKey();
        for (Iterator<String> it = trust.keys(); it.hasNext(); ) {
            String d = it.next();
            Fingerprint f = new NumericFingerprintGenerator(5200).createFor(2, uuidBytes(net.uid()), mine, uuidBytes(peer), new IdentityKey(unb64(trust.getString(d))));
            out.add(new String[]{d, f.getDisplayableFingerprint().getDisplayText(), b64(f.getScannableFingerprint().getSerialized())});
        }
        return out;
    }

    /** Сравнивает отсканированный QR собеседника с нашими кодами; true — совпало, ключи помечаются проверенными. */
    public boolean verifyScanned(String peer, byte[] scanned) throws Exception {
        JSONObject trust = jget("trust:" + peer), pend = jget("pending:" + peer);
        for (Iterator<String> it = pend.keys(); it.hasNext(); ) { String d = it.next(); trust.put(d, pend.getString(d)); }
        IdentityKey mine = store.getIdentityKeyPair().getPublicKey();
        for (Iterator<String> it = trust.keys(); it.hasNext(); ) {
            String d = it.next();
            Fingerprint f = new NumericFingerprintGenerator(5200).createFor(2, uuidBytes(net.uid()), mine, uuidBytes(peer), new IdentityKey(unb64(trust.getString(d))));
            try {
                if (f.getScannableFingerprint().compareTo(scanned)) {
                    if (hasPendingChange(peer)) acceptKeys(peer);
                    store.kv.put("verified:" + peer, jget("trust:" + peer).toString());
                    return true;
                }
            } catch (Exception ignored) { }
        }
        return false;
    }

    public boolean isVerified(String peer) {
        String v = store.kv.get("verified:" + peer);
        return v != null && v.equals(store.kv.get("trust:" + peer));
    }

    static byte[] uuidBytes(String u) {
        UUID x = UUID.fromString(u);
        return ByteBuffer.allocate(16).putLong(x.getMostSignificantBits()).putLong(x.getLeastSignificantBits()).array();
    }

    static String sha256hex(byte[] b) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-256").digest(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : h) sb.append(String.format(java.util.Locale.ROOT, "%02x", x));
        return sb.toString();
    }

    // --- файлы: AES-256-GCM из JCA ---
    /** Возвращает {ciphertext, key, iv}. */
    public static byte[][] encryptFile(byte[] plain) throws Exception {
        byte[] key = new byte[32], iv = new byte[12];
        SecureRandom r = new SecureRandom(); r.nextBytes(key); r.nextBytes(iv);
        javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "AES"), new javax.crypto.spec.GCMParameterSpec(128, iv));
        return new byte[][]{c.doFinal(plain), key, iv};
    }

    public static byte[] decryptFile(byte[] ct, byte[] key, byte[] iv, String sha256) throws Exception {
        javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        c.init(javax.crypto.Cipher.DECRYPT_MODE, new javax.crypto.spec.SecretKeySpec(key, "AES"), new javax.crypto.spec.GCMParameterSpec(128, iv));
        byte[] p = c.doFinal(ct);
        if (sha256 != null && !sha256.equals(sha256hex(p))) throw new SecurityException("Файл повреждён: контрольная сумма не совпала");
        return p;
    }

    /**
     * Выход из аккаунта: ключи устройства и сессии удаляются (новый вход = новое устройство),
     * но кэш расшифровки, свои отправленные тексты и доверенные ключи собеседников сохраняются,
     * чтобы история не пропала после повторного входа того же аккаунта.
     */
    public synchronized void wipe() {
        for (String k : new ArrayList<>(store.kv.keys()))
            if (!k.startsWith("pt") && !k.startsWith("trust:") && !k.startsWith("pending:") && !k.startsWith("verified:") && !k.equals("owner")) store.kv.remove(k);
        claimedAt.clear();
    }

    /** Полное удаление всех данных шифрования (сброс). */
    public synchronized void wipeAll() {
        for (String k : new ArrayList<>(store.kv.keys())) store.kv.remove(k);
        claimedAt.clear();
    }
}
