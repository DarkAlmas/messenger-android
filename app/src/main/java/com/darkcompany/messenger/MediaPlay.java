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

    /** Волна: 40 столбиков 1..15 (как на сайте), прослушанная часть — ярче. */
    static final class Wave extends View {
        private final int[] w; private final int on, off; private float progress;
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        Wave(Context c, JSONArray arr, int on, int off) {
            super(c); this.on = on; this.off = off;
            int n = arr == null || arr.length() == 0 ? 40 : arr.length(); w = new int[n];
            for (int i = 0; i < n; i++) w[i] = arr == null || arr.length() == 0 ? 2 : Math.max(1, Math.min(15, arr.optInt(i, 1)));
        }
        void setProgress(float f) { progress = f; invalidate(); }
        @Override protected void onDraw(android.graphics.Canvas cv) {
            float bw = getWidth() / (float) w.length, gap = bw * 0.35f, hgt = getHeight();
            for (int i = 0; i < w.length; i++) {
                p.setColor(i / (float) w.length < progress ? on : off);
                float bh = Math.max(Ui.dp(getContext(), 2), hgt * w[i] / 15f), x = i * bw;
                cv.drawRoundRect(x, (hgt - bh) / 2, x + bw - gap, (hgt + bh) / 2, bw, bw, p);
            }
        }
    }

    static View view(android.app.Activity a, boolean mine, JSONObject f, ExecutorService io) {
        return "voice".equals(f.optString("vk")) ? voiceView(a, mine, f, io) : roundView(a, f, io);
    }

    private static View voiceView(android.app.Activity a, boolean mine, JSONObject f, ExecutorService io) {
        int fg = mine ? 0xFFFFFFFF : Site.primary(a);
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dp(a, 4), Ui.dp(a, 4), Ui.dp(a, 2));
        TextView play = Site.icon(a, "play_arrow", 26, mine ? Site.col(a, R.color.site_out) : 0xFFFFFFFF);
        play.setBackground(Site.round(fg, Ui.dp(a, 21)));
        play.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        play.setContentDescription("Воспроизвести голосовое");
        row.addView(play, new LinearLayout.LayoutParams(Ui.dp(a, 42), Ui.dp(a, 42)));
        LinearLayout col = new LinearLayout(a); col.setOrientation(LinearLayout.VERTICAL); col.setPadding(Ui.dp(a, 10), 0, 0, 0);
        Wave wave = new Wave(a, f.optJSONArray("w"), fg, (fg & 0x00FFFFFF) | 0x66000000);
        col.addView(wave, new LinearLayout.LayoutParams(Ui.dp(a, 150), Ui.dp(a, 24)));
        TextView dur = Site.label(a, Cache.fmtDur(f.optDouble("d", 0)), 12, mine ? 0xB3FFFFFF : Site.col(a, R.color.site_inmeta), false);
        col.addView(dur);
        row.addView(col);
        View.OnClickListener go = v -> {
            if (playing == play && player != null) { if (player.isPlaying()) { player.pause(); play.setText("play_arrow"); } else { player.start(); play.setText("pause"); } return; }
            play.setText("hourglass_empty");
            io.execute(() -> {
                try {
                    File pf = plain(a, f);
                    a.runOnUiThread(() -> { play.setText("play_arrow"); playVoice(a, pf, play, wave, dur, f.optDouble("d", 0)); });
                } catch (Exception e) { a.runOnUiThread(() -> play.setText("play_arrow")); Ui.toast(a, Ui.err(e)); }
            });
        };
        play.setOnClickListener(go);
        return row;
    }

    /** Кружок 200dp прямо в чате: тёмный круг, ▶ и длительность; нажатие — расшифровка и воспроизведение в круге. */
    private static View roundView(android.app.Activity a, JSONObject f, ExecutorService io) {
        int size = Ui.dp(a, 200);
        FrameLayout circle = new FrameLayout(a);
        circle.setOutlineProvider(new ViewOutlineProvider() { @Override public void getOutline(View v, Outline o) { o.setOval(0, 0, v.getWidth(), v.getHeight()); } });
        circle.setClipToOutline(true);
        circle.setBackgroundColor(0xFF1B1B21);
        VideoView vv = new VideoView(a);
        circle.addView(vv, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
        vv.setVisibility(View.INVISIBLE);
        TextView play = Site.icon(a, "play_arrow", 40, 0xFFFFFFFF);
        play.setBackground(Site.round(0x66000000, Ui.dp(a, 28)));
        circle.addView(play, new FrameLayout.LayoutParams(Ui.dp(a, 56), Ui.dp(a, 56), Gravity.CENTER));
        TextView dur = Site.label(a, Cache.fmtDur(f.optDouble("d", 0)), 12, 0xFFFFFFFF, false);
        dur.setBackground(Site.round(0x66000000, Ui.dp(a, 10))); dur.setPadding(Ui.dp(a, 8), Ui.dp(a, 2), Ui.dp(a, 8), Ui.dp(a, 2));
        FrameLayout.LayoutParams dl = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL); dl.bottomMargin = Ui.dp(a, 14);
        circle.addView(dur, dl);
        circle.setContentDescription("Видеосообщение " + Cache.fmtDur(f.optDouble("d", 0)));
        circle.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        circle.setTag("round");
        circle.setOnClickListener(v -> {
            if (vv.getVisibility() == View.VISIBLE) { if (vv.isPlaying()) { vv.pause(); play.setVisibility(View.VISIBLE); } else { vv.start(); play.setVisibility(View.GONE); } return; }
            stopVoice();
            play.setText("hourglass_empty");
            io.execute(() -> {
                try {
                    File pf = plain(a, f);
                    a.runOnUiThread(() -> {
                        vv.setVisibility(View.VISIBLE); play.setVisibility(View.GONE); play.setText("play_arrow");
                        vv.setVideoPath(pf.getAbsolutePath());
                        vv.setOnPreparedListener(mp -> vv.start());
                        vv.setOnCompletionListener(mp -> { play.setVisibility(View.VISIBLE); vv.seekTo(1); });
                        vv.setOnErrorListener((mp, w, e) -> { play.setVisibility(View.VISIBLE); Ui.toast(a, "Не удалось воспроизвести видеосообщение"); return true; });
                    });
                } catch (Exception e) { a.runOnUiThread(() -> play.setText("play_arrow")); Ui.toast(a, Ui.err(e)); }
            });
        });
        return circle;
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
