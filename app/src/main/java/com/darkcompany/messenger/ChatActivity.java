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
            paintSub();
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
        android.widget.FrameLayout outer = new android.widget.FrameLayout(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Site.surface(this));
        // #chatHead: ← , аватар 40, имя + подзаголовок, ⋮
        LinearLayout head = Site.bar(this);
        head.addView(Site.iconButton(this, "arrow_back", "Назад к чатам", v -> finish()));
        // button.peer: padding 4 6, аватар 40, .titles (gap 10): имя 17px/700 (lh 20.4), #chatSub 14px (lh 16.8)
        LinearLayout peer = new LinearLayout(this); peer.setGravity(Gravity.CENTER_VERTICAL);
        peer.setPadding(Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4));
        avaBox = new android.widget.FrameLayout(this);
        peer.addView(avaBox, new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40)));
        LinearLayout titles = new LinearLayout(this); titles.setOrientation(LinearLayout.VERTICAL); titles.setPadding(Ui.dp(this, 10), 0, 0, 0);
        titleV = Site.lh(Site.label(this, "", 17f, Site.text(this), 700), 20.4f); titleV.setSingleLine(true); titleV.setEllipsize(android.text.TextUtils.TruncateAt.END);
        subV = Site.lh(Site.label(this, "", 14f, Site.text2(this), 400), 16.8f); subV.setSingleLine(true);
        titles.addView(titleV); titles.addView(subV);
        peer.addView(titles, new LinearLayout.LayoutParams(0, -2, 1f));
        peer.setOnClickListener(v -> showPeer());
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(0, Ui.dp(this, 48), 1f); pl.leftMargin = Ui.dp(this, 8); pl.rightMargin = Ui.dp(this, 8);
        head.addView(peer, pl);
        head.addView(Site.iconButton(this, "more_vert", "Меню чата", this::chatMenu));
        root.addView(head);
        // #pinBar: полоска 2px primary, «Закреплённое сообщение» 13px/700 primary, превью 14px, ✕
        pinBar = new LinearLayout(this); pinBar.setGravity(Gravity.CENTER_VERTICAL);
        pinBar.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
        android.graphics.drawable.LayerDrawable pbg = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
                new android.graphics.drawable.ColorDrawable(Site.divider(this)), new android.graphics.drawable.ColorDrawable(getColor(R.color.web_color_pinned_bg))});
        pbg.setLayerInset(1, 0, 0, 0, Math.max(1, Ui.dp(this, 1)));
        pinBar.setBackground(pbg);
        android.view.View pline = new android.view.View(this); pline.setBackground(Site.round(Site.primary(this), Ui.dp(this, 1)));
        pinBar.addView(pline, new LinearLayout.LayoutParams(Ui.dp(this, 2), Ui.dp(this, 36)));
        LinearLayout ptx = new LinearLayout(this); ptx.setOrientation(LinearLayout.VERTICAL); ptx.setPadding(Ui.dp(this, 4), Ui.dp(this, 2), Ui.dp(this, 4), Ui.dp(this, 2));
        pinLabel = Site.label(this, "Закреплённое сообщение", 13f, Site.primary(this), 700);
        pinPreview = Site.label(this, "", 14f, Site.text(this), 400); pinPreview.setSingleLine(true); pinPreview.setEllipsize(android.text.TextUtils.TruncateAt.END);
        ptx.addView(pinLabel); ptx.addView(pinPreview);
        ptx.setOnClickListener(v -> { if (pins.isEmpty()) return; jumpTo(pins.get(pinIdx)); pinIdx = (pinIdx + 1) % pins.size(); paintPinBar(); });
        LinearLayout.LayoutParams ptl = new LinearLayout.LayoutParams(0, -2, 1f); ptl.leftMargin = Ui.dp(this, 10);
        pinBar.addView(ptx, ptl);
        unpinBtn = Site.iconButton(this, "close", "Открепить", v -> { if (pins.isEmpty()) return; final String pid = pins.get(pinIdx);
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setMessage("Открепить это сообщение?").setNegativeButton("Отмена", null)
                    .setPositiveButton("Открепить", (d, w) -> pinMsg(pid, false)).show(); });
        pinBar.addView(unpinBtn);
        pinBar.setVisibility(android.view.View.GONE);
        root.addView(pinBar, new LinearLayout.LayoutParams(-1, -2));
        header();
        // имя и аватар собеседника: если peer_id не пришёл (уведомление, старый кэш) — берём из chat_members; профиль грузим всегда и перерисовываем шапку
        if ("direct".equals(kind)) io.execute(() -> {
            try {
                if (peerId == null) {
                    String me = Api.get(this).uid();
                    JSONArray mm = Api.get(this).select("chat_members?select=user_id&chat_id=eq." + chatId);
                    for (int i = 0; i < mm.length(); i++) { String u = mm.getJSONObject(i).optString("user_id"); if (!u.equals(me)) { peerId = u; break; } }
                }
                if (peerId != null) { Avatars.refresh(this, java.util.Collections.singleton(peerId), 0); runOnUiThread(this::header); }
            } catch (Exception ignored) { }
        });
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
        scroll.setFillViewport(true);
        box = Ui.column(this); box.setClipChildren(false); box.setClipToPadding(false);
        scroll.addView(box);
        scroll.setBackgroundColor(getColor(R.color.site_chatbg));
        android.widget.FrameLayout msgFrame = new android.widget.FrameLayout(this);
        msgFrame.addView(scroll, new android.widget.FrameLayout.LayoutParams(-1, -1));
        // .jump: 46×46 круг, фон surface, тень, keyboard_arrow_down; счётчик #jumpCount
        jumpBtn = new android.widget.FrameLayout(this);
        android.widget.TextView ja = Site.icon(this, "keyboard_arrow_down", 24, Site.text2(this));
        ja.setBackground(Site.round(Site.surface(this), Ui.dp(this, 23))); ja.setElevation(Ui.dp(this, 6));
        jumpBtn.addView(ja, new android.widget.FrameLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46), Gravity.BOTTOM | Gravity.START));
        jumpCount = Site.label(this, "", 12f, getColor(R.color.web_color_on_primary), 600);
        jumpCount.setGravity(Gravity.CENTER); jumpCount.setMinWidth(Ui.dp(this, 22)); jumpCount.setPadding(Ui.dp(this, 6), 0, Ui.dp(this, 6), 0);
        jumpCount.setBackground(Site.round(Site.primary(this), Ui.dp(this, 11))); jumpCount.setElevation(Ui.dp(this, 7));
        jumpBtn.addView(jumpCount, new android.widget.FrameLayout.LayoutParams(-2, Ui.dp(this, 22), Gravity.TOP | Gravity.END));
        jumpCount.setVisibility(android.view.View.GONE);
        jumpBtn.setPadding(0, Ui.dp(this, 6), Ui.dp(this, 4), 0);
        jumpBtn.setContentDescription("Вниз к последним сообщениям");
        jumpBtn.setOnClickListener(v -> { scroll.smoothScrollTo(0, box.getHeight()); jumpUnread = 0; paintJump(); });
        android.widget.FrameLayout.LayoutParams jl = new android.widget.FrameLayout.LayoutParams(Ui.dp(this, 50), Ui.dp(this, 52), Gravity.BOTTOM | Gravity.END);
        jl.rightMargin = Ui.dp(this, 16); jl.bottomMargin = Ui.dp(this, 16);
        msgFrame.addView(jumpBtn, jl);
        jumpBtn.setVisibility(android.view.View.GONE);
        scroll.setOnScrollChangeListener((v, x, y, ox, oy) -> { if (!farFromBottom()) jumpUnread = 0; paintJump(); });
        root.addView(msgFrame, new LinearLayout.LayoutParams(-1, 0, 1f));
        box.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
        // #composeBar: ответ
        replyBar = Ui.text(this, "", 14);
        replyBar.setMaxLines(2); replyBar.setEllipsize(android.text.TextUtils.TruncateAt.END);
        replyBar.setPadding(Ui.dp(this, 16), Ui.dp(this, 6), Ui.dp(this, 16), Ui.dp(this, 6));
        replyBar.setBackgroundColor(Site.surface(this)); replyBar.setTextColor(Site.text(this));
        replyBar.setVisibility(android.view.View.GONE);
        replyBar.setContentDescription("Отменить ответ");
        replyBar.setOnClickListener(v -> { replyTo = null; replyBar.setVisibility(android.view.View.GONE); });
        root.addView(replyBar, new LinearLayout.LayoutParams(-1, -2));
        // .net-line
        netLine = Site.label(this, "Отправится, когда появится сеть", 12, Site.text2(this), false);
        netLine.setGravity(Gravity.CENTER); netLine.setBackgroundColor(Site.surface(this));
        netLine.setPadding(Ui.dp(this, 14), Ui.dp(this, 3), Ui.dp(this, 14), Ui.dp(this, 3));
        netLine.setVisibility(android.view.View.GONE);
        root.addView(netLine, new LinearLayout.LayoutParams(-1, -2));
        // #sendForm: 📎, поле-«пилюля», ➤ / 🎤
        android.widget.FrameLayout barWrap = new android.widget.FrameLayout(this);
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        // #sendForm: padding 8 10, линия сверху; 📎 40, поле 46 (r24, padding 11 12), 🎤 44; промежутки 6
        bar.setPadding(Ui.dp(this, 10), Ui.dp(this, 8) + Math.max(1, Ui.dp(this, 1)), Ui.dp(this, 10), Ui.dp(this, 8));
        android.graphics.drawable.LayerDrawable sbg = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
                new android.graphics.drawable.ColorDrawable(Site.divider(this)), new android.graphics.drawable.ColorDrawable(Site.surface(this))});
        sbg.setLayerInset(1, 0, Math.max(1, Ui.dp(this, 1)), 0, 0);
        barWrap.setBackground(sbg);
        final EditText in = Site.input(this, "Сообщение"); composer = in;
        in.setBackground(((android.graphics.drawable.GradientDrawable) in.getBackground()));
        ((android.graphics.drawable.GradientDrawable) in.getBackground()).setCornerRadius(Ui.dp(this, 24));
        in.setSingleLine(false); in.setMaxLines(5);
        msgIn = in;
        in.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(4000)});
        bar.addView(Site.iconButton(this, "attach_file", "Прикрепить файл", v -> picker.launch("*/*")));
        LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(0, -2, 1f); il.leftMargin = Ui.dp(this, 6); il.rightMargin = Ui.dp(this, 6);
        bar.addView(in, il);
        final android.widget.TextView send = Site.icon(this, "send", 20, 0xFFFFFFFF);
        send.setBackground(Site.round(Site.primary(this), Ui.dp(this, 22)));
        send.setContentDescription("Отправить"); send.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        final android.widget.TextView mic = Site.iconButton(this, "mic", "Голосовое сообщение", null);
        mic.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        bar.addView(send, new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44)));
        bar.addView(mic);
        send.setVisibility(android.view.View.GONE);
        in.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence x, int a1, int b1, int c1) { }
            public void onTextChanged(CharSequence x, int a1, int b1, int c1) { }
            public void afterTextChanged(android.text.Editable e) { boolean t = e.toString().trim().length() > 0; if (t) sendTyping(); send.setVisibility(t ? android.view.View.VISIBLE : android.view.View.GONE); mic.setVisibility(t ? android.view.View.GONE : android.view.View.VISIBLE); }
        });
        barWrap.addView(bar, new android.widget.FrameLayout.LayoutParams(-1, -2));
        // .rec-bar: ● 0:07  ‹ Отмена   🔒
        recBar = new LinearLayout(this); recBar.setGravity(Gravity.CENTER_VERTICAL); recBar.setBackgroundColor(Site.surface(this));
        recBar.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 56), 0);
        android.view.View dot = new android.view.View(this); dot.setBackground(Site.round(getColor(R.color.site_danger), Ui.dp(this, 5)));
        recBar.addView(dot, new LinearLayout.LayoutParams(Ui.dp(this, 10), Ui.dp(this, 10)));
        android.animation.ObjectAnimator blink = android.animation.ObjectAnimator.ofFloat(dot, "alpha", 1f, 0.15f);
        blink.setDuration(600); blink.setRepeatMode(android.animation.ValueAnimator.REVERSE); blink.setRepeatCount(android.animation.ValueAnimator.INFINITE); recBlink = blink;
        recTime = Site.label(this, "0:00", 16, Site.text(this), false); recTime.setPadding(Ui.dp(this, 10), 0, Ui.dp(this, 10), 0);
        recBar.addView(recTime);
        recCancel = Site.label(this, "‹ Отмена", 16, Site.primary(this), false); recCancel.setGravity(Gravity.CENTER);
        recBar.addView(recCancel, new LinearLayout.LayoutParams(0, -1, 1f));
        recLock = Site.icon(this, "lock", 20, Site.text2(this));
        recBar.addView(recLock, new LinearLayout.LayoutParams(Ui.dp(this, 32), -1));
        recBar.setVisibility(android.view.View.GONE);
        recBar.setClickable(true);
        barWrap.addView(recBar, new android.widget.FrameLayout.LayoutParams(-1, -1));
        root.addView(barWrap, new LinearLayout.LayoutParams(-1, -2));
        outer.addView(root, new android.widget.FrameLayout.LayoutParams(-1, -1));
        // .round-box: круглый видоискатель 240dp над полем ввода
        roundBox = new RoundBox(this);
        android.widget.FrameLayout.LayoutParams rl = new android.widget.FrameLayout.LayoutParams(Ui.dp(this, 240), Ui.dp(this, 240), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        rl.bottomMargin = Ui.dp(this, 90);
        outer.addView(roundBox, rl);
        roundBox.setVisibility(android.view.View.GONE);
        setContentView(outer);
        Ui.fitSystemBars(this, outer); // edge-to-edge (Android 15+)
        send.setOnClickListener(v -> {
            final String t = in.getText().toString().trim();
            if (t.isEmpty()) return;
            in.setText("");
            final String rt = replyTo;
            replyTo = null; replyBar.setVisibility(android.view.View.GONE);
            stopTyping();
            outSend(t, rt);
        });
        // Голосовые и кружки: удерживать — запись, влево — отмена, вверх — замок, короткое нажатие — голос/кружок
        recorder = new Recorder(this, mic, new Recorder.Hooks() {
            @Override public void start(String mode) { recBar.setVisibility(android.view.View.VISIBLE); recTime.setText("0:00"); recCancel.setText("‹ Влево — отмена"); recLock.setText("lock"); recBlink.start(); if ("round".equals(mode)) roundBox.setVisibility(android.view.View.VISIBLE); }
            @Override public void tick(long sec, boolean locked) { recTime.setText(Cache.fmtDur(sec)); roundBox.setProgress(sec / (float) Recorder.MAX_REC); if (locked) { recCancel.setText("🔒 Отмена"); recLock.setText("send"); } }
            @Override public void end() { recBlink.cancel(); recBar.setVisibility(android.view.View.GONE); roundBox.setVisibility(android.view.View.GONE); roundBox.setProgress(0); }
            @Override public androidx.camera.view.PreviewView preview() { return roundBox.preview; }
        }, (data, mime, name, media) -> sendMedia(data, name, mime, media));
        recCancel.setOnClickListener(v -> recorder.cancel());
        recLock.setOnClickListener(v -> recorder.sendLocked());
        // Офлайн-кэш: последние 100 сообщений видны сразу, даже без интернета
        loadOutbox();
        JSONObject cached = Cache.chat(this, chatId);
        try {
            JSONArray cm = new JSONArray();
            if (cached != null) {
                JSONObject d = cached.optJSONObject("d");
                if (d != null) for (java.util.Iterator<String> it = d.keys(); it.hasNext(); ) { String k = it.next(); dec.put(k, d.getJSONObject(k)); }
                cm = cached.getJSONArray("m");
            }
            render(cm, new JSONArray(), new JSONArray());
        } catch (Exception ignored) { }
        watchNetwork();
    }

    private android.widget.FrameLayout avaBox, jumpBtn;
    private LinearLayout pinBar;
    private TextView pinLabel, pinPreview, unpinBtn, jumpCount;
    private EditText msgIn;
    private final java.util.List<String> pins = new java.util.ArrayList<>();
    private int pinIdx = 0, jumpUnread = 0;
    private long typingUntil = 0, lastTypingSent = 0;
    private String typingWho = null;
    private final Map<String, android.view.View> msgViews = new java.util.HashMap<>();
    private final Map<String, String> previews = new java.util.HashMap<>();

    // ---------- «вниз» ----------
    private boolean farFromBottom() { return box.getHeight() - (scroll.getScrollY() + scroll.getHeight()) > Ui.dp(this, 300); }
    private void paintJump() {
        jumpBtn.setVisibility(farFromBottom() ? android.view.View.VISIBLE : android.view.View.GONE);
        jumpCount.setVisibility(jumpUnread > 0 ? android.view.View.VISIBLE : android.view.View.GONE);
        jumpCount.setText(jumpUnread > 99 ? "99+" : String.valueOf(jumpUnread));
    }
    private void jumpTo(String id) {
        android.view.View v = msgViews.get(id);
        if (v == null) { toast("Сообщение не загружено"); return; }
        scroll.smoothScrollTo(0, Math.max(0, v.getTop() - scroll.getHeight() / 2 + v.getHeight() / 2));
        v.animate().alpha(0.4f).setDuration(200).withEndAction(() -> v.animate().alpha(1f).setDuration(600)).start();
    }

    // ---------- закреплённые (chat_pins + pin_message), как на сайте ----------
    private boolean canPin() { return "direct".equals(kind) || "owner".equals(role) || "admin".equals(role); }
    private String role = null;
    private void loadPins() {
        io.execute(() -> {
            try {
                Api api = Api.get(this);
                if (role == null && !"direct".equals(kind)) {
                    JSONArray r = api.select("chat_members?select=role&chat_id=eq." + chatId + "&user_id=eq." + api.uid());
                    role = r.length() > 0 ? r.getJSONObject(0).optString("role", "member") : "member";
                }
                JSONArray d = api.select("chat_pins?select=message_id,pinned_at&chat_id=eq." + chatId + "&order=pinned_at.desc");
                java.util.List<String> n = new java.util.ArrayList<>();
                for (int i = 0; i < d.length(); i++) n.add(d.getJSONObject(i).getString("message_id"));
                runOnUiThread(() -> { pins.clear(); pins.addAll(n); if (pinIdx >= pins.size()) pinIdx = 0; paintPinBar(); });
            } catch (Exception ignored) { }
        });
    }
    private void paintPinBar() {
        pinBar.setVisibility(pins.isEmpty() ? android.view.View.GONE : android.view.View.VISIBLE);
        if (pins.isEmpty()) return;
        String id = pins.get(pinIdx);
        pinLabel.setText("Закреплённое сообщение" + (pins.size() > 1 ? " #" + (pins.size() - pinIdx) : ""));
        pinPreview.setText(previews.containsKey(id) ? previews.get(id) : "сообщение не загружено");
        unpinBtn.setVisibility(canPin() ? android.view.View.VISIBLE : android.view.View.GONE);
    }
    private void pinMsg(String id, boolean on) {
        io.execute(() -> {
            try { Api.get(this).rpc("pin_message", new JSONObject().put("p_msg", id).put("p_pin", on)); runOnUiThread(() -> toast(on ? "Сообщение закреплено" : "Сообщение откреплено")); loadPins(); }
            catch (Exception e) { runOnUiThread(() -> toast(Ui.err(e))); }
        });
    }

    // ---------- «печатает…» (broadcast typing, как на сайте) ----------
    private void sendTyping() {
        if (rt == null || System.currentTimeMillis() - lastTypingSent < 3000) return;
        lastTypingSent = System.currentTimeMillis();
        try { rt.broadcast("typing", new JSONObject().put("u", Api.get(this).uid())); } catch (Exception ignored) { }
    }
    private void stopTyping() {
        if (rt == null || lastTypingSent == 0) return;
        lastTypingSent = 0;
        try { rt.broadcast("typing", new JSONObject().put("u", Api.get(this).uid()).put("stop", true)); } catch (Exception ignored) { }
    }
    private void onTyping(JSONObject p) {
        if (p == null) return;
        String u = p.optString("u");
        if (u.isEmpty() || u.equals(Api.get(this).uid())) return;
        if (p.optBoolean("stop")) { typingUntil = 0; paintSub(); return; }
        typingUntil = System.currentTimeMillis() + 5000;
        JSONObject pr = Avatars.profiles.get(u);
        typingWho = pr == null ? null : (pr.isNull("display_name") || pr.optString("display_name").isEmpty() ? "@" + pr.optString("username") : pr.optString("display_name"));
        paintSub();
        h.removeCallbacks(typingEnd); h.postDelayed(typingEnd, 5100);
    }
    private final Runnable typingEnd = () -> { if (System.currentTimeMillis() >= typingUntil) { typingUntil = 0; paintSub(); } };
    private TextView titleV, subV, netLine, recTime, recCancel, recLock;
    private EditText composer;
    private LinearLayout recBar; private android.animation.ObjectAnimator recBlink;
    private RoundBox roundBox;

    @Override protected void onStart() {
        super.onStart(); if (chatId == null) return; running = true; openChat = chatId; Notify.cancel(this, chatId);
        rt = new Realtime(Api.get(this), chatId, isGroup(), () -> h.post(() -> { if (running) onRealtimeChange(); }),
                () -> { lastDistrib = 0; }); // новая эпоха/новый SKDM: при следующем fetch — раздача ключа и приём SKDM
        if (isGroup()) io.execute(() -> { try { if (rt != null) rt.myDevice = E2e.get(this).deviceId(); } catch (Exception ignored) { } });
        rt.onBroadcast = (ev, pl) -> { if ("typing".equals(ev)) h.post(() -> onTyping(pl)); };
        rt.start();
        loadPins();
        h.post(poll);
        h.post(keyWatch);
        h.post(presence);
        flushOutbox();
    }
    private final Runnable presence = new Runnable() { @Override public void run() {
        if (!running) return;
        io.execute(() -> { try { Api.get(ChatActivity.this).rpc("touch_presence", new JSONObject()); } catch (Exception ignored) { } loadLastSeen(); });
        h.postDelayed(this, 60000);
    } };
    @Override protected void onStop() { super.onStop(); if (chatId != null && chatId.equals(openChat)) openChat = null; if (recorder != null) recorder.cancel(); running = false; h.removeCallbacks(keyWatch); h.removeCallbacks(presence); h.removeCallbacks(poll); h.removeCallbacks(rtFetch); if (rt != null) { rt.stop(); rt = null; } }

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
                    try { // быстрый путь без блокировки (как peek() сайта): свой сохранённый текст / «Отправлено до входа…»
                        String own = E2e.get(this).peekOwn(mid);
                        if (own != null) { dec.put(mid, new JSONObject(own)); decSig.put(mid, sigCt); failCount.remove(mid); continue; }
                        if (E2e.get(this).notForMe(m.getJSONObject("ciphertext")) && !"other".equals(dec.containsKey(mid) ? dec.get(mid).optString("err") : "")) {
                            dec.put(mid, new JSONObject().put("err", "other")); decSig.put(mid, sigCt); failCount.remove(mid); continue; }
                        if (E2e.get(this).notForMe(m.getJSONObject("ciphertext"))) continue;
                    } catch (Exception ignored) { }
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
                    else if (lp != null && "other".equals(lp.optString("err"))) Cache.putPreview(this, chatId, last.optString("created_at"), OTHER_DEV);
                }
                markDelivered(arr, api.uid());
                final String sig = arr.toString() + reacts + reads + dec.size();
                if (sig.equals(lastSig)) return;
                lastSig = sig;
                final JSONArray fin = arr, fr = reacts, frd = reads;
                runOnUiThread(() -> render(fin, fr, frd));
            } catch (Exception e) {
                if (e instanceof Api.AuthException) runOnUiThread(this::finish);
                else if (System.currentTimeMillis() - lastFetchErr > 30000) { lastFetchErr = System.currentTimeMillis(); toast("Не удалось загрузить сообщения: " + Ui.err(e)); }
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
        if (BuildConfig.DEBUG) android.util.Log.w("E2E", "decrypt " + mid + " failed: " + ex);
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
        box.removeAllViews(); msgViews.clear();
        if (arr.length() >= limit) { // старые сообщения не теряются — подгрузка порциями по 200
            TextView more = Site.label(this, "Загрузить более ранние сообщения", 14, Site.primary(this), true);
            more.setGravity(Gravity.CENTER); more.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
            more.setOnClickListener(v -> { limit += 200; keepScroll = true; lastSig = null; fetch(); });
            box.addView(more, new LinearLayout.LayoutParams(-1, -2));
        }
        // серверные сообщения + ещё не отправленные из очереди (как outBubble на сайте)
        java.util.List<JSONObject> all = new java.util.ArrayList<>();
        for (int i = 0; i < arr.length(); i++) { JSONObject m = arr.optJSONObject(i); if (m != null) all.add(m); }
        synchronized (outbox) {
            for (JSONObject j : outbox.values()) if (!byId.containsKey(j.optString("id"))) {
                try {
                    JSONObject m = new JSONObject().put("id", j.optString("id")).put("sender_id", me).put("created_at", j.optString("at"))
                            .put("ciphertext", new JSONObject()).put("_job", j);
                    if (j.has("rt")) m.put("reply_to", j.optString("rt"));
                    dec.put(j.optString("id"), new JSONObject().put("v", 1).put("t", j.optString("text")));
                    all.add(m);
                } catch (Exception ignored) { }
            }
        }
        long now = System.currentTimeMillis(), lastDay = -1;
        if (all.isEmpty()) {
            TextView es = Site.label(this, "Сообщений пока нет", 14, Site.text2(this), false);
            es.setGravity(Gravity.CENTER); es.setPadding(0, Ui.dp(this, 40), 0, 0);
            box.addView(es, new LinearLayout.LayoutParams(-1, -2));
        }
        for (int i = 0; i < all.size(); i++) {
            final JSONObject m = all.get(i);
            final String id = m.optString("id");
            final boolean mine = me.equals(m.optString("sender_id"));
            final JSONObject job = m.optJSONObject("_job");
            long at = Ui.instant(m.optString("created_at"));
                        if (Site.dayKey(at) != lastDay) { lastDay = Site.dayKey(at); box.addView(Site.dateSep(this, Site.dayLabel(at, now))); }
            JSONObject next = i + 1 < all.size() ? all.get(i + 1) : null;
            boolean tail = next == null || !m.optString("sender_id").equals(next.optString("sender_id")) || Site.dayKey(Ui.instant(next.optString("created_at"))) != lastDay;
            String who = mine || !isGroup() ? null : (m.isNull("sender_id") ? "удалённый аккаунт" : names.getOrDefault(m.optString("sender_id"), "участник"));
            // --- .msg: буквальный перенос renderMsg() сайта ---
            if (!m.isNull("ciphertext") && !dec.containsKey(id) && !isGroup() && job == null) { // сразу, без ожидания ключей
                try { if (E2e.get(this).notForMe(m.optJSONObject("ciphertext"))) dec.put(id, new JSONObject().put("err", "other")); } catch (Exception ignored) { }
            }
            final JSONObject pl = m.isNull("ciphertext") ? null : dec.get(id);
            final boolean ok = pl != null && !pl.has("err");
            final boolean otherDev = pl != null && "other".equals(pl.optString("err"));
            final JSONObject f = ok ? pl.optJSONObject("f") : null;
            MsgView.Bubble bubble = new MsgView.Bubble(this, mine, tail);
            if (who != null) bubble.addView(MsgView.sender(this, who));
            if (!m.isNull("reply_to") && m.has("reply_to")) {
                JSONObject q = byId.get(m.optString("reply_to"));
                String qn = q == null ? "Ответ" : me.equals(q.optString("sender_id")) ? "Вы" : q.isNull("sender_id") ? "…" : peerName(q.optString("sender_id"));
                android.view.View rq = MsgView.reply(this, mine, qn, q == null ? "сообщение" : quoteBody(q));
                final String rto = m.optString("reply_to"); rq.setOnClickListener(v -> jumpTo(rto));
                bubble.addView(rq);
            }
            boolean media = false, round = false, imgOnly = false;
            android.view.View mediaV = null;
            if (f != null && ("voice".equals(f.optString("vk")) || "round".equals(f.optString("vk")))) {
                mediaV = MediaPlay.view(this, mine, f, io, id); media = true; round = "round".equals(f.optString("vk"));
            } else if (f != null && avKind(f) == 1) {
                mediaV = MediaPlay.audioFile(this, f, io, v -> openE2eFile(f, !mine)); media = true;
                TextView an = mediaV.findViewWithTag("af-name"); if (an != null) an.setTextColor(getColor(mine ? R.color.web_bubble_out_text : R.color.web_bubble_in_text));
            } else if (f != null && avKind(f) == 2) {
                mediaV = MediaPlay.videoFile(this, f, io, v -> openE2eFile(f, !mine)); media = true;
            } else if (f != null && isImage(f)) {
                mediaV = imagePreview(f, !mine); imgOnly = captionOf(pl).isEmpty();
                ((LinearLayout.LayoutParams) mediaV.getLayoutParams()).topMargin = 0;
                ((LinearLayout.LayoutParams) mediaV.getLayoutParams()).bottomMargin = imgOnly ? 0 : MsgView.dp(this, 8);
            } else if (f != null) {
                mediaV = MsgView.fileRow(this, mine, f.optString("n", "file"), f.optLong("s", 0) > 0 ? MsgView.size(f.optLong("s", 0)) : "", false, v -> openE2eFile(f, !mine));
            } else if (!m.isNull("attachment_path") && m.has("attachment_path") && m.isNull("ciphertext")) {
                final String ap = m.optString("attachment_path");
                final String an = m.isNull("attachment_name") ? "file" : m.optString("attachment_name");
                mediaV = MsgView.fileRow(this, mine, an, m.optLong("attachment_size", 0) > 0 ? MsgView.size(m.optLong("attachment_size", 0)) : "", false, v -> openFile(ap, an, !mine));
            }
            String bd = textOf(m);
            int bodyKind = otherDev ? 2 : (!m.isNull("ciphertext") && !ok) ? 1 : 0;
            if (f != null && bd.equals("(пустое сообщение)")) bd = "";
            boolean edited = !m.isNull("edited_at") && m.has("edited_at") || (pl != null && pl.optInt("ed", 0) == 1);
            int tick = 0;
            if (job != null) tick = job.optBoolean("failed") ? 5 : 4;
            else if (mine && !otherDev) tick = tickState(at, othersDlv, othersRead);
            if (!mine && (lastIncoming == null || Ui.instantMicros(m.optString("created_at")) > Ui.instantMicros(lastIncoming))) lastIncoming = m.optString("created_at");
            String lock = job != null ? null : m.isNull("ciphertext") ? PLAIN_MARK : "🔒";
            LinearLayout foot = MsgView.foot(this, mine, imgOnly, lock, pins.contains(id), edited, Ui.time(m.optString("created_at", "")), tick);
            Map<String, int[]> em = rmap.get(id);
            if (em != null && !em.isEmpty()) MsgView.reacts(this, foot, mine, em, e -> toggleReact(id, e, em));
            if (round) { bubble.setPlain(); bubble.addView(mediaV); }
            else if (imgOnly) { bubble.setPadding(MsgView.dp(this, 4), MsgView.dp(this, 4), MsgView.dp(this, 4), MsgView.dp(this, 4)); bubble.addView(MsgView.overlayFoot(this, mediaV, foot)); }
            else if (mediaV != null && !media && f == null) { /* старый файл без шифрования: ниже текста */ }
            else if (mediaV != null) bubble.addView(mediaV);
            final boolean imgCap = f != null && isImage(f) && !imgOnly && !round; // v14: фото с подписью — фото на всю ширину (отступ 4dp), подпись 15sp, 6/12dp
            if (imgCap) bubble.setPadding(MsgView.dp(this, 4), MsgView.dp(this, 4), MsgView.dp(this, 4), MsgView.dp(this, 6));
            if (!imgOnly && !bd.isEmpty() && !(media && captionOf(pl).isEmpty())) {
                android.view.View bv = MsgView.body(this, mine, bd, bodyKind);
                if (imgCap) { bv.setPadding(MsgView.dp(this, 12), MsgView.dp(this, 6), MsgView.dp(this, 12), 0); if (bv instanceof TextView) ((TextView) bv).setTextSize(15f); }
                bubble.addView(bv, new LinearLayout.LayoutParams(-2, -2));
            }
            if (mediaV != null && f == null) bubble.addView(mediaV);
            if (!imgOnly) {
                LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(round ? MsgView.dp(this, 200) : -2, -2); fl.topMargin = MsgView.dp(this, 2); fl.gravity = Gravity.END; if (imgCap) fl.rightMargin = MsgView.dp(this, 12);
                bubble.addView(foot, fl);
            }
            if (job != null) {
                if (job.optBoolean("failed")) bubble.setOnClickListener(v -> failedMenu(id));
                bubble.setOnLongClickListener(v -> { failedMenu(id); return true; });
            } else { Site.trackTouch(bubble); bubble.setOnLongClickListener(v -> { actions(v, m, mine, em); return true; }); }
            box.addView(bubble);
            msgViews.put(id, bubble);
            previews.put(id, pl != null && pl.optJSONObject("f") != null && bd.isEmpty() ? notifText(pl) : bd);
        }
        int total = all.size();
        if (total != lastCount) {
            boolean far = lastCount > 0 && farFromBottom() && !keepScroll;
            int added = total - lastCount;
            lastCount = total;
            if (keepScroll) { keepScroll = false; scroll.post(() -> scroll.scrollTo(0, 0)); }
            else if (far && added > 0) { jumpUnread += added; paintJump(); } // как на сайте: не дёргаем ленту, счётчик на кнопке «вниз»
            else scroll.post(() -> scroll.fullScroll(android.view.View.FOCUS_DOWN));
        }
        paintPinBar();
        if (lastIncoming != null) { seenUpto = lastIncoming; markRead(); }
    }
    /** created_at последнего показанного входящего сообщения и что уже отправлено в mark_read. */
    private volatile String seenUpto = null, markedUpto = null;
    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) markRead(); }

    private void header() {
        String t = getIntent().getStringExtra("title");
        if (t != null && (t.trim().isEmpty() || "null".equals(t.trim()) || "@null".equals(t.trim()))) t = null;
        if (peerId != null) { JSONObject p = Avatars.merged(this, peerId); String dn = Avatars.displayName(p); if (!"?".equals(dn) || t == null) t = dn; }
        if (t == null) t = "Чат";
        titleV.setText(t);
        avaBox.removeAllViews();
        avaBox.addView("direct".equals(kind) && peerId != null ? Avatars.view(this, peerId, t, 40) : Avatars.render(this, null, t, chatId, 40, null),
                new android.widget.FrameLayout.LayoutParams(-1, -1));
        paintSub();
    }
    private volatile long lastSeenAt = -1; // -1: неизвестно
    private void paintSub() {
        if (subV == null) return;
        boolean conn = rt != null && rt.isJoined();
        if (!online) { subV.setText("ожидание сети…"); subV.setTextColor(Site.text2(this)); return; }
        if (!conn) { subV.setText("соединение…"); subV.setTextColor(Site.text2(this)); return; }
        if (System.currentTimeMillis() < typingUntil) {
            subV.setText(("direct".equals(kind) || typingWho == null ? "" : typingWho + " ") + "печатает…");
            subV.setTextColor(getColor(R.color.web_color_typing)); return;
        }
        if (!"direct".equals(kind)) { subV.setText("группа"); subV.setTextColor(Site.text2(this)); return; }
        String st = lastSeenAt < 0 ? "" : Site.seenText(lastSeenAt, System.currentTimeMillis());
        subV.setText(st); subV.setTextColor("в сети".equals(st) ? getColor(R.color.web_color_online) : Site.text2(this));
    }
    private void loadLastSeen() {
        if (!"direct".equals(kind) || peerId == null) return;
        try {
            String r = Api.get(this).rpc("get_last_seen", new JSONObject().put("p_user", peerId)).trim().replace("\"", "");
            lastSeenAt = r.isEmpty() || r.equals("null") ? 0 : Ui.instant(r);
        } catch (Exception ignored) { }
        runOnUiThread(this::paintSub);
    }
    private void chatMenu(android.view.View anchor) {
        android.widget.PopupMenu pm = new android.widget.PopupMenu(this, anchor);
        if ("direct".equals(kind)) pm.getMenu().add(0, 1, 0, "Код безопасности");
        if (isGroup()) pm.getMenu().add(0, 2, 1, "Покинуть группу");
        pm.setOnMenuItemClickListener(this::onOptionsItemSelected);
        pm.show();
    }

    // ---------- сеть и очередь отправки (как outbox на сайте) ----------
    private volatile boolean online = true;
    private android.net.ConnectivityManager.NetworkCallback netCb;
    private void watchNetwork() {
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return;
        online = cm.getActiveNetwork() != null;
        netCb = new android.net.ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(android.net.Network n) { online = true; h.post(() -> { paintSub(); paintNetLine(); flushOutbox(); }); }
            @Override public void onLost(android.net.Network n) { android.net.ConnectivityManager c2 = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE); online = c2 != null && c2.getActiveNetwork() != null; h.post(() -> { paintSub(); paintNetLine(); }); }
        };
        try { cm.registerDefaultNetworkCallback(netCb); } catch (Exception ignored) { }
    }
    @Override protected void onDestroy() {
        super.onDestroy();
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm != null && netCb != null) try { cm.unregisterNetworkCallback(netCb); } catch (Exception ignored) { }
    }
    /** id → {id, text, rt, at, failed}. Хранится на устройстве: переживает перезапуск приложения. */
    private final Map<String, JSONObject> outbox = new java.util.LinkedHashMap<>();
    private void loadOutbox() {
        try { String s0 = Cache.get(this, "outbox_" + chatId.replaceAll("[^0-9a-f-]", "") + ".json"); if (s0 == null) return;
            JSONArray a0 = new JSONArray(s0); synchronized (outbox) { for (int i = 0; i < a0.length(); i++) { JSONObject j = a0.getJSONObject(i); outbox.put(j.getString("id"), j); } }
        } catch (Exception ignored) { }
        h.post(this::paintNetLine);
    }
    private void saveOutbox() {
        JSONArray a0 = new JSONArray(); synchronized (outbox) { for (JSONObject j : outbox.values()) a0.put(j); }
        Cache.put(this, "outbox_" + chatId.replaceAll("[^0-9a-f-]", "") + ".json", a0.toString());
        h.post(this::paintNetLine);
    }
    private void paintNetLine() {
        if (netLine == null) return;
        boolean waiting = false;
        synchronized (outbox) { for (JSONObject j : outbox.values()) if (!j.optBoolean("failed")) waiting = true; }
        netLine.setVisibility(waiting && !online ? android.view.View.VISIBLE : android.view.View.GONE);
    }
    private void outSend(String text, String rt) {
        try {
            JSONObject j = new JSONObject().put("id", java.util.UUID.randomUUID().toString()).put("text", text)
                    .put("at", java.time.Instant.now().toString());
            if (rt != null) j.put("rt", rt);
            synchronized (outbox) { outbox.put(j.getString("id"), j); }
            saveOutbox();
            lastSig = null; fetchOrRender();
            outRun(j);
        } catch (Exception e) { toast(Ui.err(e)); }
    }
    /** Файл/голосовое/кружок через очередь (как outbox сайта): без сети ждёт, при сбое — «Повторить/Удалить». */
    private void outFile(byte[] plain, String name, String mime, JSONObject media) throws Exception { outFile(plain, name, mime, media, ""); }
    private void outFile(byte[] plain, String name, String mime, JSONObject media, String caption) throws Exception {
        if (plain.length <= 0) throw new java.io.IOException("Пустой файл");
        if (plain.length > ChatFiles.MAX) throw new java.io.IOException("Файл больше 25 МБ");
        String id = java.util.UUID.randomUUID().toString();
        java.io.File dir = new java.io.File(getFilesDir(), "outbox"); dir.mkdirs();
        java.io.File f = new java.io.File(dir, id + ".bin");
        try (java.io.FileOutputStream fo = new java.io.FileOutputStream(f)) { fo.write(plain); }
        String label = media != null ? ("voice".equals(media.optString("vk")) ? "🎤 Голосовое сообщение" : "🎥 Видеосообщение") : "📎 " + name;
        JSONObject j = new JSONObject().put("id", id).put("text", label).put("at", java.time.Instant.now().toString())
                .put("file", f.getAbsolutePath()).put("name", name);
        if (mime != null) j.put("mime", mime);
        if (media != null) j.put("media", media);
        if (caption != null && !caption.isEmpty()) j.put("caption", caption);
        synchronized (outbox) { outbox.put(id, j); }
        saveOutbox();
        runOnUiThread(() -> { lastSig = null; fetchOrRender(); outRun(j); });
    }
    private void fetchOrRender() { if (online) fetch(); else { JSONObject c0 = Cache.chat(this, chatId); try { render(c0 == null ? new JSONArray() : c0.getJSONArray("m"), new JSONArray(), new JSONArray()); } catch (Exception ignored) { } } }
    private void flushOutbox() {
        java.util.List<JSONObject> js = new java.util.ArrayList<>();
        synchronized (outbox) { for (JSONObject j : outbox.values()) if (!j.optBoolean("failed")) js.add(j); }
        for (JSONObject j : js) outRun(j);
    }
    private final Set<String> running0 = java.util.Collections.synchronizedSet(new HashSet<>());
    /** Отправка из очереди: тот же id строки (повтор не создаёт дубль), без сети — ждём, ошибка — красный значок с «Повторить/Удалить». */
    private void outRun(JSONObject j) {
        final String id = j.optString("id");
        if (!online || !running0.add(id)) { paintNetLine(); return; }
        io.execute(() -> {
            try {
                Api api = Api.get(this);
                boolean had = false;
                if (j.optInt("tries", 0) > 0) { try { had = api.select("messages?select=id&id=eq." + id).length() > 0; } catch (Exception ignored) { } }
                j.put("tries", j.optInt("tries", 0) + 1);
                if (!had) {
                    pendingId = id;
                    try {
                        if (j.has("file")) { // медиа из офлайн-очереди: открытые байты лежат в filesDir/outbox до отправки
                            byte[] plain; try (java.io.InputStream is = new java.io.FileInputStream(j.getString("file"))) { plain = readAll(is); }
                            encryptAndSend(plain, j.optString("name"), j.has("mime") ? j.optString("mime") : null, j.optJSONObject("media"), j.optString("caption", ""));
                        } else post(new JSONObject().put("v", 1).put("t", j.optString("text")), j.has("rt") ? j.optString("rt") : null, null);
                    }
                    finally { pendingId = null; }
                }
                if (j.has("file")) new java.io.File(j.getString("file")).delete();
                synchronized (outbox) { outbox.remove(id); }
                saveOutbox();
                lastSig = null; fetch();
            } catch (Exception e) {
                boolean net = !online || e instanceof java.io.IOException && !(e instanceof Api.HttpError);
                if (!net) { try { j.put("failed", true); } catch (Exception ignored) { } toast(Ui.err(e)); }
                saveOutbox();
                lastSig = null; runOnUiThread(this::fetchOrRender);
                if (e instanceof Api.AuthException) finish();
            } finally { running0.remove(id); }
        });
    }
    private void failedMenu(String id) {
        JSONObject j; synchronized (outbox) { j = outbox.get(id); }
        if (j == null) return;
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("Сообщение не отправлено")
                .setItems(new String[]{"Повторить", "Удалить"}, (d, w) -> {
                    if (w == 0) { j.remove("failed"); saveOutbox(); lastSig = null; fetchOrRender(); if (!online) toast("Отправится, когда появится сеть"); outRun(j); }
                    else { synchronized (outbox) { outbox.remove(id); } if (j.has("file")) new java.io.File(j.optString("file")).delete(); saveOutbox(); lastSig = null; fetchOrRender(); }
                }).show();
    }
    private volatile String pendingId = null;

    /** Тост над полем ввода на 3 секунды (как на сайте). Можно звать из любого потока. */
    private TextView toastV;
    void toast(String msg) {
        runOnUiThread(() -> {
            android.widget.FrameLayout outer = (android.widget.FrameLayout) roundBox.getParent();
            if (toastV == null) {
                toastV = Site.label(this, "", 14, 0xFFFFFFFF, false);
                toastV.setBackground(Site.round(0xE6303036, Ui.dp(this, 12)));
                toastV.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 16), Ui.dp(this, 10));
                toastV.setGravity(Gravity.CENTER);
                android.widget.FrameLayout.LayoutParams tl = new android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
                tl.bottomMargin = Ui.dp(this, 76); tl.leftMargin = tl.rightMargin = Ui.dp(this, 16);
                outer.addView(toastV, tl);
            }
            toastV.setText(msg); toastV.setVisibility(android.view.View.VISIBLE); toastV.bringToFront();
            h.removeCallbacks(hideToast); h.postDelayed(hideToast, 3000);
        });
    }
    private final Runnable hideToast = () -> { if (toastV != null) toastV.setVisibility(android.view.View.GONE); };

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
                .setNeutralButton("🔒 Код безопасности", (d, w) -> { if (e2e) showSafety(); else toast("Шифрование ещё не готово"); }).show();
    }

    /** previewOf() сайта: текст, «📎 имя» или подпись голосового/кружка. */
    private String quoteBody(JSONObject q) {
        JSONObject pl = q.isNull("ciphertext") ? null : dec.get(q.optString("id"));
        if (q.isNull("ciphertext")) return q.isNull("body") || q.optString("body").isEmpty() ? "Сообщение" : q.optString("body");
        if (pl != null && "other".equals(pl.optString("err"))) return OTHER_DEV;
        if (pl == null || pl.has("err")) return "🔒 Зашифрованное сообщение";
        JSONObject f = pl.optJSONObject("f");
        if (f != null && ("voice".equals(f.optString("vk")) || "round".equals(f.optString("vk")))) return Cache.previewOf(pl);
        String t = pl.optString("t", "");
        return !t.isEmpty() ? t : f != null ? "📎 " + f.optString("n", "файл") : "Сообщение";
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

    private static final String[] REACT_EMOJI = {"👍", "❤️", "😂", "😮", "😢", "🔥"};
    /** v13: таблетка реакций + Ответить, Копировать текст, Изменить, Закрепить, —, Удалить у всех. */
    private void actions(android.view.View anchor, JSONObject m, boolean mine, Map<String, int[]> em) {
        final String id = m.optString("id");
        final JSONObject pl = m.isNull("ciphertext") ? null : dec.get(id);
        final String body = m.isNull("ciphertext") ? (m.isNull("body") ? "" : m.optString("body")) : (pl != null && !pl.has("err") ? pl.optString("t") : "");
        java.util.List<Site.Item> items = new java.util.ArrayList<>();
        items.add(new Site.Item("reply", "Ответить", false, () -> {
            replyTo = id;
            replyBar.setText("↪ " + quoteOf(m, Api.get(this).uid()) + "   ✕");
            replyBar.setVisibility(android.view.View.VISIBLE);
        }));
        if (!body.isEmpty()) items.add(new Site.Item("content_copy", "Копировать текст", false, () -> {
            android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(android.content.ClipData.newPlainText("msg", body));
            toast("Текст скопирован");
        }));
        if (mine && !body.isEmpty() && !m.isNull("ciphertext") && e2e && ("direct".equals(kind) || isGroup()) && (pl == null || pl.optJSONObject("f") == null))
            items.add(new Site.Item("edit", "Изменить", false, () -> edit(id, body)));
        if (canPin()) items.add(pins.contains(id) ? new Site.Item("push_pin", "Открепить", false, () -> pinMsg(id, false)) : new Site.Item("push_pin", "Закрепить", false, () -> pinMsg(id, true)));
        if (mine) { items.add(null); items.add(new Site.Item("delete", "Удалить у всех", true, () -> confirmDelete(m))); }
        if (items.get(items.size() - 1) == null) items.remove(items.size() - 1);
        java.util.Set<String> chosen = new java.util.HashSet<>();
        if (em != null) for (Map.Entry<String, int[]> en : em.entrySet()) if (en.getValue()[1] == 1) chosen.add(en.getKey());
        Site.messageMenu(anchor, REACT_EMOJI, chosen, e -> toggleReact(id, e, em), items);
    }
    private static String notifText(JSONObject pl) {
        JSONObject f = pl.optJSONObject("f");
        if (f != null && "voice".equals(f.optString("vk"))) return "🎤 Голосовое сообщение " + Cache.fmtDur(f.optLong("d"));
        if (f != null && "round".equals(f.optString("vk"))) return "⭕ Видеосообщение";
        String t = pl.optString("t");
        return !t.isEmpty() ? t : f != null ? (isImage(f) ? "Фото" : "Файл") : "Сообщение";
    }
    /** imgMime сайта: по mime или расширению (SVG не показываем). */
    /** avKind() сайта: 1 — аудио (mp3/m4a/aac/ogg/wav), 2 — видео (mp4/webm), 0 — иначе. */
    static int avKind(JSONObject f) {
        String m = f.optString("m", "").split(";")[0].trim().toLowerCase(java.util.Locale.ROOT), n = f.optString("n", "").toLowerCase(java.util.Locale.ROOT);
        String ext = n.contains(".") ? n.substring(n.lastIndexOf('.') + 1) : ""; boolean gen = m.isEmpty() || m.equals("application/octet-stream");
        if (m.matches("audio/(mpeg|mp3|mp4|x-m4a|m4a|aac|ogg|wav|x-wav|webm)")) return 1;
        if (gen && ext.matches("mp3|m4a|aac|ogg|oga|opus|wav|weba")) return 1;
        if (m.matches("video/(mp4|webm|x-m4v)")) return 2;
        if (gen && ext.matches("mp4|m4v|webm")) return 2;
        return 0;
    }
    private String peerName(String uid) {
        JSONObject p = Avatars.profiles.get(uid);
        if (p != null) return Avatars.displayName(p);
        String n = names.get(uid); if (n == null) return "…"; int i = n.indexOf(" @"); return i > 0 ? n.substring(0, i) : n.startsWith("@") ? n.substring(1) : n;
    }
    static boolean isImage(JSONObject f) {
        String m = f.optString("m", "").toLowerCase(java.util.Locale.ROOT);
        if (m.equals("image/jpeg") || m.equals("image/png") || m.equals("image/gif") || m.equals("image/webp")) return true;
        String n = f.optString("n", "").toLowerCase(java.util.Locale.ROOT);
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".gif") || n.endsWith(".webp");
    }
    private final Map<String, android.graphics.Bitmap> thumbs = new java.util.concurrent.ConcurrentHashMap<>();
    /** .img-prev: до 260×320, r12; пока грузится — 220×160 на surface-highest со спиннером. Нажатие — открыть файл. */
    /** .img-prev: до 260×320, r12; пока грузится — блок по iw/ih (surface-highest + спиннер); без сети — серый блок с иконкой image. */
    private android.view.View imagePreview(JSONObject f, boolean incoming) {
        android.widget.FrameLayout fr = new android.widget.FrameLayout(this);
        final String key = f.optString("p");
        int[] wh = MsgView.imgBox(this, f);
        android.graphics.Bitmap have = thumbs.get(key);
        if (have != null) showThumb(fr, have);
        else if (offlineImgs.contains(key) && !online) { fr.addView(MsgView.offlineImage(this, wh[0], wh[1])); }
        else {
            fr.setBackground(Site.round(getColor(R.color.web_color_surface_highest), MsgView.dp(this, 12)));
            fr.setMinimumWidth(wh[0]); fr.setMinimumHeight(wh[1]);
            android.view.View sp = Site.spinner(this);
            fr.addView(sp, new android.widget.FrameLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 28), Gravity.CENTER));
            io.execute(() -> {
                try {
                    java.io.File pf = MediaPlay.plain(this, f);
                    android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options(); o.inJustDecodeBounds = true;
                    android.graphics.BitmapFactory.decodeFile(pf.getAbsolutePath(), o);
                    int ss = 1; while (o.outWidth / ss > 1040 || o.outHeight / ss > 1280) ss *= 2;
                    o = new android.graphics.BitmapFactory.Options(); o.inSampleSize = ss;
                    android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeFile(pf.getAbsolutePath(), o);
                    if (bm == null) { runOnUiThread(() -> { fr.removeAllViews(); fr.addView(MsgView.fileRow(this, !incoming, f.optString("n", "file"), MsgView.size(f.optLong("s", 0)), false, v -> openE2eFile(f, incoming))); }); return; }
                    thumbs.put(key, bm); offlineImgs.remove(key);
                    runOnUiThread(() -> { fr.removeAllViews(); fr.setBackground(null); fr.setMinimumWidth(0); fr.setMinimumHeight(0); showThumb(fr, bm); });
                } catch (Exception e) {
                    boolean net = !online || e instanceof java.io.IOException;
                    if (net) offlineImgs.add(key);
                    runOnUiThread(() -> { fr.removeAllViews(); fr.setBackground(null);
                        if (net) fr.addView(MsgView.offlineImage(this, wh[0], wh[1]));
                        else { TextView t = Site.label(this, "Не удалось загрузить картинку", 13f, Site.text2(this), 400); t.setGravity(Gravity.CENTER);
                            fr.setBackground(Site.round(getColor(R.color.web_color_surface_highest), MsgView.dp(this, 12))); fr.addView(t, new android.widget.FrameLayout.LayoutParams(-1, -1)); } });
                }
            });
        }
        fr.setOnClickListener(v -> openE2eFile(f, incoming));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        fr.setLayoutParams(lp);
        return fr;
    }
    private final Set<String> offlineImgs = java.util.Collections.synchronizedSet(new HashSet<>());
    private void showThumb(android.widget.FrameLayout fr, android.graphics.Bitmap bm) {
        // CSS: max-width 260 / max-height 320 в css-px; натуральный размер картинки = её пиксели в css-px
        float s = Math.min(1f, Math.min(260f / bm.getWidth(), 320f / bm.getHeight())) * getResources().getDisplayMetrics().density;
        android.widget.ImageView iv = new android.widget.ImageView(this);
        iv.setScaleType(android.widget.ImageView.ScaleType.FIT_XY);
        androidx.core.graphics.drawable.RoundedBitmapDrawable rd = androidx.core.graphics.drawable.RoundedBitmapDrawableFactory.create(getResources(), bm);
        rd.setCornerRadius(MsgView.dpf(this, 12)); rd.setAntiAlias(true); // r12
        iv.setImageDrawable(rd);
        fr.addView(iv, new android.widget.FrameLayout.LayoutParams(Math.round(bm.getWidth() * s), Math.round(bm.getHeight() * s)));
    }
    private void confirmDelete(JSONObject m) {
        final String id = m.optString("id");
        JSONObject pl = dec.get(id); JSONObject f = pl == null ? null : pl.optJSONObject("f");
        final String ap = f != null && f.has("p") ? f.optString("p") : (m.isNull("attachment_path") ? null : m.optString("attachment_path"));
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setMessage("Удалить сообщение у всех участников чата? Отменить будет нельзя.")
                .setPositiveButton("Удалить", (d, w) -> io.execute(() -> {
                    try {
                        Api api = Api.get(this);
                        api.delete("messages?id=eq." + id);
                        if (ap != null && ap.startsWith("chat/" + chatId + "/")) ChatFiles.remove(ap, api.freshToken());
                        dec.remove(id); lastSig = null; toast("Сообщение удалено"); fetch();
                    } catch (Exception e) { toast(Ui.err(e)); }
                })).setNegativeButton("Отмена", null).show();
    }

    private void toggleReact(String id, String e, Map<String, int[]> em) {
        final boolean had = em != null && em.containsKey(e) && em.get(e)[1] == 1;
        io.execute(() -> {
            try {
                Api api = Api.get(this);
                if (had) api.delete("reactions?message_id=eq." + id + "&user_id=eq." + api.uid() + "&emoji=eq." + java.net.URLEncoder.encode(e, "UTF-8"));
                else api.insert("reactions", new JSONObject().put("message_id", id).put("user_id", api.uid()).put("emoji", e));
                lastSig = null; fetch();
            } catch (Exception ex) {
                toast(ex instanceof Api.HttpError && ((Api.HttpError) ex).code == 403 ? "Не больше 10 реакций на сообщение" : Ui.err(ex));
                if (ex instanceof Api.AuthException) runOnUiThread(this::finish);
            }
        });
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
                            toast(ex instanceof Api.HttpError && ((Api.HttpError) ex).code == 403 ? "Не больше 10 реакций на сообщение" : Ui.err(ex));
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
                        catch (Exception e) { toast(Ui.err(e)); if (e instanceof Api.AuthException) runOnUiThread(this::finish); }
                    });
                }).setNegativeButton("Отмена", null).show();
    }

    static final String OTHER_DEV = "Отправлено до входа на этом устройстве";
    static final String PLAIN_MARK = "⚠ не зашифровано";
    private String textOf(JSONObject m) {
        if (m.isNull("ciphertext")) {
            String b = m.isNull("body") ? "" : m.optString("body", "");
            if (b.isEmpty() && m.isNull("attachment_path")) return "(пустое сообщение)"; // никогда не прячем молча
            return b;
        }
        JSONObject pl = dec.get(m.optString("id"));
        if (pl == null) return "Расшифровка…"; // как на сайте: курсив, 🔒 — в строке времени
        if (pl.has("err")) return "other".equals(pl.optString("err")) ? OTHER_DEV : "Не удалось расшифровать";
        String t = captionOf(pl);
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
            if (pendingId != null) row.put("id", pendingId); // id задаёт клиент (как на сайте): повтор не создаёт дубль
            String id = api.insertReturningId("messages", row);
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
                    if (pendingId != null) row.put("id", pendingId);
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
                        catch (Exception e) { toast(Ui.err(e)); }
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
            } catch (Exception e) { toast("Код безопасности недоступен: " + Ui.err(e)); }
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
            toast("Загружаю и расшифровываю файл…");
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
                        catch (Exception e) { toast("Нет приложения для открытия файла"); }
                    });
                } catch (Exception e) {
                    toast(Ui.err(e));
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
        if (upto == null || (deliveredUpto != null && Ui.instantMicros(deliveredUpto) >= Ui.instantMicros(upto))) return;
        try { Api.get(this).rpc("mark_delivered", new JSONObject().put("p_chat", chatId).put("p_upto", upto)); deliveredUpto = upto; }
        catch (Exception e) { if (BuildConfig.DEBUG) android.util.Log.w("Dlv", "mark_delivered: " + e); }
    }

    /** Отправка записанного голосового/кружка: AES-256-GCM, chat/<chat>/<uuid>_file.bin, octet-stream; vk/d/w — внутри шифротекста. */
    private void sendMedia(byte[] plain, String name, String mime, JSONObject media) {
        toast("voice".equals(media.optString("vk")) ? "Отправляю голосовое…" : "Отправляю видеосообщение…");
        io.execute(() -> {
            try { outFile(plain, name, mime, media); }
            catch (Exception e) { toast(Ui.err(e)); if (e instanceof Api.AuthException) finish(); }
        });
    }
    /** Общая отправка файла (как sendFile на сайте). При ошибке вставки сообщения загруженный объект удаляется. */
    private void encryptAndSend(byte[] plain, String fname, String mime, JSONObject media, String caption) throws Exception {
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
            post(filePayload(f, caption), null, null);
        } catch (Exception e) {
            if (path != null) ChatFiles.remove(path, api.freshToken());
            throw e;
        } finally { et.delete(); pendingAttachment = null; }
    }
    /** Payload файла как sendFile сайта: {v:1, t:'', f, caption?}. */
    static JSONObject filePayload(JSONObject f, String caption) throws org.json.JSONException {
        JSONObject p = new JSONObject().put("v", 1).put("t", "").put("f", f);
        if (caption != null && !caption.trim().isEmpty()) p.put("caption", caption.trim().length() > 4000 ? caption.trim().substring(0, 4000) : caption.trim());
        return p;
    }
    /** Подпись к вложению: t, иначе caption (сайт кладёт подпись в caption, t пустой). */
    static String captionOf(JSONObject pl) {
        if (pl == null) return "";
        String t = pl.optString("t", "");
        return !t.isEmpty() ? t : pl.optJSONObject("f") != null ? pl.optString("caption", "") : "";
    }
    /** attachment_path для строки messages (только путь; имя, размер и ключ — в шифротексте). */
    private volatile String pendingAttachment = null;

    /** Прочитано — только если чат реально на экране (в фокусе) и только до последнего показанного сообщения собеседника. */
    private void markRead() {
        final String upto = seenUpto; if (chatId != null) Notify.cancel(this, chatId);
        if (upto == null || !running || !hasWindowFocus()) return;
        if (markedUpto != null && Ui.instantMicros(markedUpto) >= Ui.instantMicros(upto)) return; // без повторов (иначе цикл через Realtime)
        markedUpto = upto;
        io.execute(() -> {
            try { Api.get(this).rpc("mark_read", new JSONObject().put("p_chat", chatId).put("p_upto", upto)); }
            catch (Exception e) { markedUpto = null; if (BuildConfig.DEBUG) android.util.Log.w("Read", "mark_read: " + e); }
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
        if (size > ChatFiles.MAX) { toast("Файл больше 25 МБ"); return; }
        final String fname = ChatFiles.safeName(name);
        final long fsize = size;
        final String mime = getContentResolver().getType(uri);
        final String cap = composer == null ? "" : composer.getText().toString().trim(); // v14: текст из поля — подпись к файлу (как на сайте)
        if (!cap.isEmpty()) composer.setText("");
        toast("Отправляю «" + fname + "»…");
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
                byte[] plain;
                try (java.io.InputStream is = getContentResolver().openInputStream(src)) { plain = readAll(is); }
                outFile(plain, fname, mime, null, cap);
            } catch (Exception e) {
                toast(Ui.err(e));
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
        toast("Загружаю файл…");
        io.execute(() -> {
            try {
                java.io.File f = ChatFiles.download(this, path, Api.get(this).freshToken());
                runOnUiThread(() -> {
                    try { ChatFiles.open(this, f, name); }
                    catch (Exception e) { toast("Нет приложения для открытия файла"); }
                });
            } catch (Exception e) {
                toast(Ui.err(e));
                if (e instanceof Api.AuthException) runOnUiThread(this::finish);
            }
        });
    }
}
