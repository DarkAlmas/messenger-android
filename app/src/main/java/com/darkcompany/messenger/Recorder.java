package com.darkcompany.messenger;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.video.FileOutputOptions;
import androidx.camera.video.Quality;
import androidx.camera.video.QualitySelector;
import androidx.camera.video.Recording;
import androidx.camera.video.VideoCapture;
import androidx.camera.video.VideoRecordEvent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Голосовые и кружки, как на сайте: удерживать — запись; свайп влево — отмена; свайп вверх — замок
 * (потом «Отправить»); короткое нажатие — переключение 🎤 ⇄ кружок. Кружок — встроенный круглый видоискатель (CameraX). До 60 с.
 */
final class Recorder {
    interface Sink { void send(byte[] data, String mime, String name, JSONObject media); }
    interface Hooks { void start(String mode); void tick(long sec, boolean locked); void end(); androidx.camera.view.PreviewView preview(); }
    static final int MAX_REC = 60;
    private static final long HOLD_MS = 250;
    private final AppCompatActivity a;
    private final TextView btn;
    private final Hooks ui;
    private final Sink sink;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final ActivityResultLauncher<String[]> perm;
    private String mode = "voice";
    private MediaRecorder mr;
    private ProcessCameraProvider camProv;
    private Recording rec;
    private boolean active, locked, holding, sendOnStop;
    private File out;
    private long t0;
    private float x0, y0;
    private final List<Integer> wave = new ArrayList<>();

    Recorder(AppCompatActivity a, TextView btn, Hooks ui, Sink sink) {
        this.a = a; this.btn = btn; this.ui = ui; this.sink = sink;
        perm = a.registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), r -> {
            if (r.containsValue(false)) Ui.toast(a, "voice".equals(mode) ? "Нет доступа к микрофону — разрешите его в настройках" : "Нет доступа к камере или микрофону — разрешите их в настройках");
        });
        btn.setOnTouchListener((v, e) -> { onTouch(e); return true; });
    }

    private final Runnable startHold = () -> { holding = true; begin(); };
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!active) return;
            long s = (System.currentTimeMillis() - t0) / 1000;
            if (mr != null) try { wave.add(mr.getMaxAmplitude()); } catch (Exception ignored) { }
            ui.tick(s, locked);
            if (s >= MAX_REC) { stop(true); return; }
            h.postDelayed(this, 100);
        }
    };

    private void onTouch(MotionEvent e) {
        float dp = a.getResources().getDisplayMetrics().density;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                x0 = e.getRawX(); y0 = e.getRawY(); holding = false;
                h.postDelayed(startHold, HOLD_MS);
                break;
            case MotionEvent.ACTION_MOVE:
                if (!active || locked) break;
                if (e.getRawX() - x0 < -100 * dp) stop(false);
                else if (e.getRawY() - y0 < -80 * dp) { locked = true; ui.tick((System.currentTimeMillis() - t0) / 1000, true); }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                h.removeCallbacks(startHold);
                if (!holding) { if (e.getActionMasked() == MotionEvent.ACTION_UP) toggle(); break; }
                if (active && !locked) stop(e.getActionMasked() == MotionEvent.ACTION_UP);
                break;
        }
    }

    private void toggle() {
        mode = "voice".equals(mode) ? "round" : "voice";
        btn.setText("voice".equals(mode) ? "mic" : "radio_button_checked");
        btn.setContentDescription("voice".equals(mode) ? "Голосовое сообщение" : "Видеосообщение");
    }

    private boolean has(String p) { return a.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED; }

    private void begin() {
        if ("voice".equals(mode)) {
            if (!has(Manifest.permission.RECORD_AUDIO)) { holding = false; perm.launch(new String[]{Manifest.permission.RECORD_AUDIO}); return; }
            startVoice();
        } else {
            if (!has(Manifest.permission.CAMERA) || !has(Manifest.permission.RECORD_AUDIO)) { holding = false; perm.launch(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO}); return; }
            startRound();
        }
    }

    private void started() { active = true; locked = false; sendOnStop = false; wave.clear(); t0 = System.currentTimeMillis(); ui.start(mode); h.post(tick); }

    private void startVoice() {
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
            started();
        } catch (Exception ex) { releaseMr(); holding = false; Ui.toast(a, "Не удалось включить микрофон: " + Ui.err(ex)); }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private void startRound() {
        out = new File(ChatFiles.cacheDir(a), "round_" + System.currentTimeMillis() + ".mp4");
        com.google.common.util.concurrent.ListenableFuture<ProcessCameraProvider> f = ProcessCameraProvider.getInstance(a);
        f.addListener(() -> {
            try {
                if (!holding) return; // кнопку уже отпустили
                camProv = f.get();
                Preview pv = new Preview.Builder().build();
                pv.setSurfaceProvider(ui.preview().getSurfaceProvider());
                androidx.camera.video.Recorder vr = new androidx.camera.video.Recorder.Builder()
                        .setQualitySelector(QualitySelector.from(Quality.SD, androidx.camera.video.FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
                        .setTargetVideoEncodingBitRate(600_000).build();
                VideoCapture<androidx.camera.video.Recorder> vc = VideoCapture.withOutput(vr);
                camProv.unbindAll();
                camProv.bindToLifecycle(a, CameraSelector.DEFAULT_FRONT_CAMERA, pv, vc);
                started();
                rec = vc.getOutput().prepareRecording(a, new FileOutputOptions.Builder(out).build()).withAudioEnabled()
                        .start(androidx.core.content.ContextCompat.getMainExecutor(a), ev -> {
                            if (ev instanceof VideoRecordEvent.Finalize) onRoundDone((VideoRecordEvent.Finalize) ev);
                        });
            } catch (Exception ex) { closeCam(); active = false; ui.end(); Ui.toast(a, "Камера недоступна: " + Ui.err(ex)); }
        }, androidx.core.content.ContextCompat.getMainExecutor(a));
    }

    private void onRoundDone(VideoRecordEvent.Finalize ev) {
        long dur = System.currentTimeMillis() - t0;
        closeCam();
        File f = out; out = null;
        if (f == null) return;
        if (!sendOnStop) { f.delete(); return; }
        if (ev.hasError() && ev.getError() != VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED) { f.delete(); Ui.toast(a, "Не удалось записать видеосообщение"); return; }
        if (dur < 700) { f.delete(); Ui.toast(a, "Слишком короткая запись"); return; }
        deliver(f, "round", "video/mp4", dur);
    }

    /** Кнопка «Отправить» в режиме замка. */
    void sendLocked() { if (active) stop(true); }
    void cancel() { h.removeCallbacks(startHold); holding = false; if (active) stop(false); else closeCam(); }

    private void stop(boolean send) {
        h.removeCallbacks(tick);
        if (!active) return;
        active = false; holding = false;
        long dur = System.currentTimeMillis() - t0;
        ui.end();
        if (!send) Ui.toast(a, "Запись отменена");
        if (rec != null) { sendOnStop = send; Recording r = rec; rec = null; r.stop(); return; } // дальше — onRoundDone
        boolean ok = true;
        try { if (mr != null) mr.stop(); } catch (Exception ex) { ok = false; }
        releaseMr();
        File f = out; out = null;
        if (f == null) return;
        if (!send) { f.delete(); return; }
        if (!ok || dur < 700) { f.delete(); Ui.toast(a, "Слишком короткая запись"); return; }
        deliver(f, "voice", "audio/mp4", dur);
    }

    private void deliver(File f, String vk, String mime, long durMs) {
        try {
            if (f.length() > ChatFiles.MAX) { Ui.toast(a, "Запись больше 25 МБ"); return; }
            byte[] data = read(f);
            JSONObject media = new JSONObject().put("vk", vk).put("d", Math.max(1, Math.min(MAX_REC, Math.round(durMs / 1000.0))));
            if ("voice".equals(vk)) media.put("w", squash(wave, 40));
            sink.send(data, mime, f.getName(), media);
        } catch (Exception ex) { Ui.toast(a, Ui.err(ex)); }
        finally { f.delete(); }
    }

    private void closeCam() { if (camProv != null) { try { camProv.unbindAll(); } catch (Exception ignored) { } camProv = null; } }
    private void releaseMr() { if (mr != null) { try { mr.release(); } catch (Exception ignored) { } mr = null; } }

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
