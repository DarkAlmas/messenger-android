package com.darkcompany.messenger;

import android.content.Context;
import android.graphics.Outline;
import android.media.MediaPlayer;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.VideoView;

import org.json.JSONArray;
import android.widget.ImageView;
import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.ExecutorService;

/** Голосовые (▶ + волна + длительность) и кружки (круглое видео) в пузырьке. Файл скачивается и расшифровывается при первом нажатии. */
final class MediaPlay {
    private MediaPlay() { }
    private static MediaPlayer player; private static TextView playing;
    private static final java.util.Map<String, File> plainFiles = new java.util.concurrent.ConcurrentHashMap<>();
    private static final String BARS = "▁▂▃▄▅▆▇█";

    static String waveText(JSONArray w) {
        if (w == null || w.length() == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < w.length(); i += 2) { int v = Math.max(1, Math.min(15, w.optInt(i, 1))); sb.append(BARS.charAt((v - 1) * 8 / 15)); }
        return sb.toString();
    }

    /** .v-wave: столбики 3px, gap 2, r2, высота 3 + h·1.4 (h 0..15, до 64 шт.), по центру высоты 26.
     *  Непрослушанное входящее — accent .9; прослушанное — muted .55; проигранная часть (.on) — #4FC3F7. */
    static final class Wave extends View {
        private final int[] w; private float progress; boolean heard;
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        Wave(Context c, JSONArray arr, boolean heard) {
            super(c); this.heard = heard;
            int n = arr == null || arr.length() == 0 ? 40 : Math.min(64, arr.length()); w = new int[n];
            for (int i = 0; i < n; i++) w[i] = arr == null || arr.length() == 0 ? 3 : Math.max(0, Math.min(15, arr.optInt(i, 0)));
        }
        void setProgress(float f) { progress = f; invalidate(); }
        void setHeard() { heard = true; invalidate(); }
        @Override protected void onMeasure(int ws, int hs) {
            float d = getResources().getDisplayMetrics().density;
            int want = Math.round((w.length * 5 - 2) * d);
            setMeasuredDimension(resolveSize(want, ws), Math.round(26 * d));
        }
        @Override protected void onDraw(android.graphics.Canvas cv) {
            float d = getResources().getDisplayMetrics().density, hgt = getHeight();
            int accent = Site.primary(getContext()), muted = Site.text2(getContext());
            for (int i = 0; i < w.length; i++) {
                float x = i * 5 * d; if (x + 3 * d > getWidth()) break;
                boolean on = i / (float) w.length < progress;
                if (on) p.setColor(Site.READ); else if (!heard) p.setColor((accent & 0x00FFFFFF) | 0xE6000000); else p.setColor((muted & 0x00FFFFFF) | 0x8C000000);
                float bh = Math.round(3 + w[i] * 1.4f) * d, top = Math.round((hgt - bh) / 2);
                cv.drawRoundRect(x, top, x + 3 * d, top + bh, 2 * d, 2 * d, p);
            }
        }
    }

    static boolean isHeard(Context c, String id) { return id != null && c.getSharedPreferences("heard", 0).getBoolean(id, false); }
    static void setHeard(Context c, String id) { if (id != null) c.getSharedPreferences("heard", 0).edit().putBoolean(id, true).apply(); }

    static View view(android.app.Activity a, boolean mine, JSONObject f, ExecutorService io) { return view(a, mine, f, io, null); }
    static View view(android.app.Activity a, boolean mine, JSONObject f, ExecutorService io, String id) {
        return "voice".equals(f.optString("vk")) ? voiceView(a, mine, f, io, id) : roundView(a, mine, f, io);
    }

    /** .voice .v-play: 36×40 (кнопка min-height 40), r50%, фон accent, белый play_arrow 24. */
    private static TextView playBtn(android.app.Activity a, String desc) {
        TextView play = Site.icon(a, "play_arrow", 24, 0xFFFFFFFF);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL); g.setColor(Site.primary(a));
        play.setBackground(g);
        play.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES); play.setContentDescription(desc);
        return play;
    }
    private static TextView durView(android.app.Activity a, String s) {
        TextView dur = Site.label(a, s, 12f, Site.text2(a), 400);
        dur.setGravity(Gravity.END | Gravity.CENTER_VERTICAL); dur.setMaxLines(1);
        dur.setFontFeatureSettings("tnum");
        return dur;
    }
    /** .voice: flex, gap 8, min-width 200, padding 2 0: ▶ · волна · длительность (34, справа). */
    private static View voiceView(android.app.Activity a, boolean mine, JSONObject f, ExecutorService io, String id) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(a, 2), 0, Ui.dp(a, 2)); row.setMinimumWidth(Ui.dp(a, 200));
        TextView play = playBtn(a, "Воспроизвести голосовое");
        row.addView(play, new LinearLayout.LayoutParams(Ui.dp(a, 36), Ui.dp(a, 40)));
        Wave wave = new Wave(a, f.optJSONArray("w"), mine || isHeard(a, id));
        LinearLayout.LayoutParams wl = new LinearLayout.LayoutParams(-2, Ui.dp(a, 26)); wl.leftMargin = Ui.dp(a, 8);
        row.addView(wave, wl);
        TextView dur = durView(a, Cache.fmtDur(f.optDouble("d", 0)));
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(Ui.dp(a, 34), -2); dl.leftMargin = Ui.dp(a, 8);
        row.addView(dur, dl);
        play.setOnClickListener(v -> {
            if (playing == play && player != null) { if (player.isPlaying()) { player.pause(); play.setText("play_arrow"); } else { player.start(); play.setText("pause"); } return; }
            play.setText("hourglass_empty");
            io.execute(() -> {
                try {
                    File pf = plain(a, f);
                    a.runOnUiThread(() -> { play.setText("play_arrow"); playVoice(a, pf, play, wave, dur, f.optDouble("d", 0)); if (!mine) { setHeard(a, id); wave.setHeard(); } });
                } catch (Exception e) { a.runOnUiThread(() -> play.setText("play_arrow")); Ui.toast(a, Ui.err(e)); }
            });
        });
        row.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    /** mp3/m4a вложением (.voice.afile): ▶ · имя 14px · длительность · ⤓ 32 (muted). */
    static View audioFile(android.app.Activity a, JSONObject f, ExecutorService io, View.OnClickListener download) {
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(a, 2), 0, Ui.dp(a, 2)); row.setMinimumWidth(Ui.dp(a, 200));
        String n = f.optString("n", "audio");
        TextView play = playBtn(a, "Воспроизвести " + n);
        row.addView(play, new LinearLayout.LayoutParams(Ui.dp(a, 36), Ui.dp(a, 40)));
        TextView name = Site.label(a, n, 14f, Site.col(a, R.color.web_bubble_in_text), 400);
        name.setTextColor(0); // цвет — от пузыря (ставится ниже)
        MsgView.css(MsgView.one(name), 18.9f); name.setTag("af-name");
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-2, -2); nl.leftMargin = Ui.dp(a, 8);
        row.addView(name, nl);
        TextView dur = durView(a, f.optDouble("d", 0) > 0 ? Cache.fmtDur(f.optDouble("d", 0)) : "");
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(Ui.dp(a, 34), -2); dl.leftMargin = Ui.dp(a, 8);
        row.addView(dur, dl);
        TextView d = Site.icon(a, "download", 24, Site.text2(a)); d.setContentDescription("Скачать и расшифровать"); d.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        d.setOnClickListener(download);
        LinearLayout.LayoutParams ddl = new LinearLayout.LayoutParams(Ui.dp(a, 32), Ui.dp(a, 32)); ddl.leftMargin = Ui.dp(a, 8);
        row.addView(d, ddl);
        play.setOnClickListener(v -> {
            if (playing == play && player != null) { if (player.isPlaying()) { player.pause(); play.setText("play_arrow"); } else { player.start(); play.setText("pause"); } return; }
            play.setText("hourglass_empty");
            io.execute(() -> {
                try { File pf = plain(a, f); a.runOnUiThread(() -> { play.setText("play_arrow"); playVoice(a, pf, play, null, dur, f.optDouble("d", 0)); }); }
                catch (Exception e) { a.runOnUiThread(() -> play.setText("play_arrow")); Ui.toast(a, "Не удалось воспроизвести файл"); }
            });
        });
        row.setLayoutParams(new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    /** mp4/webm вложением (.vfile): 260 × 16:9, r12, surface-highest, ▶ 56 по центру, подпись 12px слева снизу, ⤓ 34 справа снизу. */
    static View videoFile(android.app.Activity a, JSONObject f, ExecutorService io, View.OnClickListener download) {
        int w = Ui.dp(a, 260), h = Math.round(w * 9 / 16f);
        FrameLayout box = new FrameLayout(a);
        box.setBackground(Site.round(Site.col(a, R.color.web_color_surface_highest), Ui.dp(a, 12)));
        box.setClipToOutline(true);
        TextView ph = Site.icon(a, "play_arrow", 34, 0xFFFFFFFF); ph.setBackground(Site.round(0x66000000, Ui.dp(a, 28)));
        box.addView(ph, new FrameLayout.LayoutParams(Ui.dp(a, 56), Ui.dp(a, 56), Gravity.CENTER));
        String n = f.optString("n", "video");
        TextView cap = Site.label(a, n + (f.optLong("s", 0) > 0 ? " · " + Ui.size(f.optLong("s", 0)) : ""), 12f, 0xFFFFFFFF, 400);
        MsgView.css(MsgView.one(cap), 16.2f); cap.setShadowLayer(Ui.dp(a, 2), 0, Ui.dp(a, 1), 0xAA000000);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM); cl.leftMargin = Ui.dp(a, 8); cl.rightMargin = Ui.dp(a, 44); cl.bottomMargin = Ui.dp(a, 6);
        box.addView(cap, cl);
        TextView d = Site.icon(a, "download", 24, 0xFFFFFFFF); d.setBackground(Site.round(0x55000000, Ui.dp(a, 17)));
        d.setContentDescription("Скачать и расшифровать"); d.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES); d.setOnClickListener(download);
        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(Ui.dp(a, 34), Ui.dp(a, 34), Gravity.BOTTOM | Gravity.END); dl.rightMargin = Ui.dp(a, 4); dl.bottomMargin = Ui.dp(a, 2);
        box.addView(d, dl);
        box.setContentDescription("Видео " + n);
        box.setOnClickListener(v -> {
            if (box.getTag() != null) return; box.setTag("loading"); ph.setText("progress_activity");
            io.execute(() -> {
                try {
                    File pf = plain(a, f);
                    a.runOnUiThread(() -> {
                        VideoView vv = new VideoView(a); box.setBackgroundColor(0xFF000000);
                        box.removeView(ph); box.removeView(cap); d.setVisibility(View.GONE);
                        box.addView(vv, 0, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
                        android.widget.MediaController mc = new android.widget.MediaController(a); mc.setAnchorView(vv); vv.setMediaController(mc);
                        vv.setVideoPath(pf.getAbsolutePath()); vv.setOnPreparedListener(mp -> vv.start());
                        vv.setOnErrorListener((mp, x, y) -> { Ui.toast(a, "Видео повреждено"); return true; });
                    });
                } catch (Exception e) { a.runOnUiThread(() -> { box.setTag(null); ph.setText("play_arrow"); }); Ui.toast(a, "Не удалось загрузить видео"); }
            });
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h); lp.topMargin = Ui.dp(a, 2); lp.bottomMargin = Ui.dp(a, 4);
        box.setLayoutParams(lp);
        return box;
    }

    /** .round-wrap > .round-v: круг ровно 200, фон surface-highest, ▶ 56 (rgba 0,0,0,.4; 34), длительность снизу 10 (12px, фон .45, r9, padding 1 6). */
    private static View roundView(android.app.Activity a, boolean mine, JSONObject f, ExecutorService io) {
        int size = Ui.dp(a, 200);
        FrameLayout circle = new FrameLayout(a);
        circle.setOutlineProvider(new ViewOutlineProvider() { @Override public void getOutline(View v, Outline o) { o.setOval(0, 0, v.getWidth(), v.getHeight()); } });
        circle.setClipToOutline(true);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable(); bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        bg.setColor(Site.col(a, R.color.web_color_surface_highest)); circle.setBackground(bg);
        VideoView vv = new VideoView(a);
        circle.addView(vv, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
        vv.setVisibility(View.INVISIBLE);
        TextView play = Site.icon(a, "play_arrow", 34, 0xFFFFFFFF);
        play.setBackground(Site.round(0x66000000, Ui.dp(a, 28)));
        circle.addView(play, new FrameLayout.LayoutParams(Ui.dp(a, 56), Ui.dp(a, 56), Gravity.CENTER));
        TextView dur = Site.label(a, Cache.fmtDur(f.optDouble("d", 0)), 12f, 0xFFFFFFFF, 400);
        dur.setBackground(Site.round(0x73000000, Ui.dp(a, 9))); dur.setPadding(Ui.dp(a, 6), Ui.dp(a, 1), Ui.dp(a, 6), Ui.dp(a, 1));
        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL); dl.bottomMargin = Ui.dp(a, 10);
        circle.addView(dur, dl);
        circle.setContentDescription("Видеосообщение " + Cache.fmtDur(f.optDouble("d", 0)) + ", нажмите для звука");
        circle.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        circle.setTag("round");
        TextView capV = Site.lh(Site.label(a, "", 12f, Site.text2(a), 400), 16f); capV.setVisibility(View.GONE);
        circle.setOnClickListener(v -> {
            if (vv.getVisibility() == View.VISIBLE) { if (vv.isPlaying()) { vv.pause(); play.setVisibility(View.VISIBLE); } else { vv.start(); play.setVisibility(View.GONE); } return; }
            stopVoice();
            play.setText("progress_activity"); capV.setVisibility(View.GONE);
            io.execute(() -> {
                try {
                    File pf = plain(a, f);
                    a.runOnUiThread(() -> {
                        vv.setVisibility(View.VISIBLE); play.setVisibility(View.GONE); play.setText("play_arrow");
                        vv.setVideoPath(pf.getAbsolutePath());
                        vv.setOnPreparedListener(mp -> vv.start());
                        vv.setOnCompletionListener(mp -> { play.setVisibility(View.VISIBLE); vv.seekTo(1); });
                        vv.setOnErrorListener((mp, w, e) -> { vv.setVisibility(View.INVISIBLE); play.setVisibility(View.VISIBLE); play.setText("videocam_off"); capV.setText("Видео повреждено"); capV.setVisibility(View.VISIBLE); return true; });
                    });
                } catch (Exception e) { a.runOnUiThread(() -> { play.setText("videocam_off"); capV.setText("Не загрузилось — нажмите, чтобы повторить"); capV.setVisibility(View.VISIBLE); }); }
            });
        });
        // .round-wrap: колонка, выравнивание по своей стороне, gap 4; подпись состояния под кругом
        LinearLayout wrap = new LinearLayout(a); wrap.setOrientation(LinearLayout.VERTICAL); wrap.setGravity(mine ? Gravity.END : Gravity.START);
        wrap.addView(circle);
        LinearLayout.LayoutParams cpl = new LinearLayout.LayoutParams(-2, -2); cpl.topMargin = Ui.dp(a, 4); wrap.addView(capV, cpl);
        wrap.setTag("round");
        return wrap;
    }

    static File plain(Context c, JSONObject f) throws Exception {
        String p = f.getString("p");
        File have = plainFiles.get(p);
        if (have != null && have.exists()) return have;
        File enc = ChatFiles.download(c, p, Api.get(c).freshToken());
        try {
            byte[] ct;
            try (java.io.FileInputStream in = new java.io.FileInputStream(enc)) {
                java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(); byte[] b = new byte[65536]; int r;
                while ((r = in.read(b)) > 0) { bo.write(b, 0, r); if (bo.size() > ChatFiles.MAX + 16) throw new java.io.IOException("Файл слишком большой"); }
                ct = bo.toByteArray();
            }
            byte[] plain = E2eCore.decryptFile(ct, E2eCore.unb64(f.getString("k")), E2eCore.unb64(f.getString("iv")), f.optString("h", null));
            String m = f.optString("m", "");
            String ext = sniffExt(plain, m); // по сигнатуре, как сайт (66eeafa): webm/mkv/mp4
            File out = new File(ChatFiles.cacheDir(c), java.util.UUID.randomUUID() + ext);
            try (java.io.FileOutputStream fo = new java.io.FileOutputStream(out)) { fo.write(plain); }
            plainFiles.put(p, out);
            return out;
        } finally { enc.delete(); }
    }

    private static Wave playingWave; private static TextView playingDur; private static double playingTotal;
    private static final android.os.Handler ph = new android.os.Handler(android.os.Looper.getMainLooper());
    private static final Runnable prog = new Runnable() { @Override public void run() {
        if (player == null) return;
        try { int pos = player.getCurrentPosition(), d = Math.max(1, player.getDuration());
            if (playingWave != null) playingWave.setProgress(pos / (float) d);
            if (playingDur != null) playingDur.setText(Cache.fmtDur(pos / 1000.0));
        } catch (Exception ignored) { }
        ph.postDelayed(this, 100);
    } };
    private static void stopVoice() {
        ph.removeCallbacks(prog);
        if (player != null) { try { player.release(); } catch (Exception ignored) { } player = null; }
        if (playing != null) playing.setText("play_arrow"); playing = null;
        if (playingWave != null) playingWave.setProgress(0); playingWave = null;
        if (playingDur != null) playingDur.setText(Cache.fmtDur(playingTotal)); playingDur = null;
    }
    private static void playVoice(android.app.Activity c, File f, TextView btn, Wave wave, TextView dur, double total) {
        stopVoice();
        try {
            player = new MediaPlayer();
            player.setDataSource(f.getAbsolutePath());
            player.setOnCompletionListener(mp -> stopVoice());
            player.prepare(); player.start();
            playing = btn; btn.setText("pause"); playingWave = wave; playingDur = dur; playingTotal = total;
            ph.post(prog);
        } catch (Exception e) { stopVoice(); Ui.toast(c, "Не удалось воспроизвести: " + Ui.err(e)); }
    }
    private static void showRound(android.app.Activity a, File f) {
        stopVoice();
        int size = Ui.dp(a, 280);
        FrameLayout fr = new FrameLayout(a);
        VideoView vv = new VideoView(a);
        FrameLayout circle = new FrameLayout(a);
        circle.setOutlineProvider(new ViewOutlineProvider() { @Override public void getOutline(View v, Outline o) { o.setOval(0, 0, v.getWidth(), v.getHeight()); } });
        circle.setClipToOutline(true);
        circle.addView(vv, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
        fr.addView(circle, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
        fr.setPadding(0, Ui.dp(a, 16), 0, Ui.dp(a, 8));
        androidx.appcompat.app.AlertDialog d = new com.google.android.material.dialog.MaterialAlertDialogBuilder(a).setView(fr).setPositiveButton("Закрыть", null).create();
        vv.setVideoPath(f.getAbsolutePath());
        vv.setOnPreparedListener(mp -> vv.start());
        vv.setOnClickListener(v -> { if (vv.isPlaying()) vv.pause(); else vv.start(); });
        vv.setOnErrorListener((mp, w, e) -> { Ui.toast(a, "Не удалось воспроизвести видеосообщение"); return true; });
        d.setOnDismissListener(x -> vv.stopPlayback());
        d.show();
    }

    /** Тип медиа по сигнатуре файла; mime из шифротекста — только запасной вариант. */
    static String sniffExt(byte[] b, String mime) {
        if (b != null && b.length >= 12) {
            if ((b[0] & 0xff) == 0x1A && (b[1] & 0xff) == 0x45 && (b[2] & 0xff) == 0xDF && (b[3] & 0xff) == 0xA3) {
                String head = new String(b, 0, Math.min(b.length, 64), java.nio.charset.StandardCharsets.ISO_8859_1);
                return head.contains("matroska") ? ".mkv" : ".webm";
            }
            if (b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') return ".mp4";
            if (b[0] == 'O' && b[1] == 'g' && b[2] == 'g' && b[3] == 'S') return ".ogg";
            if ((b[0] == 'I' && b[1] == 'D' && b[2] == '3') || ((b[0] & 0xff) == 0xFF && (b[1] & 0xE0) == 0xE0)) return ".mp3";
        }
        String m = mime == null ? "" : mime;
        return m.contains("mp4") ? ".mp4" : m.contains("ogg") ? ".ogg" : m.contains("mpeg") ? ".mp3" : ".webm";
    }
}
