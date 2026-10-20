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

    static View view(android.app.Activity a, boolean mine, JSONObject f, ExecutorService io) {
        boolean voice = "voice".equals(f.optString("vk"));
        int fg = Ui.attr(a, mine ? com.google.android.material.R.attr.colorOnPrimary : com.google.android.material.R.attr.colorOnSurface);
        LinearLayout row = new LinearLayout(a);
        row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(a, 2), Ui.dp(a, 4), Ui.dp(a, 6), Ui.dp(a, 4));
        TextView play = Ui.text(a, "▶", 20); play.setTextColor(fg); play.setGravity(Gravity.CENTER);
        play.setContentDescription(voice ? "Воспроизвести голосовое" : "Воспроизвести видеосообщение");
        row.addView(play, new LinearLayout.LayoutParams(Ui.dp(a, 40), Ui.dp(a, 40)));
        TextView label = Ui.text(a, voice ? waveText(f.optJSONArray("w")) + "  " + Cache.fmtDur(f.optDouble("d", 0))
                : "⭕ Видеосообщение " + Cache.fmtDur(f.optDouble("d", 0)), 15);
        label.setTextColor(fg);
        row.addView(label);
        row.setOnClickListener(v -> {
            if (voice && playing == play && player != null) { stopVoice(); return; }
            play.setText("…");
            io.execute(() -> {
                try {
                    File pf = plain(a, f);
                    a.runOnUiThread(() -> { play.setText("▶"); if (voice) playVoice(a, pf, play); else showRound(a, pf); });
                } catch (Exception e) { a.runOnUiThread(() -> play.setText("▶")); Ui.toast(a, Ui.err(e)); }
            });
        });
        return row;
    }

    private static File plain(Context c, JSONObject f) throws Exception {
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
            String ext = m.contains("mp4") ? ".mp4" : m.contains("ogg") ? ".ogg" : m.contains("mpeg") ? ".mp3" : ".webm";
            File out = new File(ChatFiles.cacheDir(c), java.util.UUID.randomUUID() + ext);
            try (java.io.FileOutputStream fo = new java.io.FileOutputStream(out)) { fo.write(plain); }
            plainFiles.put(p, out);
            return out;
        } finally { enc.delete(); }
    }

    private static void stopVoice() {
        if (player != null) { try { player.release(); } catch (Exception ignored) { } player = null; }
        if (playing != null) playing.setText("▶"); playing = null;
    }
    private static void playVoice(android.app.Activity c, File f, TextView btn) {
        stopVoice();
        try {
            player = new MediaPlayer();
            player.setDataSource(f.getAbsolutePath());
            player.setOnCompletionListener(mp -> stopVoice());
            player.prepare(); player.start();
            playing = btn; btn.setText("⏸");
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
}
