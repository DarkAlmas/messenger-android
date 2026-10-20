package com.darkcompany.messenger;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;

/** v11: «Выберите имя пользователя» после входа через Google (account.js сайта: check_username / set_username). */
final class NickDialog {
    static final Pattern NICK = Pattern.compile("^[a-z0-9_]{5,32}$");
    static final String RULE = "От 5 до 32 символов: латиница, цифры и _";

    static String message(String state, String n) {
        switch (state) {
            case "ok": return "@" + n + " свободно";
            case "reserved": return "Это имя недоступно";
            case "taken": return "Это имя уже занято";
            case "rate": return "Слишком часто, подождите немного";
            case "checking": return "Проверяем…";
            default: return RULE;
        }
    }
    /** Как input-обработчик сайта: нижний регистр, только [a-z0-9_], не длиннее 32. */
    static String clean(String s) { String v = s.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_]", ""); return v.length() > 32 ? v.substring(0, 32) : v; }
    /** suggest() сайта: из имени/почты, 3 варианта. */
    static Set<String> suggest(String fullName, String email, Random r) {
        String src = fullName != null && !fullName.isEmpty() ? fullName : email != null && email.contains("@") ? email.substring(0, email.indexOf('@')) : "user";
        String base = Normalizer.normalize(src.toLowerCase(java.util.Locale.ROOT), Normalizer.Form.NFKD).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (base.length() > 24) base = base.substring(0, 24);
        if (base.length() < 3) base = "user";
        Set<String> seen = new LinkedHashSet<>();
        while (seen.size() < 3) seen.add(base + (seen.isEmpty() && base.length() >= 5 ? "" : String.valueOf(10 + r.nextInt(990))));
        return seen;
    }
    static boolean isRate(Exception e) {
        if (e instanceof Api.HttpError) { Api.HttpError h = (Api.HttpError) e; if (h.code == 429 || "P0429".equals(h.pgCode)) return true; }
        return String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT).contains("rate limited");
    }
    static String unq(String s) { s = s.trim(); return s.startsWith("\"") ? s.substring(1, s.length() - 1) : s; }

    /** Профиль без выбранного имени (username_chosen = false) — показываем блокирующее окно. Вызывать в фоне. */
    static void checkAndShow(Activity a) {
        try {
            JSONArray r = Api.get(a).select("profiles?select=username_chosen&id=eq." + Api.get(a).uid());
            if (r.length() == 0 || r.getJSONObject(0).isNull("username_chosen") || r.getJSONObject(0).optBoolean("username_chosen", true)) return;
            String full = null;
            try { // user_metadata из /auth/v1/user (имя из Google)
                JSONObject u = new JSONObject(Api.get(a).raw("GET", "/auth/v1/user", null, false));
                JSONObject md = u.optJSONObject("user_metadata");
                if (md != null) full = md.optString("full_name", md.optString("name", null));
            } catch (Exception ignored) { }
            final String fn = full;
            a.runOnUiThread(() -> show(a, fn));
        } catch (Exception ignored) { }
    }

    static void show(Activity a, String fullName) {
        if (a.isFinishing()) return;
        LinearLayout box = new LinearLayout(a); box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(a, 24); box.setPadding(p, Ui.dp(a, 8), p, 0);
        box.addView(Site.label(a, "По нему вас смогут найти.", 14f, Site.text2(a), 400));
        LinearLayout field = new LinearLayout(a); field.setGravity(android.view.Gravity.CENTER_VERTICAL);
        field.setBackground(Site.round(0, Ui.dp(a, 10)));
        android.graphics.drawable.GradientDrawable fg = Site.round(0, Ui.dp(a, 10)); fg.setStroke(Math.max(1, Ui.dp(a, 1)), Site.divider(a)); field.setBackground(fg);
        field.setPadding(Ui.dp(a, 10), 0, Ui.dp(a, 10), 0);
        field.addView(Site.label(a, "@", 16f, Site.text2(a), 400));
        EditText in = new EditText(a); in.setBackground(null); in.setSingleLine(true); in.setTextColor(Site.text(a));
        in.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        field.addView(in, new LinearLayout.LayoutParams(0, Ui.dp(a, 46), 1f));
        LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(-1, -2); fl.topMargin = Ui.dp(a, 12); box.addView(field, fl);
        TextView hint = Site.label(a, RULE, 13f, Site.text2(a), 400); hint.setMinHeight(Ui.dp(a, 18));
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(-1, -2); hl.topMargin = hl.bottomMargin = Ui.dp(a, 6); box.addView(hint, hl);
        LinearLayout chips = new LinearLayout(a);
        for (String s : suggest(fullName, Api.get(a).email(), new Random())) {
            TextView c = Site.label(a, "@" + s, 14f, Site.text(a), 400);
            c.setBackground(Site.round(Site.col(a, R.color.web_color_secondary_container), Ui.dp(a, 16)));
            c.setPadding(Ui.dp(a, 10), Ui.dp(a, 4), Ui.dp(a, 10), Ui.dp(a, 4));
            c.setOnClickListener(v -> { in.setText(s); in.setSelection(s.length()); });
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-2, -2); cl.rightMargin = Ui.dp(a, 6); chips.addView(c, cl);
        }
        box.addView(chips);
        AlertDialog d = new AlertDialog.Builder(a).setTitle("Выберите имя пользователя").setView(box)
                .setPositiveButton("Продолжить", null).setNegativeButton("Выйти", null).setCancelable(false).create();
        final int[] seq = {0};
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        final String[] state = {"invalid"};
        Runnable[] check = new Runnable[1];
        java.util.function.BiConsumer<String, Boolean> paint = (text, bad) -> { hint.setText(text); hint.setTextColor(bad ? Site.danger(a) : text.endsWith("свободно") ? Site.col(a, R.color.web_color_online) : Site.text2(a)); };
        check[0] = () -> {
            String n = in.getText().toString(); int my = ++seq[0];
            android.widget.Button go = d.getButton(AlertDialog.BUTTON_POSITIVE);
            if (go != null) go.setEnabled(false);
            if (!NICK.matcher(n).matches()) { state[0] = "invalid"; paint.accept(RULE, !n.isEmpty()); return; }
            paint.accept(message("checking", n), false);
            new Thread(() -> {
                String st;
                try { st = unq(Api.get(a).rpc("check_username", new JSONObject().put("p_username", n))); }
                catch (Exception e) { st = isRate(e) ? "rate" : "error:" + Ui.err(e); }
                final String fs = st;
                a.runOnUiThread(() -> {
                    if (my != seq[0]) return; // устаревший ответ
                    state[0] = fs;
                    if (fs.equals("rate")) { paint.accept(message("rate", n), false); h.postDelayed(check[0], 3000); return; }
                    if (fs.startsWith("error:")) { paint.accept(fs.substring(6), true); return; }
                    paint.accept(message(fs, n), !fs.equals("ok"));
                    android.widget.Button g2 = d.getButton(AlertDialog.BUTTON_POSITIVE); if (g2 != null) g2.setEnabled(fs.equals("ok"));
                });
            }).start();
        };
        in.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int af) { }
            public void onTextChanged(CharSequence s, int st, int b, int c) { }
            public void afterTextChanged(Editable e) {
                String v = clean(e.toString());
                if (!v.equals(e.toString())) { in.setText(v); in.setSelection(v.length()); return; }
                seq[0]++; h.removeCallbacks(check[0]); h.postDelayed(check[0], 300);
            }
        });
        d.setOnShowListener(x -> {
            android.widget.Button go = d.getButton(AlertDialog.BUTTON_POSITIVE); go.setEnabled(false);
            go.setOnClickListener(v -> {
                String n = in.getText().toString(); go.setEnabled(false);
                new Thread(() -> {
                    String r;
                    try { r = unq(Api.get(a).rpc("set_username", new JSONObject().put("p_username", n))); }
                    catch (Exception e) { r = isRate(e) ? "rate" : "error:" + Ui.err(e); }
                    final String fr = r;
                    a.runOnUiThread(() -> {
                        if (fr.equals("ok")) { d.dismiss(); Ui.toast(a, "Имя сохранено"); return; }
                        if (fr.equals("taken")) { paint.accept("Только что заняли, выберите другое", true); return; }
                        if (fr.startsWith("error:")) { paint.accept(fr.substring(6), true); go.setEnabled(true); return; }
                        paint.accept(message(fr, n), !fr.equals("rate"));
                    });
                }).start();
            });
            d.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> { d.dismiss(); ChatsActivity.logout(a); });
        });
        d.show();
    }
}
