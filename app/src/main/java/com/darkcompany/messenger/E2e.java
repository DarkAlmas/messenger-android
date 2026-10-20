package com.darkcompany.messenger;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashSet;
import java.util.Set;

/** Android-обвязка E2eCore: состояние протокола в EncryptedSharedPreferences под ключом Android Keystore. */
public final class E2e {
    private static E2eCore core;
    /** Ошибка Android Keystore: ключи недоступны. Новую identity в этом случае НЕ создаём. */
    public static volatile String storeError;
    private static E2eGroup group;
    public static synchronized E2eGroup group(Context ctx) { E2eCore c = get(ctx); if (group == null) group = new E2eGroup(c, net); return group; }
    private static E2eCore.Net net;

    public static synchronized E2eCore get(Context ctx) {
        if (core == null) {
            final Context c = ctx.getApplicationContext();
            final SharedPreferences sp = open(c);
            E2eStore.Kv kv = new E2eStore.Kv() {
                public String get(String k) { return sp.getString(k, null); }
                public void put(String k, String v) { sp.edit().putString(k, v).apply(); }
                public void remove(String k) { sp.edit().remove(k).apply(); }
                public Set<String> keys() { return new HashSet<>(sp.getAll().keySet()); }
            };
            final Api api = Api.get(c);
            net = new E2eCore.Net() {
                public String call(String m, String p, String j, boolean min) throws Exception { return api.raw(m, p, j, min); }
                public String callPrefer(String m, String p, String j, String pref) throws Exception { return api.rawPrefer(m, p, j, pref); }
                public String accessToken() throws Exception { return api.accessToken(); }
                public String uid() { return api.uid(); }
            };
            core = new E2eCore(new E2eStore(kv), net);
        }
        return core;
    }

    /** Ключ AES256-GCM в Android Keystore; при сбое Keystore — только память (ключи не пишутся на диск открыто). */
    private static SharedPreferences open(Context c) {
        try {
            androidx.security.crypto.MasterKey mk = new androidx.security.crypto.MasterKey.Builder(c)
                    .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM).build();
            return androidx.security.crypto.EncryptedSharedPreferences.create(c, "msgr_e2e", mk,
                    androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception e) {
            // Не удаляем сохранённые ключи: сбой может быть временным. Шифрование выключено до успешного открытия.
            storeError = "Хранилище ключей Android недоступно (" + e.getClass().getSimpleName() + "). Шифрование временно не работает — перезапустите приложение.";
            return new MemPrefs();
        }
    }

    /** Подготовка ключей в фоне; ошибки (нет прав, сеть) — тихо, повторим позже. */
    public static boolean tryReady(Context c) {
        synchronized (E2e.class) {
            if (storeError != null) { // повторная попытка открыть Keystore
                storeError = null; core = null; group = null;
                get(c);
                if (storeError != null) { lastError = storeError; return false; }
            }
        }
        E2eCore core = get(c);
        try {
            core.lastRebindKind = null;
            core.ensureReady(); lastError = null; rebindBlocked = false;
            String k = core.lastRebindKind; // rebind отказал (RB001/RB003), зарегистрировано новое устройство — сообщаем один раз
            if (k != null && !k.isEmpty() && !"active".equals(k)) rebindNotice = E2eCore.rebindMessage(k);
            return true;
        }
        catch (E2eCore.RebindBlocked e) { rebindBlocked = true; lastError = e.getMessage(); return false; }
        catch (Exception e) {
            if ("".equals(core.lastRebindKind)) { lastError = E2eCore.rebindMessage(""); rebindBlocked = true; return false; } lastError = "Не удалось подготовить ключи шифрования этого устройства: " + Ui.err(e); return false; }
    }
    private static volatile long lastCheck = 0;
    /** Проверка ключей не чаще раза в 10 минут (вызывать при старте, onResume и по таймеру). force — сразу. */
    public static boolean ensureFresh(Context c, boolean force) {
        long now = System.currentTimeMillis();
        if (!force && lastError == null && now - lastCheck < 10 * 60 * 1000) return true;
        lastCheck = now;
        return tryReady(c);
    }
    /** Причина, по которой своё устройство не готово к шифрованию (null — всё в порядке). */
    public static volatile String lastError;
    /** rebind_device: прежний сеанс активен (RB002) или нет сети — ждём решения «Подождать / Новое устройство». */
    public static volatile boolean rebindBlocked = false;
    /** Одноразовое сообщение об отказе rebind_device (новое устройство уже зарегистрировано). */
    public static volatile String rebindNotice;
}
