package com.darkcompany.messenger;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ChatActivity extends AppCompatActivity {
    /** Открытый сейчас чат (уведомление о нём не показываем). */
    static volatile String openChat = null;
    private Recorder recorder;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler h = new Handler(Looper.getMainLooper());
    private final Map<String, String> names = new java.util.concurrent.ConcurrentHashMap<>();
    private String chatId;
    private LinearLayout box;
    private ScrollView scroll;
    private boolean running = false;
    private String kind, peerId;
    private volatile boolean e2e = false, e2eChecked = false;
    private TextView banner;
    /** Расшифрованные полезные нагрузки E2E-сообщений: id -> payload (или {"err":...}). */
    private final Map<String, JSONObject> dec = new java.util.concurrent.ConcurrentHashMap<>();
    private final androidx.activity.result.ActivityResultLauncher<com.journeyapps.barcodescanner.ScanOptions> safetyScan =
            registerForActivityResult(new com.journeyapps.barcodescanner.ScanContract(), r -> { if (r.getContents() != null) onSafetyScanned(r.getContents()); });
    private final androidx.activity.result.ActivityResultLauncher<String> picker =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.GetContent(), uri -> { if (uri != null) sendFile(uri); });

    // Realtime: обновление по событиям WebSocket; опрос — только редкий запасной (или частый, пока сокет не подключён)
    private Realtime rt;
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!running) return;
            fetch();
            h.postDelayed(this, rt != null && rt.isJoined() ? 30000 : 5000);
        }
    };
    private final Runnable rtFetch = this::fetch;
    /** Пока отправка заблокирована (нет ключей у нас или у собеседника) — перепроверка каждые 20 с, разблокировка автоматически. */
    private final Runnable keyWatch = new Runnable() {
        @Override public void run() {
            if (!running) return;
            io.execute(() -> E2e.ensureFresh(ChatActivity.this, false));
            if (e2eChecked && !e2e) recheckE2e();
            h.postDelayed(this, 20000);
        }
    };
    private void onRealtimeChange() { h.removeCallbacks(rtFetch); h.postDelayed(rtFetch, 150); }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        chatId = getIntent().getStringExtra("chat_id");
        if (chatId == null || !Api.get(this).hasSession()) { finish(); return; }
        setTitle(getIntent().getStringExtra("title"));
        kind = getIntent().getStringExtra("kind");
        peerId = getIntent().getStringExtra("peer_id");
        header();
        if (peerId != null) io.execute(() -> { if (Avatars.refresh(this, java.util.Collections.singleton(peerId), 0)) runOnUiThread(this::header); });
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        banner = Ui.text(this, "", 14);
        banner.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 10));
        banner.setOnClickListener(v -> {
            if ("direct".equals(kind) && e2e) showSafety();
            else if (isGroup() && e2e) { E2e.group(this).clearWarnings(chatId); updateBanner(); }
        });
        root.addView(banner, new LinearLayout.LayoutParams(-1, -2));
        updateBanner();
        checkE2e();
        scroll = new ScrollView(this);
        box = Ui.column(this);
        scroll.addView(box);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.setBackgroundColor(Ui.attr(this, com.google.android.material.R.attr.colorSurfaceContainerLowest));
        box.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 8), Ui.dp(this, 6));
        bar.setBackgroundColor(Ui.attr(this, com.google.android.material.R.attr.colorSurfaceContainer));
        final EditText in = Ui.input(this, "Сообщение");
        Ui.pill(in); in.setSingleLine(false); in.setMaxLines(5);
        in.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(4000)});
        android.view.View send = Ui.sendButton(this);
        com.google.android.material.button.MaterialButton attach = new com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle);
        attach.setText("📎");
        attach.setTextSize(20);
        attach.setPadding(0, 0, 0, 0);
        attach.setInsetTop(0); attach.setInsetBottom(0);
        attach.setContentDescription("Прикрепить файл");
        attach.setOnClickListener(v -> picker.launch("*/*"));
        bar.addView(attach, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        bar.addView(in, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)); slp.leftMargin = Ui.dp(this, 6);
        final com.google.android.material.button.MaterialButton mic = new com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialIconButtonStyle);
        mic.setText("🎤"); mic.setTextSize(20); mic.setPadding(0, 0, 0, 0); mic.setInsetTop(0); mic.setInsetBottom(0);
        mic.setContentDescription("Голосовое сообщение: удерживайте");
        bar.addView(mic, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        bar.addView(send, slp);
        replyBar = Ui.text(this, "", 13);
        replyBar.setMaxLines(2); replyBar.setEllipsize(android.text.TextUtils.TruncateAt.END);
        replyBar.setPadding(Ui.dp(this, 16), Ui.dp(this, 6), Ui.dp(this, 16), Ui.dp(this, 6));
        replyBar.setBackgroundColor(Ui.attr(this, com.google.android.material.R.attr.colorSurfaceContainerHigh));
        replyBar.setVisibility(android.view.View.GONE);
        replyBar.setContentDescription("Отменить ответ");
        replyBar.setOnClickListener(v -> { replyTo = null; replyBar.setVisibility(android.view.View.GONE); });
        root.addView(replyBar, new LinearLayout.LayoutParams(-1, -2));
        root.addView(bar, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
        Ui.fitSystemBars(this, root); // edge-to-edge (Android 15+): не прятать контент под тулбаром/панелями
        send.setOnClickListener(v -> {
            final String t = in.getText().toString().trim();
            if (t.isEmpty()) return;
            in.setText("");
            final String rt = replyTo;
            replyTo = null; replyBar.setVisibility(android.view.View.GONE);
            io.execute(() -> {
                try {
                    Api api = Api.get(this);
                    post(new JSONObject().put("v", 1).put("t", t), rt,
                            new JSONObject().put("chat_id", chatId).put("sender_id", api.uid()).put("body", t));
                    fetch();
                } catch (Exception e) {
                    Ui.toast(this, Ui.err(e));
                    // Текст не теряется: возвращаем его в поле ввода (если там пусто)
                    runOnUiThread(() -> { if (in.getText().length() == 0) { in.setText(t); in.setSelection(t.length()); } });
                    if (e instanceof Api.AuthException) finish();
                }
            });
        });
        // Голосовые и кружки: удерживать — запись, влево — отмена, вверх — замок, короткое нажатие — голос/кружок
        recorder = new Recorder(this, mic, (data, mime, name, media) -> sendMedia(data, name, mime, media));
        // Офлайн-кэш: последние 100 сообщений видны сразу, даже без интернета
        JSONObject cached = Cache.chat(this, chatId);
        if (cached != null) {
            try {
                JSONObject d = cached.optJSONObject("d");
                if (d != null) for (java.util.Iterator<String> it = d.keys(); it.hasNext(); ) { String k = it.next(); dec.put(k, d.getJSONObject(k)); }
                render(cached.getJSONArray("m"), new JSONArray(), new JSONArray());
            } catch (Exception ignored) { }
        }
    }

    @Override protected void onStart() {
        super.onStart(); if (chatId == null) return; running = true; openChat = chatId; Notify.cancel(this, chatId);
        rt = new Realtime(Api.get(this), chatId, isGroup(), () -> h.post(() -> { if (running) onRealtimeChange(); }),
                () -> { lastDistrib = 0; }); // новая эпоха/новый SKDM: при следующем fetch — раздача ключа и приём SKDM
        if (isGroup()) io.execute(() -> { try { if (rt != null) rt.myDevice = E2e.get(this).deviceId(); } catch (Exception ignored) { } });
        rt.start();
        h.post(poll);
        h.post(keyWatch);
    }
    @Override protected void onStop() { super.onStop(); if (chatId != null && chatId.equals(openChat)) openChat = null; if (recorder != null) recorder.cancel(); running = false; h.removeCallbacks(keyWatch); h.removeCallbacks(poll); h.removeCallbacks(rtFetch); if (rt != null) { rt.stop(); rt = null; } }

    private static final String[] EMOJI = {"👍", "❤️", "😂", "😮", "😢", "🔥"};
    private volatile String lastSig = null;
    private int lastCount = 0;
    private final Map<String, JSONObject> byId = new java.util.concurrent.ConcurrentHashMap<>();
    private String replyTo = null;
    private TextView replyBar;

    private static String fmtName(JSONObject p) {
        String un = p.optString("username", "");
        String dn = p.isNull("display_name") ? "" : p.optString("display_name", "").trim();
        if (dn.isEmpty() || dn.equals(un)) return "@" + un;
        return dn + " @" + un;
    }

    private void fetch() {
        io.execute(() -> {
            try {
                Api api = Api.get(this);
                JSONArray desc = api.select("messages?select=id,sender_id,sender_device,key_epoch,body,ciphertext,created_at,edited_at,reply_to,attachment_path,attachment_name,attachment_size&chat_id=eq." + chatId + "&order=created_at.desc&limit=" + limit);
                JSONArray arr = new JSONArray();
                for (int i = desc.length() - 1; i >= 0; i--) arr.put(desc.get(i));
                // Расшифровка E2E (один раз на сообщение; Double Ratchet не позволяет расшифровать повторно)
                boolean gotSkdm = false;
                if (isGroup() && e2e && System.currentTimeMillis() - lastDistrib > 60000) { // новым участникам — свой ключ
                    lastDistrib = System.currentTimeMillis();
                    try { E2e.group(this).distribute(chatId); } catch (Api.AuthException ae) { throw ae; } catch (Exception ignored) { }
                }
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject m = arr.getJSONObject(i);
                    String mid = m.optString("id");
                    if (m.isNull("ciphertext")) continue;
                    String sigCt = m.optJSONObject("ciphertext") == null ? "" : m.getJSONObject("ciphertext").toString();
                    if (dec.containsKey(mid) && sigCt.equals(decSig.get(mid)) && !canRetry(mid)) continue; // правка меняет шифротекст — расшифровать заново; сбой — до 3 попыток
                    if (isGroup()) {
                        if (!gotSkdm) { gotSkdm = true; try { E2e.group(this).receive(chatId); } catch (Api.AuthException ae) { throw ae; } catch (Exception ignored) { } }
                        try {
                            String p = E2e.group(this).decrypt(chatId, mid, m.isNull("sender_id") ? null : m.optString("sender_id"),
                                    m.isNull("sender_device") ? null : m.optString("sender_device"), m.optInt("key_epoch", -1), m.getJSONObject("ciphertext"));
                            dec.put(mid, new JSONObject(p)); decSig.put(mid, sigCt); failCount.remove(mid);
                        } catch (E2eGroup.NoKeyException nk) {
                            dec.remove(mid); // ключ ещё в пути — попробуем при следующем обновлении
                        } catch (Exception ex) {
                            failed(mid, ex, sigCt);
                        }
                        continue;
                    }
                    try {
                        String p = E2e.get(this).decrypt(mid, m.isNull("sender_id") ? null : m.optString("sender_id"), m.getJSONObject("ciphertext"));
                        dec.put(mid, p == null ? new JSONObject().put("err", "other") : new JSONObject(p));
                        decSig.put(mid, sigCt); failCount.remove(mid);
                    } catch (Exception ex) {
                        failed(mid, ex, sigCt);
                    }
                }
                Set<String> need = new HashSet<>();
                StringBuilder ids = new StringBuilder();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject m = arr.getJSONObject(i);
                    String s = m.optString("sender_id", "");
                    if (!s.isEmpty() && !m.isNull("sender_id") && !names.containsKey(s)) need.add(s);
                    if (ids.length() > 0) ids.append(',');
                    ids.append(m.getString("id"));
                }
                JSONArray reacts = new JSONArray();
                if (ids.length() > 0) reacts = api.select("reactions?select=message_id,user_id,emoji&order=created_at.asc&message_id=in.(" + ids + ")");
                JSONArray reads = new JSONArray();
                try { String r = api.rpc("chat_read_state", new JSONObject().put("p_chat", chatId)).trim(); if (!r.isEmpty()) reads = new JSONArray(r); }
                catch (Api.AuthException ae) { throw ae; } catch (Exception ignored) { }
                if (!need.isEmpty()) {
                    JSONArray pr = api.select("profiles?select=id,username,display_name&id=in.(" + String.join(",", need) + ")");
                    for (int i = 0; i < pr.length(); i++) { JSONObject p = pr.getJSONObject(i); names.put(p.getString("id"), fmtName(p)); }
                }
                Cache.putChat(this, chatId, arr, dec);
                if (arr.length() > 0) {
                    JSONObject last = arr.getJSONObject(arr.length() - 1);
                    JSONObject lp = last.isNull("ciphertext") ? null : dec.get(last.optString("id"));
                    if (lp != null && !lp.has("err")) Cache.putPreview(this, chatId, last.optString("created_at"), Cache.previewOf(lp));
                }
                markDelivered(arr, api.uid());
                final String sig = arr.toString() + reacts + reads + dec.size();
                if (sig.equals(lastSig)) return;
                lastSig = sig;
                final JSONArray fin = arr, fr = reacts, frd = reads;
                runOnUiThread(() -> render(fin, fr, frd));
            } catch (Exception e) {
                if (e instanceof Api.AuthException) runOnUiThread(this::finish);
                else if (System.currentTimeMillis() - lastFetchErr > 30000) { lastFetchErr = System.currentTimeMillis(); Ui.toast(this, "Не удалось загрузить сообщения: " + Ui.err(e)); }
            }
        });
    }
    private volatile long lastFetchErr = 0, lastDistrib = 0;
    private volatile int limit = 200;
    private volatile boolean keepScroll = false;
    private final Map<String, Integer> failCount = new java.util.concurrent.ConcurrentHashMap<>();
    private static final int MAX_DECRYPT_TRIES = 3;
    private boolean canRetry(String mid) { JSONObject p = dec.get(mid); return p != null && "fail".equals(p.optString("err")) && failCount.getOrDefault(mid, 0) < MAX_DECRYPT_TRIES; }
    private void failed(String mid, Exception ex, String sigCt) {
        failCount.merge(mid, 1, Integer::sum);
        try { dec.put(mid, new JSONObject().put("err", "fail").put("why", ex.getClass().getSimpleName())); } catch (Exception ignored) { }
        decSig.put(mid, sigCt);
        android.util.Log.w("E2E", "decrypt " + mid + " failed: " + ex);
    }
    private final Map<String, String> decSig = new java.util.concurrent.ConcurrentHashMap<>();
    private boolean isGroup() { return "group".equals(kind); }

    private void render(JSONArray arr, JSONArray reacts, JSONArray reads) {
        String me = Api.get(this).uid();
        // Последнее прочтение собеседниками (для ✓✓)
        // ✓✓ — только если ВСЕ остальные участники (в личном чате — собеседник) прочитали до этого сообщения
        long othersRead = Long.MAX_VALUE; boolean anyOther = false;
        for (int i = 0; i < reads.length(); i++) {
            JSONObject r = reads.optJSONObject(i);
            if (r == null || me.equals(r.optString("user_id"))) continue;
            anyOther = true;
            long t = r.isNull("last_read_at") ? 0 : Ui.instant(r.optString("last_read_at"));
            othersRead = Math.min(othersRead, t);
        }
        if (!anyOther) othersRead = 0;
        long othersDlv = Long.MAX_VALUE; boolean anyD = false;
        for (int i = 0; i < reads.length(); i++) {
            JSONObject r = reads.optJSONObject(i);
            if (r == null || me.equals(r.optString("user_id"))) continue;
            anyD = true;
            long t = Math.max(r.isNull("last_delivered_at") ? 0 : Ui.instant(r.optString("last_delivered_at")), r.isNull("last_read_at") ? 0 : Ui.instant(r.optString("last_read_at")));
            othersDlv = Math.min(othersDlv, t);
        }
        if (!anyD) othersDlv = 0;
        String lastIncoming = null;
        Map<String, Map<String, int[]>> rmap = new HashMap<>(); // msg -> emoji -> {count, mine}
        for (int i = 0; i < reacts.length(); i++) {
            JSONObject r = reacts.optJSONObject(i); if (r == null) continue;
            Map<String, int[]> em = rmap.computeIfAbsent(r.optString("message_id"), k -> new java.util.LinkedHashMap<>());
            int[] c = em.computeIfAbsent(r.optString("emoji"), k -> new int[2]);
            c[0]++; if (me.equals(r.optString("user_id"))) c[1] = 1;
        }
        byId.clear();
        for (int i = 0; i < arr.length(); i++) { JSONObject m = arr.optJSONObject(i); if (m != null) byId.put(m.optString("id"), m); }
        box.removeAllViews();
        if (arr.length() >= limit) { // M1: старые сообщения не теряются — подгрузка порциями по 200
            com.google.android.material.button.MaterialButton more = new com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
            more.setText("Загрузить более ранние сообщения");
            more.setOnClickListener(v -> { limit += 200; keepScroll = true; lastSig = null; fetch(); });
            box.addView(more);
        }
        boolean incoming = false;
        for (int i = 0; i < arr.length(); i++) {
            final JSONObject m = arr.optJSONObject(i);
            if (m == null) continue;
            final String id = m.optString("id");
            final boolean mine = me.equals(m.optString("sender_id"));
            String who = mine ? null : (m.isNull("sender_id") ? "удалённый аккаунт" : names.getOrDefault(m.optString("sender_id"), "участник"));
            LinearLayout extra = new LinearLayout(this);
            extra.setOrientation(LinearLayout.VERTICAL);
            if (!m.isNull("reply_to")) {
                JSONObject q = byId.get(m.optString("reply_to"));
                TextView qt = Ui.text(this, "↪ " + (q == null ? "сообщение" : quoteOf(q, me)), 13);
                qt.setMaxLines(2); qt.setEllipsize(android.text.TextUtils.TruncateAt.END);
                qt.setAlpha(0.8f);
                qt.setPadding(Ui.dp(this, 8), Ui.dp(this, 2), 0, Ui.dp(this, 4));
                extra.addView(qt);
            }
            final JSONObject pl = m.isNull("ciphertext") ? null : dec.get(id);
            if (pl != null && pl.optJSONObject("f") != null && ("voice".equals(pl.optJSONObject("f").optString("vk")) || "round".equals(pl.optJSONObject("f").optString("vk")))) {
                extra.addView(MediaPlay.view(this, mine, pl.optJSONObject("f"), io));
            } else if (pl != null && pl.optJSONObject("f") != null) {
                final JSONObject f = pl.optJSONObject("f");
                extra.addView(Ui.attachment(this, mine, f.optString("n", "file"), f.optLong("s", 0), v -> openE2eFile(f, !mine)));
            } else if (!m.isNull("attachment_path")) {
                final String ap = m.optString("attachment_path");
                final String an = m.isNull("attachment_name") ? "file" : m.optString("attachment_name");
                extra.addView(Ui.attachment(this, mine, an, m.optLong("attachment_size", 0), v -> openFile(ap, an, !mine)));
            }
            if (!mine) incoming = true;
            String bd = textOf(m);
            String ts = Ui.time(m.optString("created_at", ""));
            if (!m.isNull("edited_at") || (pl != null && pl.optInt("ed", 0) == 1)) ts = "изменено · " + ts;
            if (!m.isNull("ciphertext")) ts = "🔒 " + ts;
            else ts = PLAIN_MARK + " · " + ts; // открытый текст (сайт/старая версия): показываем, но с пометкой
            int tick = 0;
            if (mine) { tick = tickState(Ui.instant(m.optString("created_at")), othersDlv, othersRead); ts += tick == 1 ? "  ✓" : "  ✓✓"; }
            else if (lastIncoming == null || Ui.instant(m.optString("created_at")) > Ui.instant(lastIncoming)) lastIncoming = m.optString("created_at");
            LinearLayout bubble = (LinearLayout) Ui.bubble(this, mine, who, bd, ts, extra.getChildCount() > 0 ? extra : null);
            Map<String, int[]> em = rmap.get(id);
            if (em != null && !em.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<String, int[]> e : em.entrySet()) {
                    if (sb.length() > 0) sb.append("  ");
                    sb.append(e.getKey()).append(e.getValue()[0] > 1 ? " " + e.getValue()[0] : "");
                    if (e.getValue()[1] == 1) sb.append("•");
                }
                TextView rv = Ui.text(this, sb.toString(), 14);
                bubble.addView(rv, bubble.getChildCount() - 1);
            }
            if (tick == 3) colorTicks(bubble);
            bubble.setOnLongClickListener(v -> { actions(m, mine, em); return true; });
            box.addView(bubble);
        }
        if (arr.length() != lastCount) {
            lastCount = arr.length();
            if (keepScroll) { keepScroll = false; scroll.post(() -> scroll.scrollTo(0, 0)); }
            else scroll.post(() -> scroll.fullScroll(android.view.View.FOCUS_DOWN));
        }
        if (lastIncoming != null) { seenUpto = lastIncoming; markRead(); }
    }
    /** created_at последнего показанного входящего сообщения и что уже отправлено в mark_read. */
    private volatile String seenUpto = null, markedUpto = null;
    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) markRead(); }

    private void header() {
        String t = getIntent().getStringExtra("title");
        Avatars.setHeader(this, "direct".equals(kind) ? peerId : null, t == null ? "Чат" : t,
                isGroup() ? "🔒 группа · сквозное шифрование" : "🔒 сквозное шифрование", v -> showPeer());
    }

    private void showPeer() {
        if (peerId == null) return;
        JSONObject p = Avatars.merged(this, peerId);
        String dn = p.isNull("display_name") ? "" : p.optString("display_name", "");
        String un = p.optString("username", "");
        LinearLayout c = Ui.column(this);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.addView(Avatars.view(this, peerId, dn.isEmpty() ? un : dn, 96), new LinearLayout.LayoutParams(Ui.dp(this, 96), Ui.dp(this, 96)));
        TextView n = Ui.text(this, dn.isEmpty() ? "@" + un : dn, 20); n.setTypeface(android.graphics.Typeface.DEFAULT_BOLD); n.setGravity(Gravity.CENTER);
        c.addView(n, Ui.spaced(this));
        if (!dn.isEmpty()) { TextView u = Ui.text(this, "@" + un, 14); u.setGravity(Gravity.CENTER); u.setTextColor(Ui.gray()); c.addView(u); }
        String bio = p.isNull("bio") ? "" : p.optString("bio", "");
        if (!bio.isEmpty()) { TextView b = Ui.text(this, bio, 15); b.setGravity(Gravity.CENTER); c.addView(b, Ui.spaced(this)); }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setView(c)
                .setPositiveButton("Закрыть", null)
                .setNeutralButton("🔒 Код безопасности", (d, w) -> { if (e2e) showSafety(); else Ui.toast(this, "Шифрование ещё не готово"); }).show();
    }

    private String quoteOf(JSONObject q, String me) {
        String who = me.equals(q.optString("sender_id")) ? "Вы" : names.getOrDefault(q.optString("sender_id"), "");
        String b = textOf(q);
        JSONObject pl = q.isNull("ciphertext") ? null : dec.get(q.optString("id"));
        if (b.isEmpty() && pl != null && pl.optJSONObject("f") != null) b = Cache.previewOf(pl);
        if (b.isEmpty() && !q.isNull("attachment_name")) b = "📎 " + q.optString("attachment_name");
        if (q.isNull("ciphertext") && !b.isEmpty()) b = PLAIN_MARK + " " + b;
        return who + ": " + b;
    }

    private void actions(JSONObject m, boolean mine, Map<String, int[]> em) {
        final String id = m.optString("id");
        final String body = m.isNull("ciphertext") ? (m.isNull("body") ? "" : m.optString("body")) : (dec.containsKey(id) && !dec.get(id).has("err") ? dec.get(id).optString("t") : "");
        java.util.List<String> items = new java.util.ArrayList<>();
        items.add("Ответить");
        items.add("Реакция");
        if (!body.isEmpty()) items.add("Копировать");
        // Правка только зашифрованных (через RPC edit_message); открытый текст не правим — иначе он ушёл бы на сервер снова
        if (mine && !body.isEmpty() && !m.isNull("ciphertext") && e2e && ("direct".equals(kind) || isGroup())) items.add("Изменить");
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setItems(items.toArray(new String[0]), (d, w) -> {
                    switch (items.get(w)) {
                        case "Ответить":
                            replyTo = id;
                            replyBar.setText("↪ " + quoteOf(m, Api.get(this).uid()) + "   ✕");
                            replyBar.setVisibility(android.view.View.VISIBLE);
                            break;
                        case "Реакция": pickReaction(id, em); break;
                        case "Копировать":
                            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                            if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("msg", body));
                            Ui.toast(this, "Скопировано");
                            break;
                        case "Изменить": edit(id, body); break;
                    }
                }).show();
    }

    private void pickReaction(String id, Map<String, int[]> em) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("Реакция")
                .setItems(EMOJI, (d, w) -> {
                    final String e = EMOJI[w];
                    final boolean had = em != null && em.containsKey(e) && em.get(e)[1] == 1;
                    io.execute(() -> {
                        try {
                            Api api = Api.get(this);
                            if (had) api.delete("reactions?message_id=eq." + id + "&user_id=eq." + api.uid() + "&emoji=eq." + java.net.URLEncoder.encode(e, "UTF-8"));
                            else api.insert("reactions", new JSONObject().put("message_id", id).put("user_id", api.uid()).put("emoji", e));
                            fetch();
                        } catch (Exception ex) {
                            Ui.toast(this, ex instanceof Api.HttpError && ((Api.HttpError) ex).code == 403 ? "Не больше 10 реакций на сообщение" : Ui.err(ex));
                            if (ex instanceof Api.AuthException) runOnUiThread(this::finish);
                        }
                    });
                }).show();
    }

    private void edit(String id, String body) {
        final EditText in = Ui.input(this, "Сообщение");
        in.setText(body); in.setSingleLine(false); in.setMaxLines(6);
        in.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(4000)});
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("Изменить сообщение").setView(in)
                .setPositiveButton("Сохранить", (d, w) -> {
                    final String t = in.getText().toString().trim();
                    if (t.isEmpty() || t.equals(body)) return;
                    io.execute(() -> {
                        try { editEncrypted(id, new JSONObject().put("v", 1).put("t", t).put("ed", 1)); fetch(); }
                        catch (Exception e) { Ui.toast(this, Ui.err(e)); if (e instanceof Api.AuthException) runOnUiThread(this::finish); }
                    });
                }).setNegativeButton("Отмена", null).show();
    }

    static final String PLAIN_MARK = "⚠ не зашифровано";
    private String textOf(JSONObject m) {
        if (m.isNull("ciphertext")) {
            String b = m.isNull("body") ? "" : m.optString("body", "");
            if (b.isEmpty() && m.isNull("attachment_path")) return "(пустое сообщение)"; // никогда не прячем молча
            return b;
        }
        JSONObject pl = dec.get(m.optString("id"));
        if (pl == null) return isGroup() ? "🔒 Ожидание ключа отправителя…" : "🔒 …";
        if (pl.has("err")) return "other".equals(pl.optString("err")) ? "🔒 Зашифровано для другого устройства"
                : "🔒 Не удалось расшифровать" + (pl.has("why") ? " (" + pl.optString("why") + ")" : "")
                + (failCount.getOrDefault(m.optString("id"), 0) < MAX_DECRYPT_TRIES ? " — повторю попытку" : "");
        String t = pl.optString("t", "");
        if (t.isEmpty() && pl.optJSONObject("f") == null) return "(пустое сообщение)";
        return t;
    }

    private static byte[] readAll(java.io.InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[65536]; int n;
        while ((n = in.read(buf)) > 0) { bo.write(buf, 0, n); if (bo.size() > ChatFiles.MAX) throw new java.io.IOException("Файл больше 25 МБ"); }
        return bo.toByteArray();
    }

    /** Отправка: в E2E-чате только шифротекст; без ключей — открытый текст (с пометкой в UI). Ничего не уходит молча при смене ключа. */
    private void post(JSONObject payload, String rt, JSONObject plainRow) throws Exception {
        Api api = Api.get(this);
        if (!e2eChecked) waitE2e();
        if (!e2e && "direct".equals(kind)) { recheckE2e(); waitE2e(); } // собеседник мог уже обновиться
        if (isGroup()) {
            if (!e2e) { recheckE2e(); waitE2e(); }
            if (!e2e) throw new Exception("Сообщение не отправлено. " + selfErr());
            groupSend(payload, rt, null);
            return;
        }
        if (e2e) {
            E2eCore c = E2e.get(this);
            JSONObject ct;
            try { ct = c.encrypt(peerId, payload.toString()); }
            catch (E2eCore.KeyChangeException k) {
                runOnUiThread(this::updateBanner);
                throw new Exception("Ключ безопасности изменился. Сообщение НЕ отправлено: сверьте код безопасности и примите новый ключ.");
            }
            if (ct == null) {
                e2e = false; runOnUiThread(this::updateBanner);
                throw new Exception("У собеседника больше нет ключей шифрования. Сообщение не отправлено.");
            }
            JSONObject row = new JSONObject().put("chat_id", chatId).put("sender_id", api.uid()).put("body", "").put("ciphertext", ct);
            if (rt != null) row.put("reply_to", rt);
            if (pendingAttachment != null) row.put("attachment_path", pendingAttachment);
            String id = api.insertReturningId("messages", row); // id выдаёт сервер
            c.rememberOwn(id, payload.toString());
            dec.put(id, payload);
        } else {
            // В личном чате открытый текст не отправляем никогда (нет тихого отката на нешифрованную отправку)
            if ("direct".equals(kind)) { recheckE2e(); throw new Exception(selfFail ? "Сообщение не отправлено. " + selfErr()
                    : peerErr != null ? "Сообщение не отправлено: не удалось проверить ключи собеседника (" + peerErr + "). Повторите попытку." : NO_KEYS_MSG); }
            if (plainRow == null) throw new Exception("Шифрование недоступно");
            if (rt != null) plainRow.put("reply_to", rt);
            api.insert("messages", plainRow);
        }
    }

    static final String NO_KEYS_MSG = "Сообщение не отправлено: у собеседника сейчас нет ни одного устройства с ключами шифрования — "
            + "он заходил только с сайта старой версии, со старой версии приложения или вышел из аккаунта. Попросите его открыть приложение (или обновлённый сайт) — "
            + "отправка заработает автоматически, текст сохранён в поле ввода. "
            + "Без шифрования личные сообщения не отправляются — попросите собеседника обновить приложение и войти в нём.";
    /** Повторная проверка ключей (собеседник мог обновить приложение) — в фоне, без отправки. */
    private volatile boolean selfFail = false;
    private volatile String peerErr = null;
    private static String selfErr() { String e = E2e.lastError; return e != null ? e : "Ключи шифрования этого устройства ещё не готовы. Проверьте интернет и попробуйте снова."; }
    private void recheckE2e() { if (e2eChecked && !e2e) { e2eChecked = false; checkE2e(); } }

    /**
     * Отправка/правка в группе: актуальная эпоха из group_devices, SKDM новым устройствам, шифрование своим ключом эпохи.
     * На «stale key epoch» — новая эпоха, новый ключ, раздача и повтор.
     */
    private void groupSend(JSONObject payload, String rt, String editId) throws Exception {
        Api api = Api.get(this);
        E2eGroup g = E2e.group(this);
        String dev = E2e.get(this).deviceId();
        Exception last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            int epoch = g.distribute(chatId);
            lastDistrib = System.currentTimeMillis();
            JSONObject ct = g.encrypt(chatId, epoch, payload.toString());
            JSONObject row = new JSONObject().put("ciphertext", ct).put("key_epoch", epoch).put("sender_device", dev);
            try {
                String id;
                if (editId != null) { editRpc(editId, ct, epoch, dev); id = editId; }
                else {
                    row.put("chat_id", chatId).put("sender_id", api.uid()).put("body", "");
                    if (rt != null) row.put("reply_to", rt);
                    if (pendingAttachment != null) row.put("attachment_path", pendingAttachment);
                    id = api.insertReturningId("messages", row);
                }
                g.rememberOwn(id, ct.getString("sk"), payload.toString());
                dec.put(id, payload); decSig.put(id, ct.toString());
                runOnUiThread(this::updateBanner);
                return;
            } catch (Api.HttpError e) {
                if (e.getMessage() == null || !e.getMessage().contains(E2eGroup.STALE)) throw e;
                last = e; // эпоха сменилась (кто-то вышел/устройство отозвано) — ротация ключа
            }
        }
        throw new Exception("Сообщение не отправлено: ключ группы меняется слишком часто, попробуйте ещё раз.", last);
    }

    /** Правка зашифрованного сообщения: новый шифротекст (личный — всем устройствам, группа — текущей эпохой). */
    private void editEncrypted(String id, JSONObject payload) throws Exception {
        if (isGroup()) { groupSend(payload, null, id); return; }
        E2eCore c = E2e.get(this);
        JSONObject ct;
        try { ct = c.encrypt(peerId, payload.toString()); }
        catch (E2eCore.KeyChangeException k) { runOnUiThread(this::updateBanner); throw new Exception("Ключ безопасности изменился. Правка НЕ отправлена: сверьте код безопасности."); }
        if (ct == null) throw new Exception("У собеседника нет ключей шифрования. Правка не отправлена.");
        editRpc(id, ct, null, null);
        c.rememberOwn(id, payload.toString());
        dec.put(id, payload); decSig.put(id, ct.toString());
    }

    /** RPC edit_message (сервер ставит body='' и edited_at). Если функции ещё нет — PATCH теми же полями. */
    private void editRpc(String id, JSONObject ct, Integer epoch, String dev) throws Exception {
        Api api = Api.get(this);
        JSONObject a = new JSONObject().put("p_id", id).put("p_ciphertext", ct)
                .put("p_key_epoch", epoch == null ? JSONObject.NULL : epoch).put("p_sender_device", dev == null ? JSONObject.NULL : dev);
        try { api.rpc("edit_message", a); }
        catch (Api.HttpError e) {
            String m = String.valueOf(e.getMessage());
            // Фолбэк: функции ещё нет (404/PGRST202) или она падает на сервере (22P02: сейчас real_user() boolean → uuid)
            if (!(e.code == 404 || m.contains("PGRST202") || m.contains("Could not find the function") || m.contains("22P02") || m.contains("invalid input syntax for type uuid"))) throw e;
            JSONObject row = new JSONObject().put("body", "").put("ciphertext", ct);
            if (epoch != null) row.put("key_epoch", epoch).put("sender_device", dev);
            api.update("messages?id=eq." + id + "&sender_id=eq." + api.uid(), row);
        }
    }

    private final Object e2eLock = new Object();
    private void waitE2e() throws InterruptedException {
        synchronized (e2eLock) { long end = System.currentTimeMillis() + 15000; while (!e2eChecked && System.currentTimeMillis() < end) e2eLock.wait(500); }
        if (!e2eChecked && "direct".equals(kind)) throw new InterruptedException("Шифрование ещё проверяется, попробуйте снова");
    }

    /** E2E включается, когда у нас и у собеседника опубликованы ключи. Работает в отдельном потоке, чтобы не блокировать очередь io. */
    private void checkE2e() {
        new Thread(() -> {
            boolean ok = false;
            if ("direct".equals(kind) && peerId != null) {
                boolean self = E2e.tryReady(this);
                selfFail = !self;
                peerErr = null;
                try { if (self) ok = E2e.get(this).peerHasKeys(peerId); }
                catch (Exception ex) { peerErr = Ui.err(ex); } // сеть/сервер — это не «нет ключей»
            } else if (isGroup()) {
                ok = E2e.tryReady(this); // в группе нужны только свои ключи; участникам без ключей SKDM не уйдёт
                selfFail = !ok;
            }
            e2e = ok;
            synchronized (e2eLock) { e2eChecked = true; e2eLock.notifyAll(); }
            runOnUiThread(() -> { updateBanner(); invalidateOptionsMenu(); });
        }, "e2e-check").start();
    }

    private void updateBanner() {
        if (banner == null) return;
        int bg, fg; String t;
        if (isGroup()) {
            java.util.List<String> w = e2eChecked && e2e ? E2e.group(this).warnings(chatId) : new java.util.ArrayList<>();
            if (!e2eChecked) { t = "Проверяю сквозное шифрование…"; bg = 0xFFECEFF1; fg = 0xFF37474F; }
            else if (!e2e) { t = "⚠ " + selfErr() + " Отправка в группу заблокирована."; bg = 0xFFFFE0B2; fg = 0xFFE65100; }
            else if (!w.isEmpty()) { t = "⚠ " + w.get(w.size() - 1) + " (нажмите, чтобы скрыть)"; bg = 0xFFFFCDD2; fg = 0xFFB71C1C; }
            else { t = "🔒 Групповое сквозное шифрование (Sender Keys)"; bg = 0xFFC8E6C9; fg = 0xFF1B5E20; }
        } else if (!"direct".equals(kind)) { t = "Чат без сквозного шифрования"; bg = 0xFFECEFF1; fg = 0xFF37474F; }
        else if (!e2eChecked) { t = "Проверяю сквозное шифрование…"; bg = 0xFFECEFF1; fg = 0xFF37474F; }
        else if (e2e && E2e.get(this).hasPendingChange(peerId)) {
            t = "⚠ Ключ безопасности собеседника изменился (новое устройство или переустановка). Отправка заблокирована. Нажмите, чтобы сверить код и принять.";
            bg = 0xFFFFCDD2; fg = 0xFFB71C1C;
        } else if (e2e) {
            boolean v = E2e.get(this).isVerified(peerId);
            t = v ? "🔒 Сквозное шифрование · код безопасности проверен ✅" : "🔒 Сквозное шифрование · нажмите, чтобы сверить код безопасности по QR";
            bg = 0xFFC8E6C9; fg = 0xFF1B5E20;
        } else if (peerErr != null) { t = "⚠ Не удалось проверить ключи собеседника: " + peerErr + ". Повторяю…"; bg = 0xFFFFE0B2; fg = 0xFFE65100;
        } else if (selfFail) { t = "⚠ " + selfErr() + " Отправка заблокирована."; bg = 0xFFFFE0B2; fg = 0xFFE65100;
        } else { t = "⚠ У собеседника сейчас нет устройства с ключами шифрования (заходил только с сайта/старой версии или вышел). Отправка заблокирована до его входа в приложение или обновлённый сайт — проверяю каждые 20 с: без шифрования личные сообщения не уходят. Входящие без шифрования помечены «⚠ не зашифровано»."; bg = 0xFFFFE0B2; fg = 0xFFE65100; }
        banner.setText(t); banner.setBackgroundColor(bg); banner.setTextColor(fg);
    }

    @Override public boolean onCreateOptionsMenu(android.view.Menu menu) {
        if ("direct".equals(kind) && e2e) menu.add(0, 1, 0, "🔒 Код безопасности").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS);
        if (isGroup()) menu.add(0, 2, 1, "Покинуть группу").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER);
        return true;
    }
    @Override public boolean onOptionsItemSelected(android.view.MenuItem it) {
        if (it.getItemId() == 1) { showSafety(); return true; }
        if (it.getItemId() == 2) {
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("Покинуть группу?")
                    .setPositiveButton("Покинуть", (d, w) -> io.execute(() -> {
                        try { Api.get(this).rpc("leave_chat", new JSONObject().put("p_chat", chatId)); runOnUiThread(this::finish); }
                        catch (Exception e) { Ui.toast(this, Ui.err(e)); }
                    })).setNegativeButton("Отмена", null).show();
            return true;
        }
        return super.onOptionsItemSelected(it);
    }

    private void showSafety() {
        new Thread(() -> {
            try {
                final java.util.List<String[]> nums = E2e.get(this).safetyNumbers(peerId);
                runOnUiThread(() -> {
                    LinearLayout col = Ui.column(this);
                    col.setPadding(Ui.dp(this, 24), Ui.dp(this, 8), Ui.dp(this, 24), 0);
                    col.addView(Ui.text(this, "Сверьте код с собеседником при встрече: отсканируйте его QR или сравните цифры. Если совпало — переписку никто не подменяет.", 14));
                    int n = 0;
                    for (String[] d : nums) {
                        n++;
                        if (nums.size() > 1) col.addView(Ui.text(this, "Устройство " + n, 13));
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < d[1].length(); i += 5) { sb.append(d[1], i, Math.min(i + 5, d[1].length())); sb.append((i / 5) % 4 == 3 ? "\n" : "  "); }
                        TextView num = Ui.text(this, sb.toString().trim(), 18);
                        num.setTypeface(android.graphics.Typeface.MONOSPACE);
                        num.setGravity(Gravity.CENTER);
                        col.addView(num);
                        try {
                            android.graphics.Bitmap bmp = new com.journeyapps.barcodescanner.BarcodeEncoder().encodeBitmap(d[2], com.google.zxing.BarcodeFormat.QR_CODE, 600, 600);
                            android.widget.ImageView iv = new android.widget.ImageView(this);
                            iv.setImageBitmap(bmp);
                            iv.setContentDescription("QR-код безопасности");
                            col.addView(iv, new LinearLayout.LayoutParams(-1, Ui.dp(this, 240)));
                        } catch (Exception ignored) { }
                    }
                    if (nums.isEmpty()) col.addView(Ui.text(this, "Нет ключей собеседника.", 14));
                    ScrollView sv = new ScrollView(this); sv.addView(col);
                    com.google.android.material.dialog.MaterialAlertDialogBuilder bd = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                            .setTitle("Код безопасности").setView(sv)
                            .setPositiveButton("Сканировать QR собеседника", (dd, w) -> {
                                com.journeyapps.barcodescanner.ScanOptions o = new com.journeyapps.barcodescanner.ScanOptions();
                                o.setDesiredBarcodeFormats(com.journeyapps.barcodescanner.ScanOptions.QR_CODE);
                                o.setPrompt("Наведите на код безопасности собеседника");
                                o.setBeepEnabled(false); o.setOrientationLocked(false);
                                safetyScan.launch(o);
                            })
                            .setNegativeButton("Закрыть", null);
                    if (E2e.get(this).hasPendingChange(peerId)) bd.setNeutralButton("Принять новый ключ", (dd, w) -> confirmAccept());
                    bd.show();
                });
            } catch (Exception e) { Ui.toast(this, "Код безопасности недоступен: " + Ui.err(e)); }
        }, "safety").start();
    }

    private void confirmAccept() {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle("Принять новый ключ?")
                .setMessage("Делайте это, только если собеседник подтвердил, что сменил телефон или переустановил приложение. Надёжнее — сначала сверить QR.")
                .setPositiveButton("Принять", (d, w) -> { E2e.get(this).acceptKeys(peerId); updateBanner(); })
                .setNegativeButton("Отмена", null).show();
    }

    private void onSafetyScanned(String content) {
        new Thread(() -> {
            boolean ok;
            try { ok = E2e.get(this).verifyScanned(peerId, java.util.Base64.getDecoder().decode(content.trim())); }
            catch (Exception e) { ok = false; }
            final boolean fin = ok;
            runOnUiThread(() -> {
                updateBanner();
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle(fin ? "✅ Коды совпали" : "❌ Коды не совпадают")
                        .setMessage(fin ? "Ключ собеседника проверен. Переписка защищена." : "Возможна подмена ключа. Не отправляйте ничего важного и сверьте код ещё раз при встрече.")
                        .setPositiveButton("OK", null).show();
            });
        }, "safety-scan").start();
    }

    private void openE2eFile(JSONObject f, boolean fromOther) {
        final String name = ChatFiles.safeName(f.optString("n", "file"));
        Runnable go = () -> {
            Ui.toast(this, "Загружаю и расшифровываю файл…");
            io.execute(() -> {
                java.io.File enc = null;
                try {
                    enc = ChatFiles.download(this, f.getString("p"), Api.get(this).freshToken());
                    byte[] ct;
                    try (java.io.FileInputStream fi = new java.io.FileInputStream(enc)) { ct = readAll(fi); }
                    byte[] plain = E2eCore.decryptFile(ct, E2eCore.unb64(f.getString("k")), E2eCore.unb64(f.getString("iv")), f.optString("h", null));
                    java.io.File out = new java.io.File(ChatFiles.cacheDir(this), java.util.UUID.randomUUID() + "_" + name);
                    try (java.io.FileOutputStream fo = new java.io.FileOutputStream(out)) { fo.write(plain); }
                    runOnUiThread(() -> {
                        try { ChatFiles.open(this, out, name); }
                        catch (Exception e) { Ui.toast(this, "Нет приложения для открытия файла"); }
                    });
                } catch (Exception e) {
                    Ui.toast(this, Ui.err(e));
                    if (e instanceof Api.AuthException) runOnUiThread(this::finish);
                } finally { if (enc != null) enc.delete(); }
            });
        };
        if (fromOther && ChatFiles.risky(name)) {
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle("Открыть исполняемый файл?")
                    .setMessage("«" + name + "» может быть программой или установщиком. Открывайте, только если доверяете отправителю.")
                    .setPositiveButton("Открыть", (d, w) -> go.run())
                    .setNegativeButton("Отмена", null).show();
        } else go.run();
    }

    /** 1 — на сервере (одна серая), 2 — доставлено (две серые), 3 — прочитано (две синие). */
    static int tickState(long created, long othersDelivered, long othersRead) {
        if (othersRead > 0 && created <= othersRead) return 3;
        if (othersDelivered > 0 && created <= othersDelivered) return 2;
        return 1;
    }
    private static void colorTicks(LinearLayout bubble) {
        android.view.View v = bubble.getChildAt(bubble.getChildCount() - 1);
        if (!(v instanceof TextView)) return;
        TextView tv = (TextView) v; String t = tv.getText().toString(); int i = t.lastIndexOf("✓✓");
        if (i < 0) return;
        android.text.SpannableString sp = new android.text.SpannableString(t);
        sp.setSpan(new android.text.style.ForegroundColorSpan(0xFF4FC3F7), i, i + 2, 0);
        sp.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), i, i + 2, 0);
        tv.setText(sp); tv.setAlpha(1f);
    }
    private volatile String deliveredUpto = null;
    /** «Получил»: rpc mark_delivered до последнего входящего, время только растёт. Фоновый поток. */
    private void markDelivered(JSONArray arr, String me) {
        String upto = null;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject m = arr.optJSONObject(i);
            if (m != null && !me.equals(m.optString("sender_id"))) upto = m.optString("created_at");
        }
        if (upto == null || (deliveredUpto != null && Ui.instant(deliveredUpto) >= Ui.instant(upto))) return;
        try { Api.get(this).rpc("mark_delivered", new JSONObject().put("p_chat", chatId).put("p_upto", upto)); deliveredUpto = upto; }
        catch (Exception e) { android.util.Log.w("Dlv", "mark_delivered: " + e); }
    }

    /** Отправка записанного голосового/кружка: AES-256-GCM, chat/<chat>/<uuid>_file.bin, octet-stream; vk/d/w — внутри шифротекста. */
    private void sendMedia(byte[] plain, String name, String mime, JSONObject media) {
        Ui.toast(this, "voice".equals(media.optString("vk")) ? "Отправляю голосовое…" : "Отправляю видеосообщение…");
        io.execute(() -> {
            try { encryptAndSend(plain, name, mime, media); fetch(); }
            catch (Exception e) { Ui.toast(this, Ui.err(e)); if (e instanceof Api.AuthException) finish(); }
        });
    }
    /** Общая отправка файла (как sendFile на сайте). При ошибке вставки сообщения загруженный объект удаляется. */
    private void encryptAndSend(byte[] plain, String fname, String mime, JSONObject media) throws Exception {
        Api api = Api.get(this);
        if (plain.length <= 0) throw new java.io.IOException("Пустой файл");
        if (plain.length > ChatFiles.MAX) throw new java.io.IOException("Файл больше 25 МБ");
        if (!e2eChecked) waitE2e();
        if (!e2e) { recheckE2e(); waitE2e(); }
        if (!e2e) throw new Exception(isGroup() || selfFail ? "Файл не отправлен. " + selfErr() : NO_KEYS_MSG);
        byte[][] enc = E2eCore.encryptFile(plain);
        java.io.File et = java.io.File.createTempFile("enc", ".bin", getCacheDir());
        String path = null;
        try {
            try (java.io.FileOutputStream fo = new java.io.FileOutputStream(et)) { fo.write(enc[0]); }
            path = ChatFiles.upload(this, android.net.Uri.fromFile(et), "file.bin", et.length(), "application/octet-stream", chatId, api.freshToken());
            JSONObject f = new JSONObject().put("p", path).put("n", fname).put("s", plain.length).put("m", mime == null ? "" : mime)
                    .put("k", E2eCore.b64(enc[1])).put("iv", E2eCore.b64(enc[2])).put("h", E2eCore.sha256hex(plain));
            if (media != null) for (java.util.Iterator<String> it = media.keys(); it.hasNext(); ) { String k = it.next(); f.put(k, media.get(k)); }
            pendingAttachment = path;
            post(new JSONObject().put("v", 1).put("t", "").put("f", f), null, null);
        } catch (Exception e) {
            if (path != null) ChatFiles.remove(path, api.freshToken());
            throw e;
        } finally { et.delete(); pendingAttachment = null; }
    }
    /** attachment_path для строки messages (только путь; имя, размер и ключ — в шифротексте). */
    private volatile String pendingAttachment = null;

    /** Прочитано — только если чат реально на экране (в фокусе) и только до последнего показанного сообщения собеседника. */
    private void markRead() {
        final String upto = seenUpto;
        if (upto == null || !running || !hasWindowFocus()) return;
        if (markedUpto != null && Ui.instant(markedUpto) >= Ui.instant(upto)) return; // без повторов (иначе цикл через Realtime)
        markedUpto = upto;
        io.execute(() -> {
            try { Api.get(this).rpc("mark_read", new JSONObject().put("p_chat", chatId).put("p_upto", upto)); }
            catch (Exception e) { markedUpto = null; android.util.Log.w("Read", "mark_read: " + e); }
        });
    }

    private void sendFile(android.net.Uri uri) {
        String name = "file"; long size = -1;
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                int si = c.getColumnIndex(android.provider.OpenableColumns.SIZE);
                if (ni >= 0 && !c.isNull(ni)) name = c.getString(ni);
                if (si >= 0 && !c.isNull(si)) size = c.getLong(si);
            }
        } catch (Exception ignored) { }
        if (size > ChatFiles.MAX) { Ui.toast(this, "Файл больше 25 МБ"); return; }
        final String fname = ChatFiles.safeName(name);
        final long fsize = size;
        final String mime = getContentResolver().getType(uri);
        Ui.toast(this, "Отправляю «" + fname + "»…");
        io.execute(() -> {
            java.io.File tmp = null;
            try {
                android.net.Uri src = uri; long sz = fsize;
                if (sz <= 0) {
                    tmp = ChatFiles.copyToTemp(this, uri);
                    sz = tmp.length();
                    src = android.net.Uri.fromFile(tmp);
                }
                if (sz <= 0) throw new java.io.IOException("Пустой файл");
                Api api = Api.get(this);
                if (!e2eChecked) waitE2e();
                if (!e2e) { recheckE2e(); waitE2e(); }
                byte[] plain;
                try (java.io.InputStream is = getContentResolver().openInputStream(src)) { plain = readAll(is); }
                encryptAndSend(plain, fname, mime, null);
                fetch();
            } catch (Exception e) {
                Ui.toast(this, Ui.err(e));
                if (e instanceof Api.AuthException) finish();
            } finally {
                if (tmp != null) tmp.delete();
            }
        });
    }

    private void openFile(String path, String name, boolean fromOther) {
        if (fromOther && ChatFiles.risky(name)) {
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                    .setTitle("Открыть исполняемый файл?")
                    .setMessage("«" + name + "» может быть программой или установщиком. Открывайте, только если доверяете отправителю.")
                    .setPositiveButton("Открыть", (d, w) -> doOpen(path, name))
                    .setNegativeButton("Отмена", null).show();
        } else doOpen(path, name);
    }

    private void doOpen(String path, String name) {
        Ui.toast(this, "Загружаю файл…");
        io.execute(() -> {
            try {
                java.io.File f = ChatFiles.download(this, path, Api.get(this).freshToken());
                runOnUiThread(() -> {
                    try { ChatFiles.open(this, f, name); }
                    catch (Exception e) { Ui.toast(this, "Нет приложения для открытия файла"); }
                });
            } catch (Exception e) {
                Ui.toast(this, Ui.err(e));
                if (e instanceof Api.AuthException) runOnUiThread(this::finish);
            }
        });
    }
}
