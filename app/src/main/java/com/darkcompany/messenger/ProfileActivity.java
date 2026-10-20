package com.darkcompany.messenger;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
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

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!Api.get(this).hasSession()) { finish(); return; }
        setTitle("Профиль");
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        letter = Avatars.myLetter(this);
        color = Avatars.myColor(this);
        LinearLayout col = Ui.column(this);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        avatarBox = new FrameLayout(this);
        col.addView(avatarBox, new LinearLayout.LayoutParams(Ui.dp(this, 112), Ui.dp(this, 112)));
        LinearLayout btns = new LinearLayout(this);
        btns.setGravity(Gravity.CENTER);
        View photo = Ui.textButton(this, "📷 Фото");
        photo.setOnClickListener(v -> pick.launch("image/*"));
        View auto = Ui.textButton(this, "🔤 Буква и цвет");
        auto.setOnClickListener(v -> editAuto());
        btns.addView(photo); btns.addView(auto);
        LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, -2); bl.topMargin = Ui.dp(this, 8);
        col.addView(btns, bl);
        name = Ui.input(this, "Имя (видно собеседникам)");
        nick = Ui.input(this, "Никнейм (для поиска, латиница)");
        bio = Ui.input(this, "О себе");
        bio.setSingleLine(false); bio.setMaxLines(4);
        bio.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(300)});
        for (EditText e : new EditText[]{name, nick, bio}) col.addView(Ui.field(e), Ui.spaced(this));
        View save = Ui.button(this, "Сохранить");
        save.setOnClickListener(v -> save());
        col.addView(save, Ui.spaced(this));
        note = Ui.text(this, "", 13);
        note.setTextColor(Ui.gray());
        col.addView(note, Ui.spaced(this));
        TextView priv = Ui.text(this, "Имя, никнейм, «о себе» и аватар видны другим пользователям и не шифруются сквозным шифрованием. Сообщения и файлы — шифруются всегда.", 12);
        priv.setTextColor(Ui.gray());
        col.addView(priv, Ui.spaced(this));
        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        setContentView(sv);
        Ui.fitSystemBars(this, sv);
        drawAvatar();
        io.execute(this::load);
    }

    @Override public boolean onSupportNavigateUp() { finish(); return true; }

    private void setNote(String s) { runOnUiThread(() -> note.setText(s)); }

    private void drawAvatar() {
        avatarBox.removeAllViews();
        String uid = Api.get(this).uid();
        avatarBox.addView(Avatars.view(this, uid, name == null || name.getText().length() == 0 ? (nick == null ? "?" : nick.getText().toString()) : name.getText().toString(), 112),
                new FrameLayout.LayoutParams(-1, -1));
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
                drawAvatar();
                if (Boolean.FALSE.equals(Avatars.serverHasFields))
                    note.setText("«О себе» и аватар пока хранятся только на этом телефоне: сервер ещё не обновлён.");
            });
        } catch (Exception e) { Ui.toast(this, Ui.err(e)); }
    }

    private void editAuto() {
        LinearLayout c = Ui.column(this);
        final EditText l = Ui.input(this, "Буква или эмодзи (1–2 символа)");
        l.setText(letter);
        l.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(4)});
        c.addView(Ui.field(l));
        final int[] chosen = {color};
        android.widget.GridLayout grid = new android.widget.GridLayout(this);
        grid.setColumnCount(6);
        final java.util.List<View> sw = new java.util.ArrayList<>();
        for (int col : Avatars.PALETTE) {
            View s = new View(this);
            android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
            g.setShape(android.graphics.drawable.GradientDrawable.OVAL); g.setColor(col);
            g.setStroke(Ui.dp(this, col == chosen[0] ? 3 : 0), 0xFFFFFFFF);
            s.setBackground(g);
            s.setContentDescription(String.format("#%06X", col & 0xFFFFFF));
            s.setOnClickListener(v -> {
                chosen[0] = col;
                for (View o : sw) ((android.graphics.drawable.GradientDrawable) o.getBackground()).setStroke(o == v ? Ui.dp(this, 3) : 0, 0xFFFFFFFF);
            });
            sw.add(s);
            android.widget.GridLayout.LayoutParams lp = new android.widget.GridLayout.LayoutParams();
            lp.width = Ui.dp(this, 40); lp.height = Ui.dp(this, 40);
            lp.setMargins(Ui.dp(this, 5), Ui.dp(this, 5), Ui.dp(this, 5), Ui.dp(this, 5));
            grid.addView(s, lp);
        }
        c.addView(grid, Ui.spaced(this));
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(this).setTitle("Автоаватар").setView(c)
                .setPositiveButton("Готово", (d, w) -> {
                    letter = l.getText().toString().trim();
                    color = chosen[0];
                    Avatars.prefs(this).edit().putString("letter", letter).putInt("color", color).apply();
                    Avatars.myPhotoFile(this).delete(); // автоаватар вместо фото
                    drawAvatar();
                    io.execute(() -> {
                        try {
                            JSONObject cur = Avatars.profiles.get(Api.get(this).uid());
                            String old = cur == null || cur.isNull("avatar_url") ? "" : cur.optString("avatar_url");
                            Api.get(this).update("profiles?id=eq." + Api.get(this).uid(), new JSONObject().put("avatar_letter", letter.isEmpty() ? JSONObject.NULL : letter)
                                    .put("avatar_color", String.format("#%06X", color & 0xFFFFFF)).put("avatar_url", JSONObject.NULL));
                            Avatars.deletePhoto(this, old);
                            Avatars.refresh(this, Collections.singleton(Api.get(this).uid()), 0);
                        } catch (Exception e) { setNote("Аватар сохранён на этом телефоне; на сервер — после его обновления."); }
                    });
                }).setNegativeButton("Отмена", null).show();
    }

    private void save() {
        final String n = name.getText().toString().trim(), u = nick.getText().toString().trim().replaceFirst("^@+", "").toLowerCase(java.util.Locale.ROOT),
                bi = bio.getText().toString().trim();
        if (!u.matches("[a-z0-9_]{3,32}")) { Ui.toast(this, "Никнейм: 3–32 символа, латиница, цифры, _"); return; }
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
                runOnUiThread(this::drawAvatar);
            } catch (Exception e) {
                String m = Ui.err(e);
                Ui.toast(this, m.contains("duplicate") || m.contains("unique") ? "Этот никнейм уже занят" : "Не сохранено: " + m);
            }
        });
    }
}
