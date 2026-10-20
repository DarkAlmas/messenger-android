package com.darkcompany.messenger;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;

import androidx.camera.view.PreviewView;

/** .round-box: круглый видоискатель 240dp (фронтальная камера, зеркально) с кольцом прогресса до 60 с. */
final class RoundBox extends FrameLayout {
    final PreviewView preview;
    private float progress = 0;
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    RoundBox(Context c) {
        super(c);
        setWillNotDraw(false);
        preview = new PreviewView(c);
        preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        preview.setScaleType(PreviewView.ScaleType.FILL_CENTER);
        FrameLayout clip = new FrameLayout(c);
        clip.setOutlineProvider(new ViewOutlineProvider() { @Override public void getOutline(View v, Outline o) { o.setOval(0, 0, v.getWidth(), v.getHeight()); } });
        clip.setClipToOutline(true);
        clip.setBackgroundColor(0xFF000000);
        clip.addView(preview, new LayoutParams(-1, -1));
        int pad = Ui.dp(c, 10);
        LayoutParams lp = new LayoutParams(-1, -1); lp.setMargins(pad, pad, pad, pad);
        addView(clip, lp);
        ring.setStyle(Paint.Style.STROKE); ring.setStrokeWidth(Ui.dp(c, 4)); ring.setStrokeCap(Paint.Cap.ROUND);
        ring.setColor(Site.primary(c));
    }
    void setProgress(float p) { progress = Math.max(0, Math.min(1, p)); invalidate(); }
    @Override protected void dispatchDraw(Canvas cv) {
        super.dispatchDraw(cv);
        float s = Ui.dp(getContext(), 4);
        RectF r = new RectF(s, s, getWidth() - s, getHeight() - s);
        cv.drawArc(r, -90, 360 * progress, false, ring);
    }
}
