package com.darkcompany.messenger;

import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** SharedPreferences только в памяти (без записи на диск). */
final class MemPrefs implements SharedPreferences {
    private final Map<String, Object> map = new HashMap<>();
    private final Set<OnSharedPreferenceChangeListener> listeners = new HashSet<>();

    @Override public synchronized Map<String, ?> getAll() { return new HashMap<>(map); }
    @Override public synchronized String getString(String k, String d) { Object v = map.get(k); return v instanceof String ? (String) v : d; }
    @SuppressWarnings("unchecked")
    @Override public synchronized Set<String> getStringSet(String k, Set<String> d) { Object v = map.get(k); return v instanceof Set ? new HashSet<>((Set<String>) v) : d; }
    @Override public synchronized int getInt(String k, int d) { Object v = map.get(k); return v instanceof Integer ? (Integer) v : d; }
    @Override public synchronized long getLong(String k, long d) { Object v = map.get(k); return v instanceof Long ? (Long) v : d; }
    @Override public synchronized float getFloat(String k, float d) { Object v = map.get(k); return v instanceof Float ? (Float) v : d; }
    @Override public synchronized boolean getBoolean(String k, boolean d) { Object v = map.get(k); return v instanceof Boolean ? (Boolean) v : d; }
    @Override public synchronized boolean contains(String k) { return map.containsKey(k); }
    @Override public Editor edit() { return new Ed(); }
    @Override public synchronized void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { listeners.add(l); }
    @Override public synchronized void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { listeners.remove(l); }

    private final class Ed implements Editor {
        private final Map<String, Object> put = new HashMap<>();
        private final Set<String> remove = new HashSet<>();
        private boolean clear;

        @Override public Editor putString(String k, String v) { if (v == null) remove.add(k); else put.put(k, v); return this; }
        @Override public Editor putStringSet(String k, Set<String> v) { if (v == null) remove.add(k); else put.put(k, new HashSet<>(v)); return this; }
        @Override public Editor putInt(String k, int v) { put.put(k, v); return this; }
        @Override public Editor putLong(String k, long v) { put.put(k, v); return this; }
        @Override public Editor putFloat(String k, float v) { put.put(k, v); return this; }
        @Override public Editor putBoolean(String k, boolean v) { put.put(k, v); return this; }
        @Override public Editor remove(String k) { remove.add(k); return this; }
        @Override public Editor clear() { clear = true; return this; }
        @Override public boolean commit() {
            synchronized (MemPrefs.this) {
                if (clear) map.clear();
                for (String k : remove) map.remove(k);
                map.putAll(put);
            }
            return true;
        }
        @Override public void apply() { commit(); }
    }
}
