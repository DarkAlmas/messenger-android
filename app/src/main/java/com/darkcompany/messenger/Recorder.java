package com.darkcompany.messenger;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.MotionEvent;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.google.android.material.button.MaterialButton;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Запись голосовых и кружков, как на сайте: удерживать кнопку — запись; свайп влево — отмена; свайп вверх — замок
 * (потом нажать ещё раз — отправить); короткое нажатие — переключение голос ⇄ кружок. Не дольше 60 с.
 */
final class Recorder {
    interface Sink { void send(byte[] data, String mime, String name, JSONObject media); }
    static final int MAX_REC = 60;
    private static final long HOLD_MS = 250;
    private final AppCompatActivity a;
    private final MaterialButton btn;
    private final Sink sink;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final ActivityResultLauncher<String> perm;
    private final ActivityResultLauncher<android.net.Uri> video;
    private String mode = "voice";
    private MediaRecorder mr;
    private File out, roundFile;
    private long t0;
    private boolean locked, holding;
    private float x0, y0;
    private final List<Integer> wave = new ArrayList<>();

    Recorder(AppCompatActivity a, MaterialButton btn, Sink sink) {
        this.a = a; this.btn = btn; this.sink = sink;
        perm = a.registerForActivityResult(new ActivityResultContracts.RequestPermission(), ok -> {
            if (!ok) Ui.toast(a, "voice".equals(mode) ? "Нет доступа к микрофону — разрешите его в настройках" : "Нет доступа к камере — разрешите её в настройках");
        });
        video = a.registerForActivityResult(new ActivityResultContracts.CaptureVideo(), ok -> onRound(Boolean.TRUE.equals(ok)));
        btn.setOnTouchListener((v, e) -> { onTouch(e); return true; });
    }

    private final Runnable startHold = () -> {
        holding = true;
        if ("voice".equals(mode)) startVoice(); else startRound();
    };
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (mr == null) return;
            long s = (System.currentTimeMillis() - t0) / 1000;
            try { wave.add(mr.getMaxAmplitude()); } catch (Exception ignored) { }
            btn.setText((locked ? "➤ " : "● ") + Cache.fmtDur(s));
            if (s >= MAX_REC) { stop(true); return; }
            h.postDelayed(this, 100);
        }
    };

    private void onTouch(MotionEvent e) {
        float dp = a.getResources().getDisplayMetrics().density;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (mr != null && locked) { stop(true); return; } // замок: повторное нажатие — отправить
                x0 = e.getRawX(); y0 = e.getRawY(); holding = false;
                h.postDelayed(startHold, HOLD_MS);
                break;
            case MotionEvent.ACTION_MOVE:
                if (mr == null || locked) break;
                if (e.getRawX() - x0 < -100 * dp) { stop(false); }
                else if (e.getRawY() - y0 < -80 * dp) { locked = true; Ui.toast(a, "🔒 Запись закреплена. Нажмите ещё раз, чтобы отправить"); }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                h.removeCallbacks(startHold);
                if (!holding) { if (e.getActionMasked() == MotionEvent.ACTION_UP) toggle(); break; }
                if (mr != null && !locked) stop(e.getActionMasked() == MotionEvent.ACTION_UP);
                break;
        }
    }

    private void toggle() {
        mode = "voice".equals(mode) ? "round" : "voice";
        btn.setText("voice".equals(mode) ? "🎤" : "⭕");
        btn.setContentDescription("voice".equals(mode) ? "Голосовое сообщение: удерживайте" : "Видеосообщение: удерживайте");
    }

    private void startVoice() {
        if (a.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { holding = false; perm.launch(Manifest.permission.RECORD_AUDIO); return; }
        try {
            out = new File(ChatFiles.cacheDir(a), "voice_" + System.currentTimeMillis() + ".m4a");
            mr = new MediaRecorder(a);
            mr.setAudioSource(MediaRecorder.AudioSource.MIC);
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mr.setAudioEncodingBitRate(32000);
            mr.setAudioSamplingRate(44100);
            mr.setMaxDuration(MAX_REC * 1000 + 500);
            mr.setOutputFile(out.getAbsolutePath());
            mr.prepare(); mr.start();
            t0 = System.currentTimeMillis(); locked = false; wave.clear();
            h.post(tick);
        } catch (Exception ex) {
            release(); holding = false;
            Ui.toast(a, "Не удалось включить микрофон: " + Ui.err(ex));
        }
    }

    /** Кружок: системная камера (фронтальная), лимит 60 с, качество пониже — до 25 МБ. */
    private void startRound() {
        holding = false;
        if (a.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) { perm.launch(Manifest.permission.CAMERA); return; }
        try {
            roundFile = new File(ChatFiles.cacheDir(a), "round_" + System.currentTimeMillis() + ".mp4");
            android.net.Uri u = FileProvider.getUriForFile(a, a.getPackageName() + ".files", roundFile);
            Intent probe = new Intent(MediaStore.ACTION_VIDEO_CAPTURE);
            if (probe.resolveActivity(a.getPackageManager()) == null) { Ui.toast(a, "На телефоне нет приложения камеры"); return; }
            video.launch(u);
        } catch (Exception ex) { Ui.toast(a, "Камера недоступна: " + Ui.err(ex)); }
    }

    private void onRound(boolean ok) {
        File f = roundFile; roundFile = null;
        if (f == null) return;
        if (!ok || !f.exists() || f.length() == 0) { f.delete(); Ui.toast(a, "Запись отменена"); return; }
        new Thread(() -> {
            try {
                long ms = 0;
                android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
                try { r.setDataSource(f.getAbsolutePath()); String d = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION); ms = d == null ? 0 : Long.parseLong(d); }
                finally { try { r.release(); } catch (Exception ignored) { } }
                if (ms > (MAX_REC + 1) * 1000L) { Ui.toast(a, "Кружок длиннее 60 секунд — запишите короче"); return; }
                if (f.length() > ChatFiles.MAX) { Ui.toast(a, "Запись больше 25 МБ"); return; }
                byte[] data = read(f);
                JSONObject media = new JSONObject().put("vk", "round").put("d", Math.max(1, Math.min(MAX_REC, Math.round(ms / 1000.0))));
                sink.send(data, "video/mp4", f.getName(), media);
            } catch (Exception ex) { Ui.toast(a, Ui.err(ex)); }
            finally { f.delete(); }
        }).start();
    }

    void cancel() { h.removeCallbacks(startHold); if (mr != null) stop(false); }

    private void stop(boolean send) {
        h.removeCallbacks(tick);
        long dur = System.currentTimeMillis() - t0;
        boolean ok = true;
        try { if (mr != null) mr.stop(); } catch (Exception ex) { ok = false; }
        release();
        locked = false; holding = false;
        btn.setText("🎤");
        File f = out; out = null;
        if (f == null) return;
        if (!send) { f.delete(); Ui.toast(a, "Запись отменена"); return; }
        if (!ok || dur < 700) { f.delete(); Ui.toast(a, "Слишком короткая запись"); return; }
        try {
            byte[] data = read(f);
            JSONObject media = new JSONObject().put("vk", "voice").put("d", Math.max(1, Math.min(MAX_REC, Math.round(dur / 1000.0))))
                    .put("w", squash(wave, 40));
            sink.send(data, "audio/mp4", f.getName(), media);
        } catch (Exception ex) { Ui.toast(a, Ui.err(ex)); }
        finally { f.delete(); }
    }

    private void release() { if (mr != null) { try { mr.release(); } catch (Exception ignored) { } mr = null; } }

    /** Волна голосового: n столбиков 1..15, как squash на сайте. */
    static JSONArray squash(List<Integer> w, int n) {
        JSONArray o = new JSONArray();
        if (w.isEmpty()) { for (int i = 0; i < n; i++) o.put(2); return o; }
        int mx = 1; for (int v : w) mx = Math.max(mx, v);
        for (int i = 0; i < n; i++) {
            int s = i * w.size() / n, e = Math.max(s + 1, (i + 1) * w.size() / n), m = 0;
            for (int j = s; j < e && j < w.size(); j++) m = Math.max(m, w.get(j));
            o.put(Math.max(1, Math.round(m / (float) mx * 15)));
        }
        return o;
    }

    private static byte[] read(File f) throws java.io.IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(); byte[] b = new byte[65536]; int r;
            while ((r = in.read(b)) > 0) bo.write(b, 0, r);
            return bo.toByteArray();
        }
    }
}
