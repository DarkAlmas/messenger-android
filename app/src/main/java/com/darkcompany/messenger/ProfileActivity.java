package com.darkcompany.messenger;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Мой профиль: имя, никнейм, о себе, аватар (фото или буква + цвет). */
public class ProfileActivity extends AppCompatActivity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private FrameLayout avatarBox;
    private EditText name, nick, bio;
    private TextView note;
    private String letter;
    private int color;

    private final ActivityResultLauncher<String> pick = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
        if (uri == null) return;
        io.execute(() -> {
            try {
                Avatars.saveSquare(this, uri, Avatars.myPhotoFile(this));
                runOnUiThread(this::drawAvatar);
                try {
                    JSONObject cur = Avatars.profiles.get(Api.get(this).uid());
                    String old = cur == null || cur.isNull("avatar_url") ? "" : cur.optString("avatar_url");
                    String path = Avatars.uploadMyPhoto(this, Avatars.myPhotoFile(this)); // новое имя каждый раз
                    Api.get(this).update("profiles?id=eq." + Api.get(this).uid(), new JSONObject().put("avatar_url", path));
                    Avatars.deletePhoto(this, old);
                    Avatars.refresh(this, Collections.singleton(Api.get(this).uid()), 0);
                    Ui.toast(this, "Фото профиля обновлено");
                } catch (Exception e) {
                    setNote("Фото сохранено на этом телефоне. Собеседники увидят его после обновления сервера (" + Ui.err(e) + ").");
                }
            } catch (Exception e) { Ui.toast(this, "Не удалось загрузить фото: " + Ui.err(e)); }
        });
    });

    private TextView nameV, nickV, bioV, counter;
    private LinearLayout editCard;
    private EditText letterIn;
    private int chosen;
    private final java.util.List<View> swatches = new java.util.ArrayList<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!Api.get(this).hasSession()) { finish(); return; }
        letter = Avatars.myLetter(this);
        color = Avatars.myColor(this);
        chosen = color;
        LinearLayout col = Site.scaffold(this, Site.pageTitle(this, "Профиль", "edit", "Изменить профиль", v -> toggleEdit()), 2);
        // .profile-head: большой аватар 120dp с кнопкой-камерой, имя, «в сети»
        LinearLayout head = new LinearLayout(this); head.setOrientation(LinearLayout.VERTICAL); head.setGravity(Gravity.CENTER_HORIZONTAL);
        head.setPadding(0, Ui.dp(this, 24), 0, Ui.dp(this, 8));
        FrameLayout ab = new FrameLayout(this);
        avatarBox = new FrameLayout(this);
        ab.addView(avatarBox, new FrameLayout.LayoutParams(Ui.dp(this, 120), Ui.dp(this, 120), Gravity.CENTER));
        TextView cam = Site.icon(this, "photo_camera", 20, 0xFFFFFFFF);
        cam.setBackground(Site.round(Site.primary(this), Ui.dp(this, 20)));
        cam.setContentDescription("Загрузить фото"); cam.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        cam.setOnClickListener(v -> pick.launch("image/*"));
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40), Gravity.END | Gravity.BOTTOM);
        cl.setMargins(0, 0, 0, Ui.dp(this, 2));
        ab.addView(cam, cl);
        head.addView(ab, new LinearLayout.LayoutParams(Ui.dp(this, 132), Ui.dp(this, 124)));
        nameV = Site.label(this, "", 24, Site.text(this), true); nameV.setGravity(Gravity.CENTER); nameV.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), 0);
        head.addView(nameV, new LinearLayout.LayoutParams(-1, -2));
        TextView st = Site.label(this, "в сети", 15, Site.primary(this), false); st.setGravity(Gravity.CENTER);
        head.addView(st, new LinearLayout.LayoutParams(-1, -2));
        col.addView(head);

        LinearLayout g = Site.group(this, col, "Информация");
        LinearLayout r1 = Site.row(this, g, "alternate_email", "@", "Имя пользователя", null, null);
        nickV = r1.findViewWithTag("label");
        LinearLayout r2 = Site.row(this, g, "info", "Не указано", "О себе", null, null);
        bioV = r2.findViewWithTag("label");
        note = Site.hint(this, "");
        note.setVisibility(View.GONE);
        col.addView(note);

        // .edit-card «Редактирование» (по ✎)
        LinearLayout wrap = new LinearLayout(this); wrap.setOrientation(LinearLayout.VERTICAL); wrap.setVisibility(View.GONE);
        col.addView(wrap);
        editCard = wrap;
        LinearLayout ec = Site.group(this, wrap, "Редактирование");
        ec.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 16), Ui.dp(this, 16));
        name = Ui.input(this, "Имя");
        name.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(64)});
        nick = Ui.input(this, "Никнейм");
        nick.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(32)});
        bio = Ui.input(this, "О себе");
        bio.setSingleLine(false); bio.setMaxLines(5);
        bio.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(300)});
        letterIn = Ui.input(this, "Буквы на аватаре (1–2 символа, пусто — первая буква имени)");
        letterIn.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(2)});
        letterIn.setText(letter);
        ec.addView(Ui.field(name), Ui.spaced(this));
        ec.addView(Ui.field(nick), Ui.spaced(this));
        ec.addView(Ui.field(bio), Ui.spaced(this));
        counter = Site.label(this, "0/300", 12, Site.text2(this), false); counter.setGravity(Gravity.END);
        ec.addView(counter, new LinearLayout.LayoutParams(-1, -2));
        bio.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence a, int b1, int c, int d) { }
            public void onTextChanged(CharSequence a, int b1, int c, int d) { counter.setText(a.length() + "/300"); }
            public void afterTextChanged(android.text.Editable e) { }
        });
        ec.addView(Ui.field(letterIn), Ui.spaced(this));
        ec.addView(Site.label(this, "Цвет фона", 13, Site.text2(this), false), Ui.spaced(this));
        android.widget.GridLayout grid = new android.widget.GridLayout(this);
        grid.setColumnCount(6);
        for (int c0 : Avatars.PALETTE) {
            View sw = new View(this);
            android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
            gd.setShape(android.graphics.drawable.GradientDrawable.OVAL); gd.setColor(c0);
            gd.setStroke(c0 == chosen ? Ui.dp(this, 3) : 0, Site.text(this));
            sw.setBackground(gd); sw.setTag(c0);
            sw.setContentDescription(String.format("#%06X", c0 & 0xFFFFFF));
            sw.setOnClickListener(v -> { chosen = (Integer) v.getTag(); paintSwatches(); });
            swatches.add(sw);
            android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams();
            lp.width = Ui.dp(this, 36); lp.height = Ui.dp(this, 36);
            lp.setMargins(Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
            grid.addView(sw, lp);
        }
        ec.addView(grid);
        View rm = Ui.textButton(this, "Убрать фото");
        rm.setOnClickListener(v -> applyAuto(true));
        ec.addView(rm, Ui.spaced(this));
        View save = Ui.button(this, "Сохранить");
        save.setOnClickListener(v -> save());
        ec.addView(save, Ui.spaced(this));

        col.addView(Site.hint(this, "Имя, никнейм, «О себе» и аватар видны другим пользователям и не шифруются сквозным шифрованием. Сообщения и файлы шифруются всегда."));
        paintInfo(Avatars.merged(this, Api.get(this).uid()));
        drawAvatar();
        io.execute(this::load);
    }

    private void toggleEdit() { editCard.setVisibility(editCard.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); }
    private void paintSwatches() {
        for (View o : swatches) ((android.graphics.drawable.GradientDrawable) o.getBackground()).setStroke((Integer) o.getTag() == chosen ? Ui.dp(this, 3) : 0, Site.text(this));
    }
    private void paintInfo(JSONObject p) {
        String un = p.optString("username", "");
        String dn = p.isNull("display_name") ? "" : p.optString("display_name", "");
        String bi = p.has("bio") && !p.isNull("bio") ? p.optString("bio") : Avatars.prefs(this).getString("bio", "");
        nameV.setText(dn.isEmpty() ? un : dn);
        nickV.setText("@" + un);
        bioV.setText(bi.isEmpty() ? "Не указано" : bi);
        bioV.setTextColor(bi.isEmpty() ? Site.text2(this) : Site.text(this));
    }

    @Override public boolean onSupportNavigateUp() { finish(); return true; }

    private void setNote(String s) { runOnUiThread(() -> { note.setText(s); note.setVisibility(s.isEmpty() ? View.GONE : View.VISIBLE); }); }

    private void drawAvatar() {
        avatarBox.removeAllViews();
        String uid = Api.get(this).uid();
        String n = nameV == null ? "?" : nameV.getText().toString();
        avatarBox.addView(Avatars.view(this, uid, n.isEmpty() ? "?" : n, 120), new FrameLayout.LayoutParams(-1, -1));
    }

    private void load() {
        try {
            String uid = Api.get(this).uid();
            Avatars.refresh(this, Collections.singleton(uid), 0);
            JSONObject p = Avatars.profiles.get(uid);
            if (p == null) return;
            runOnUiThread(() -> {
                name.setText(p.isNull("display_name") ? "" : p.optString("display_name"));
                nick.setText(p.optString("username"));
                String lb = Avatars.prefs(this).getString("bio", "");
                bio.setText(p.has("bio") && !p.isNull("bio") ? p.optString("bio") : lb);
                paintInfo(Avatars.merged(this, uid));
                drawAvatar();
                if (Boolean.FALSE.equals(Avatars.serverHasFields))
                    setNote("Сервер пока не поддерживает фото, цвет и «о себе». Имя и никнейм менять можно.");
            });
        } catch (Exception e) { Ui.toast(this, Ui.err(e)); }
    }

    /** Буква + цвет из карточки редактирования; removePhoto — «Убрать фото». */
    private void applyAuto(boolean removePhoto) {
        String l = letterIn.getText().toString().trim();
        if (l.equals(letter) && chosen == color && !removePhoto) return;
        letter = l; color = chosen;
        Avatars.prefs(this).edit().putString("letter", letter).putInt("color", color).apply();
        Avatars.myPhotoFile(this).delete();
        drawAvatar();
        io.execute(() -> {
            try {
                JSONObject cur = Avatars.profiles.get(Api.get(this).uid());
                String old = cur == null || cur.isNull("avatar_url") ? "" : cur.optString("avatar_url");
                Api.get(this).update("profiles?id=eq." + Api.get(this).uid(), new JSONObject().put("avatar_letter", letter.isEmpty() ? JSONObject.NULL : letter)
                        .put("avatar_color", String.format("#%06X", color & 0xFFFFFF)).put("avatar_url", JSONObject.NULL));
                Avatars.deletePhoto(this, old);
                Avatars.refresh(this, Collections.singleton(Api.get(this).uid()), 0);
                runOnUiThread(this::drawAvatar);
                if (removePhoto) Ui.toast(this, "Фото удалено");
            } catch (Exception e) { setNote("Аватар сохранён на этом телефоне; на сервер — после его обновления."); }
        });
    }

    private void save() {
        final String n = name.getText().toString().trim(), u = nick.getText().toString().trim().replaceFirst("^@+", "").toLowerCase(java.util.Locale.ROOT),
                bi = bio.getText().toString().trim();
        if (n.isEmpty() || n.length() > 64) { Ui.toast(this, "Имя: от 1 до 64 символов"); return; }
        JSONObject curP = Avatars.profiles.get(Api.get(this).uid());
        boolean nickChanged = curP == null || !u.equals(curP.optString("username"));
        if (nickChanged && !u.matches("[a-z0-9_]{5,32}")) { Ui.toast(this, "От 5 до 32 символов: латиница, цифры и _"); return; }
        applyAuto(false);
        Avatars.prefs(this).edit().putString("bio", bi).apply();
        io.execute(() -> {
            try {
                Api api = Api.get(this);
                api.update("profiles?id=eq." + api.uid(), new JSONObject().put("display_name", n.isEmpty() ? JSONObject.NULL : n).put("username", u));
                String extra = "";
                try { api.update("profiles?id=eq." + api.uid(), new JSONObject().put("bio", bi)); }
                catch (Exception e) { extra = " «О себе» сохранено на телефоне — сервер ещё не поддерживает это поле."; }
                Avatars.refresh(this, Collections.singleton(api.uid()), 0);
                Ui.toast(this, "Профиль сохранён." + extra);
                runOnUiThread(() -> { paintInfo(Avatars.merged(this, api.uid())); drawAvatar(); editCard.setVisibility(View.GONE); });
            } catch (Exception e) {
                String m = Ui.err(e);
                Ui.toast(this, m.contains("duplicate") || m.contains("unique") ? "Этот никнейм уже занят" : "Не сохранено: " + m);
            }
        });
    }
}
