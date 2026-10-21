package com.darkcompany.messenger;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Сообщение в ленте — буквальный перенос .msg из style.css сайта (c9f363f):
 *  .msg: max-width min(480px, 100% - 56px), min-width 64, padding 8 14 6, margin 4 0 2, r18, хвостик r4 + ::after 10×16;
 *  .foot: flex end, gap 6, 11px, margin-top 2; .ticks.ms 16px; .reacts слева; .reply, .file-row, .img-prev, .voice, .round-v, .vfile. */
final class MsgView {
    private MsgView() { }
    static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }
    static float dpf(Context c, float v) { return v * c.getResources().getDisplayMetrics().density; }

    /** Пузырь .msg (v15: без хвостика, малый угол 6dp у последнего в серии). */
    static final class Bubble extends LinearLayout {
        final boolean mine; boolean tail, plain; int color;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        Bubble(Context c, boolean mine, boolean tail) {
            super(c); this.mine = mine; this.tail = tail;
            setOrientation(VERTICAL); setWillNotDraw(false);
            color = Site.col(c, mine ? R.color.site_out : R.color.site_in);
            setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 6));
            setMinimumWidth(dp(c, 64));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.gravity = mine ? Gravity.END : Gravity.START; lp.topMargin = dp(c, 4); lp.bottomMargin = dp(c, 2);
            setLayoutParams(lp);
        }
        /** .round-msg: без фона и отступов. */
        void setPlain() { plain = true; setPadding(0, 0, 0, 0); setMinimumWidth(0); invalidate(); }
        @Override protected void onMeasure(int ws, int hs) {
            int avail = MeasureSpec.getSize(ws);
            int max = plain ? avail : Math.min(dp(getContext(), 480), avail - dp(getContext(), 56));
            if (MeasureSpec.getMode(ws) == MeasureSpec.UNSPECIFIED) max = dp(getContext(), 332);
            super.onMeasure(MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST), hs);
            // CSS: блок с переносом строк занимает всю доступную ширину (TextView сжался бы до самой длинной строки)
            int w = !plain && multiLine(this) ? max : getMeasuredWidth();
            // .foot — блок на всю ширину пузыря, но его содержимое тоже задаёт ширину (как flex в CSS): второй проход EXACTLY
            View foot = findViewWithTag("foot");
            if (foot != null && foot.getParent() == this) {
                LayoutParams fl = (LayoutParams) foot.getLayoutParams(); int old = fl.width; fl.width = plain ? old : LayoutParams.MATCH_PARENT;
                View auto = foot.findViewWithTag("auto"); if (auto != null) ((LayoutParams) auto.getLayoutParams()).weight = 1f;
                super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), hs); fl.width = old;
                if (auto != null) ((LayoutParams) auto.getLayoutParams()).weight = 0f;
            } else if (w != getMeasuredWidth()) super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), hs);
        }
        private static boolean multiLine(View v) {
            if (v instanceof TextView && "body".equals(v.getTag())) return ((TextView) v).getLineCount() > 1;
            if (v instanceof TextView && ((TextView) v).getLayout() != null && ((TextView) v).getLayout().getLineCount() > 0 && ((TextView) v).getLayout().getEllipsisCount(0) > 0) return true; // .reply с многоточием — блок во всю ширину
            if (v instanceof android.view.ViewGroup) { android.view.ViewGroup g = (android.view.ViewGroup) v; for (int i = 0; i < g.getChildCount(); i++) if (multiLine(g.getChildAt(i))) return true; }
            return false;
        }
        @Override protected void onDraw(Canvas cv) {
            if (plain) return;
            Context c = getContext();
            // v15 (Design Bot): без хвостика — углы 18dp, у последнего пузыря серии малый угол 6dp (исх. — справа снизу, вход. — слева снизу)
            float w = getWidth(), h = getHeight(), r = dpf(c, 18), t = tail ? dpf(c, 6) : r;
            path.reset();
            float[] rad = mine ? new float[]{r, r, r, r, t, t, r, r} : new float[]{r, r, r, r, r, r, t, t};
            path.addRoundRect(new RectF(0, 0, w, h), rad, Path.Direction.CW);
            p.setColor(color);
            cv.drawPath(path, p);
        }
    }

    /** span.body: 16px, lh 21.6 (e2e — курсив, opacity .75; other-dev — 12px muted). */
    static TextView body(Context c, boolean mine, CharSequence s, int kind) {
        TextView tv = new TextView(c); tv.setText(s);
        int fg = Site.col(c, mine ? R.color.web_bubble_out_text : R.color.web_bubble_in_text);
        tv.setTextSize(kind == 2 ? 12 : 16); tv.setTextColor(kind == 2 ? Site.text2(c) : fg);
        css(tv, 21.6f);
        if (kind > 0) { tv.setTypeface(null, android.graphics.Typeface.ITALIC); tv.setAlpha(.75f); }
        tv.setTag("body");
        if (kind == 0) { android.text.util.Linkify.addLinks(tv, android.text.util.Linkify.WEB_URLS); tv.setLinkTextColor(mine ? 0xFFFFFFFF : Site.primary(c)); }
        return tv;
    }

    /** CSS line-height: каждая строка ровно lh, полу-интерлиньяж сверху и снизу (как строчный бокс браузера). */
    static TextView css(TextView tv, float lh) {
        tv.setIncludeFontPadding(false); tv.setMinHeight(0); tv.setMinimumHeight(0);
        android.graphics.Paint.FontMetricsInt fm = tv.getPaint().getFontMetricsInt();
        int px = Math.round(lh * tv.getResources().getDisplayMetrics().density), extra = px - (fm.descent - fm.ascent);
        tv.setLineSpacing(extra, 1f);
        tv.setPadding(tv.getPaddingLeft(), extra / 2, tv.getPaddingRight(), extra - extra / 2);
        return tv;
    }
    static TextView one(TextView tv) { tv.setMaxLines(1); tv.setEllipsize(android.text.TextUtils.TruncateAt.END); return tv; }

    /** small: имя отправителя в группе 13px/700 primary, margin-bottom 2. */
    static TextView sender(Context c, String who) {
        TextView w = Site.label(c, who, 13f, Site.primary(c), 700);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.bottomMargin = dp(c, 2); w.setLayoutParams(lp);
        return w;
    }

    /** .foot: [реакции] [📌] [изм.] 🔒 время [галочки]; gap 6, 11px, цвет bubble-*-meta. tick: 0 нет, 1 done, 2 delivered, 3 read, 4 sending, 5 failed. */
    static LinearLayout foot(Context c, boolean mine, boolean onMedia, String lock, boolean pinned, boolean edited, String time, int tick) {
        LinearLayout f = new LinearLayout(c); f.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        int mc = onMedia ? 0xFFFFFFFF : Site.col(c, mine ? R.color.web_bubble_out_meta : R.color.site_inmeta);
        int gap = dp(c, 6);
        java.util.List<View> items = new java.util.ArrayList<>();
        if (pinned) items.add(Site.icon(c, "push_pin", 11, mc));
        if (edited) items.add(Site.label(c, "изм.", 11f, mc, 400));
        if (lock != null) { TextView l = Site.label(c, lock, 11f, lock.startsWith("⚠") ? (mine ? 0xFFFFE08A : Site.col(c, R.color.web_color_warning)) : mc, 400); items.add(l); }
        items.add(Site.label(c, time, 11f, mc, 400));
        if (tick > 0) {
            String ic = tick == 4 ? "schedule" : tick == 5 ? "error" : tick == 1 ? "done" : "done_all";
            TextView t = Site.icon(c, ic, 16, tick == 3 ? Site.READ : tick == 5 ? Site.col(c, R.color.site_danger) : mc);
            if (tick != 3 && tick != 5 && !onMedia) t.setAlpha(mine ? .75f : 1f);
            t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            t.setContentDescription(tick == 4 ? "Отправляется" : tick == 5 ? "Не отправлено — нажмите" : tick == 3 ? "Прочитано" : tick == 2 ? "Доставлено" : "Отправлено");
            t.setTag("ticks");
            items.add(t);
        }
        for (int i = 0; i < items.size(); i++) {
            View v = items.get(i);
            LinearLayout.LayoutParams lp = v instanceof TextView && "ticks".equals(v.getTag()) ? new LinearLayout.LayoutParams(dp(c, 16), dp(c, 16)) : new LinearLayout.LayoutParams(-2, -2);
            if (i > 0) lp.leftMargin = gap;
            f.addView(v, lp);
        }
        f.setTag("foot");
        return f;
    }

    /** .reacts в начале .foot (margin-right auto): пилюли .react 22px, 13px, padding 0 7, gap 4. */
    static void reacts(Context c, LinearLayout foot, boolean mineBubble, java.util.Map<String, int[]> em, java.util.function.Consumer<String> click) {
        LinearLayout rr = new LinearLayout(c); rr.setGravity(Gravity.CENTER_VERTICAL); rr.setPadding(0, 0, dp(c, 4), 0);
        int pc = Site.primary(c);
        for (java.util.Map.Entry<String, int[]> e : em.entrySet()) {
            final String emo = e.getKey(); boolean my = e.getValue()[1] == 1;
            TextView rv = new TextView(c); rv.setText(emo + (e.getValue()[0] > 1 ? " " + e.getValue()[0] : ""));
            rv.setTextSize(13); rv.setIncludeFontPadding(false); rv.setGravity(Gravity.CENTER);
            rv.setTextColor(Site.col(c, mineBubble ? R.color.web_bubble_out_text : R.color.web_bubble_in_text));
            rv.setPadding(dp(c, 7), 0, dp(c, 7), 0);
            android.graphics.drawable.GradientDrawable rb = Site.round(mineBubble ? 0x2EFFFFFF : (pc & 0x00FFFFFF) | (my ? 0x38000000 : 0x1F000000), dp(c, 11));
            int bc = !my ? 0 : mineBubble ? 0x80FFFFFF : (pc & 0x00FFFFFF) | 0x8C000000;
            rb.setStroke(Math.max(1, dp(c, 1)), bc);
            rv.setBackground(rb);
            rv.setOnClickListener(v -> click.accept(emo));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, dp(c, 22));
            if (rr.getChildCount() > 0) lp.leftMargin = dp(c, 4);
            rr.addView(rv, lp);
        }
        foot.setGravity(Gravity.CENTER_VERTICAL);
        foot.addView(rr, 0, new LinearLayout.LayoutParams(-2, -2));
        View sp = new View(c); sp.setTag("auto"); foot.addView(sp, 1, new LinearLayout.LayoutParams(0, 1, 0f)); // margin-right:auto (вес включается во 2-м проходе)
        if (foot.getChildCount() > 2) ((LinearLayout.LayoutParams) foot.getChildAt(2).getLayoutParams()).leftMargin = dp(c, 6); // flex gap 6 после .reacts
    }

    /** .reply: border-left 2px currentColor / primary, фон color-hover (своё — #ffffff1f), r6, padding 3 8, margin 2 0 4; b 12px/700, текст 14px. */
    static View reply(Context c, boolean mine, String who, String text) {
        final int line = mine ? 0xFFFFFFFF : Site.primary(c), bg = mine ? 0x1FFFFFFF : Site.col(c, R.color.web_color_hover);
        LinearLayout q = new LinearLayout(c) {
            final Paint pp = new Paint(Paint.ANTI_ALIAS_FLAG); final Path cp = new Path();
            { setWillNotDraw(false); }
            @Override protected void onDraw(Canvas cv) {
                float r = dpf(getContext(), 6); cp.reset(); cp.addRoundRect(new RectF(0, 0, getWidth(), getHeight()), r, r, Path.Direction.CW);
                cv.save(); cv.clipPath(cp); pp.setColor(bg); cv.drawRect(0, 0, getWidth(), getHeight(), pp);
                pp.setColor(line); cv.drawRect(0, 0, dpf(getContext(), 2), getHeight(), pp); cv.restore();
            }
        };
        q.setOrientation(LinearLayout.VERTICAL);
        q.setPadding(dp(c, 10), dp(c, 3), dp(c, 8), dp(c, 3));
        int fg = Site.col(c, mine ? R.color.web_bubble_out_text : R.color.web_bubble_in_text);
        TextView b = css(one(Site.label(c, who, 12f, mine ? fg : Site.primary(c), 700)), 16.2f);
        TextView s = css(one(Site.label(c, text, 14f, fg, 400)), 18.9f);
        q.addView(b, new LinearLayout.LayoutParams(-1, -2)); q.addView(s, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(c, 2); lp.bottomMargin = dp(c, 4);
        q.setLayoutParams(lp);
        return q;
    }

    /** .file-row: margin 4 0, padding 6 6 6 8, r12, min-width 200, gap 10; значок description 32, имя 16/500, размер 12, ⤓ 40. */
    static View fileRow(Context c, boolean mine, String name, String size, boolean broken, View.OnClickListener dl) {
        LinearLayout row = new LinearLayout(c); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Site.round(mine ? 0x26FFFFFF : Site.col(c, R.color.web_color_hover), dp(c, 12)));
        row.setPadding(dp(c, 8), dp(c, 6), dp(c, 6), dp(c, 6));
        row.setMinimumWidth(dp(c, 200));
        int fg = Site.col(c, mine ? R.color.web_bubble_out_text : R.color.web_bubble_in_text);
        TextView ic = Site.icon(c, broken ? "broken_image" : "description", 32, broken ? Site.col(c, R.color.site_danger) : mine ? fg : Site.primary(c));
        row.addView(ic, new LinearLayout.LayoutParams(dp(c, 32), dp(c, 32)));
        LinearLayout t = new LinearLayout(c); t.setOrientation(LinearLayout.VERTICAL);
        TextView n = css(one(Site.label(c, name, 16f, fg, 500)), 21.6f);
        TextView z = css(one(Site.label(c, size, 12f, mine ? fg : Site.text2(c), 400)), 16.2f); if (mine) z.setAlpha(.75f);
        t.addView(n); t.addView(z);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f); tl.leftMargin = dp(c, 10);
        row.addView(t, tl);
        if (!broken) {
            TextView d = Site.icon(c, "download", 24, fg); d.setContentDescription("Скачать и расшифровать"); d.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            d.setOnClickListener(dl);
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(c, 40), dp(c, 40)); dlp.leftMargin = dp(c, 10);
            row.addView(d, dlp);
            row.setOnClickListener(dl);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.topMargin = lp.bottomMargin = dp(c, 4);
        row.setLayoutParams(lp);
        return row;
    }

    /** fmtSize() сайта: «245 КБ», «5.0 МБ». */
    static String size(long n) { return n >= 1048576 ? String.format(java.util.Locale.US, "%.1f МБ", n / 1048576.0) : n >= 1024 ? Math.round(n / 1024.0) + " КБ" : n + " Б"; }

    /** .img-prev.offline: серый блок surface-highest r12 с иконкой image 40 (opacity .7). */
    static View offlineImage(Context c, int w, int h) {
        FrameLayout box = new FrameLayout(c);
        box.setBackground(Site.round(Site.col(c, R.color.web_color_surface_highest), dp(c, 12)));
        TextView ic = Site.icon(c, "image", 40, Site.text2(c)); ic.setAlpha(.7f);
        box.addView(ic, new FrameLayout.LayoutParams(dp(c, 40), dp(c, 40), Gravity.CENTER));
        box.setContentDescription("Картинка загрузится, когда появится сеть");
        box.setLayoutParams(new LinearLayout.LayoutParams(w, h));
        return box;
    }

    /** Размер .img-prev по iw/ih: w = min(260, iw), h = clamp(80..340). */
    static int[] imgBox(Context c, org.json.JSONObject f) {
        double iw = f.optDouble("iw", 0), ih = f.optDouble("ih", 0);
        if (iw > 0 && ih > 0) { double w = Math.min(260, iw), h = Math.max(80, Math.min(340, Math.round(w * ih / iw))); return new int[]{dp(c, (float) w), dp(c, (float) h)}; }
        return new int[]{dp(c, 220), dp(c, 160)};
    }

    /** Для .img-only: .foot поверх картинки справа снизу (10/10), padding 2 8, r10, rgba(0,0,0,.5), белый. */
    static View overlayFoot(Context c, View img, LinearLayout foot) {
        FrameLayout fr = new FrameLayout(c);
        fr.addView(img);
        foot.setBackground(Site.round(0x80000000, dp(c, 10))); foot.setPadding(dp(c, 8), dp(c, 2), dp(c, 8), dp(c, 2));
        FrameLayout.LayoutParams fl = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.BOTTOM); fl.rightMargin = dp(c, 6); fl.bottomMargin = dp(c, 6);
        fr.addView(foot, fl);
        return fr;
    }
}
