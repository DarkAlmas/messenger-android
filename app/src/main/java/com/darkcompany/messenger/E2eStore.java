package com.darkcompany.messenger;

import org.signal.libsignal.protocol.IdentityKey;
import org.signal.libsignal.protocol.IdentityKeyPair;
import org.signal.libsignal.protocol.InvalidKeyIdException;
import org.signal.libsignal.protocol.NoSessionException;
import org.signal.libsignal.protocol.ReusedBaseKeyException;
import org.signal.libsignal.protocol.SignalProtocolAddress;
import org.signal.libsignal.protocol.ecc.ECPublicKey;
import org.signal.libsignal.protocol.groups.state.SenderKeyRecord;
import org.signal.libsignal.protocol.state.KyberPreKeyRecord;
import org.signal.libsignal.protocol.state.PreKeyRecord;
import org.signal.libsignal.protocol.state.SessionRecord;
import org.signal.libsignal.protocol.state.SignalProtocolStore;
import org.signal.libsignal.protocol.state.SignedPreKeyRecord;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Хранилище состояния протокола Signal поверх ключ-значение.
 * На Android Kv — EncryptedSharedPreferences с ключом из Android Keystore; в тестах — память.
 * Вся криптография — libsignal, здесь только сериализация.
 */
public final class E2eStore implements SignalProtocolStore {
    public interface Kv {
        String get(String k);
        void put(String k, String v);
        void remove(String k);
        Set<String> keys();
    }

    final Kv kv;
    public E2eStore(Kv kv) { this.kv = kv; }

    static String b64(byte[] b) { return Base64.getEncoder().encodeToString(b); }
    static byte[] unb64(String s) { return Base64.getDecoder().decode(s); }
    private static String addr(SignalProtocolAddress a) { return a.getName() + "." + a.getDeviceId(); }

    // --- identity ---
    @Override public IdentityKeyPair getIdentityKeyPair() {
        try { return new IdentityKeyPair(unb64(kv.get("idk"))); } catch (Exception e) { throw new IllegalStateException("no identity", e); }
    }
    @Override public int getLocalRegistrationId() { return Integer.parseInt(kv.get("reg")); }

    @Override public IdentityChange saveIdentity(SignalProtocolAddress a, IdentityKey k) {
        String old = kv.get("ident:" + a.getName());
        String nw = b64(k.serialize());
        kv.put("ident:" + a.getName(), nw);
        return old != null && !old.equals(nw) ? IdentityChange.REPLACED_EXISTING : IdentityChange.NEW_OR_UNCHANGED;
    }

    /** Устройство = сессия; его identity никогда не меняется легитимно, поэтому другой ключ — всегда отказ. */
    @Override public boolean isTrustedIdentity(SignalProtocolAddress a, IdentityKey k, Direction d) {
        String old = kv.get("ident:" + a.getName());
        return old == null || old.equals(b64(k.serialize()));
    }
    @Override public IdentityKey getIdentity(SignalProtocolAddress a) {
        String s = kv.get("ident:" + a.getName());
        try { return s == null ? null : new IdentityKey(unb64(s)); } catch (Exception e) { return null; }
    }

    // --- one-time prekeys ---
    @Override public PreKeyRecord loadPreKey(int id) throws InvalidKeyIdException {
        String s = kv.get("pk:" + id);
        if (s == null) throw new InvalidKeyIdException("no prekey " + id);
        try { return new PreKeyRecord(unb64(s)); } catch (Exception e) { throw new InvalidKeyIdException(e); }
    }
    @Override public void storePreKey(int id, PreKeyRecord r) { kv.put("pk:" + id, b64(r.serialize())); }
    @Override public boolean containsPreKey(int id) { return kv.get("pk:" + id) != null; }
    @Override public void removePreKey(int id) { kv.remove("pk:" + id); }

    // --- signed prekeys ---
    @Override public SignedPreKeyRecord loadSignedPreKey(int id) throws InvalidKeyIdException {
        String s = kv.get("spk:" + id);
        if (s == null) throw new InvalidKeyIdException("no signed prekey " + id);
        try { return new SignedPreKeyRecord(unb64(s)); } catch (Exception e) { throw new InvalidKeyIdException(e); }
    }
    @Override public List<SignedPreKeyRecord> loadSignedPreKeys() {
        List<SignedPreKeyRecord> l = new ArrayList<>();
        for (String k : kv.keys()) if (k.startsWith("spk:")) try { l.add(new SignedPreKeyRecord(unb64(kv.get(k)))); } catch (Exception ignored) { }
        return l;
    }
    @Override public void storeSignedPreKey(int id, SignedPreKeyRecord r) { kv.put("spk:" + id, b64(r.serialize())); }
    @Override public boolean containsSignedPreKey(int id) { return kv.get("spk:" + id) != null; }
    @Override public void removeSignedPreKey(int id) { kv.remove("spk:" + id); }

    // --- kyber (PQXDH, last-resort key) ---
    @Override public KyberPreKeyRecord loadKyberPreKey(int id) throws InvalidKeyIdException {
        String s = kv.get("kpk:" + id);
        if (s == null) throw new InvalidKeyIdException("no kyber prekey " + id);
        try { return new KyberPreKeyRecord(unb64(s)); } catch (Exception e) { throw new InvalidKeyIdException(e); }
    }
    @Override public List<KyberPreKeyRecord> loadKyberPreKeys() {
        List<KyberPreKeyRecord> l = new ArrayList<>();
        for (String k : kv.keys()) if (k.startsWith("kpk:")) try { l.add(new KyberPreKeyRecord(unb64(kv.get(k)))); } catch (Exception ignored) { }
        return l;
    }
    @Override public void storeKyberPreKey(int id, KyberPreKeyRecord r) { kv.put("kpk:" + id, b64(r.serialize())); }
    @Override public boolean containsKyberPreKey(int id) { return kv.get("kpk:" + id) != null; }
    /** Last-resort ключ переиспользуется, но одна и та же базовая пара не должна приниматься дважды. */
    @Override public void markKyberPreKeyUsed(int id, int signedId, ECPublicKey base) throws ReusedBaseKeyException {
        String k = "kused:" + id + ":" + signedId + ":" + b64(base.serialize());
        if (kv.get(k) != null) throw new ReusedBaseKeyException("reused base key");
        kv.put(k, "1");
    }

    // --- sessions ---
    @Override public SessionRecord loadSession(SignalProtocolAddress a) {
        String s = kv.get("sess:" + addr(a));
        try { return s == null ? new SessionRecord() : new SessionRecord(unb64(s)); } catch (Exception e) { return new SessionRecord(); }
    }
    @Override public List<SessionRecord> loadExistingSessions(List<SignalProtocolAddress> as) throws NoSessionException {
        List<SessionRecord> l = new ArrayList<>();
        for (SignalProtocolAddress a : as) {
            String s = kv.get("sess:" + addr(a));
            if (s == null) throw new NoSessionException("no session for " + a);
            try { l.add(new SessionRecord(unb64(s))); } catch (Exception e) { throw new NoSessionException("bad session"); }
        }
        return l;
    }
    @Override public List<Integer> getSubDeviceSessions(String name) {
        List<Integer> l = new ArrayList<>();
        for (String k : kv.keys()) if (k.startsWith("sess:" + name + ".")) {
            int d = Integer.parseInt(k.substring(k.lastIndexOf('.') + 1));
            if (d != 1) l.add(d);
        }
        return l;
    }
    @Override public void storeSession(SignalProtocolAddress a, SessionRecord r) { kv.put("sess:" + addr(a), b64(r.serialize())); }
    @Override public boolean containsSession(SignalProtocolAddress a) {
        String s = kv.get("sess:" + addr(a));
        if (s == null) return false;
        try { return new SessionRecord(unb64(s)).hasSenderChain(); } catch (Exception e) { return false; }
    }
    @Override public void deleteSession(SignalProtocolAddress a) { kv.remove("sess:" + addr(a)); }
    @Override public void deleteAllSessions(String name) {
        for (String k : new ArrayList<>(kv.keys())) if (k.startsWith("sess:" + name + ".")) kv.remove(k);
    }

    // --- sender keys (группы пока не шифруются, но интерфейс обязателен) ---
    @Override public void storeSenderKey(SignalProtocolAddress a, UUID d, SenderKeyRecord r) { kv.put("sk:" + addr(a) + ":" + d, b64(r.serialize())); }
    @Override public SenderKeyRecord loadSenderKey(SignalProtocolAddress a, UUID d) {
        String s = kv.get("sk:" + addr(a) + ":" + d);
        try { return s == null ? null : new SenderKeyRecord(unb64(s)); } catch (Exception e) { return null; }
    }
}
