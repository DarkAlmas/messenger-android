package com.darkcompany.messenger;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Внешний вид как на сайте (web/tokens.css, style.css): цвета, значки Material Symbols Rounded, панели. */
final class Site {
    private Site() { }
    private static Typeface icons;
    static Typeface iconFont(Context c) {
        if (icons == null) icons = androidx.core.content.res.ResourcesCompat.getFont(c, R.font.material_symbols);
        return icons;
    }
    static int col(Context c, int res) { return c.getColor(res); }
    static int text(Context c) { return col(c, R.color.site_text); }
    static int text2(Context c) { return col(c, R.color.site_text2); }
    static int primary(Context c) { return Ui.attr(c, androidx.appcompat.R.attr.colorPrimary); }
    static int surface(Context c) { return Ui.attr(c, com.google.android.material.R.attr.colorSurface); }
    static int container(Context c) { return Ui.attr(c, com.google.android.material.R.attr.colorSurfaceContainer); }
    static int primaryContainer(Context c) { return Ui.attr(c, com.google.android.material.R.attr.colorPrimaryContainer); }
    static int onPrimaryContainer(Context c) { return Ui.attr(c, com.google.android.material.R.attr.colorOnPrimaryContainer); }

    /** Значок по имени лигатуры (как <span class="ms">name</span>). */
    static TextView icon(Context c, String name, int sizeDp, int color) {
        TextView t = new TextView(c);
        t.setTypeface(iconFont(c));
        t.setText(name);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, sizeDp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        t.setGravity(Gravity.CENTER);
        t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return t;
    }
    /** Кнопка-значок 44×44 с круглой подсветкой нажатия. */
    static TextView iconButton(Context c, String name, String label, View.OnClickListener l) {
        TextView t = icon(c, name, 24, text2(c));
        t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        t.setContentDescription(label);
        android.util.TypedValue tv = new android.util.TypedValue();
        c.getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true);
        t.setBackgroundResource(tv.resourceId);
        t.setClickable(true); t.setFocusable(true);
        if (l != null) t.setOnClickListener(l);
        t.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(c, 44), Ui.dp(c, 44)));
        return t;
    }
    static GradientDrawable round(int color, float radiusPx) { GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(radiusPx); return g; }

    /** Шапка .bar: высота 56, фон surface, нижняя линия. */
    static LinearLayout bar(Context c) {
        LinearLayout b = new LinearLayout(c);
        b.setOrientation(LinearLayout.HORIZONTAL); b.setGravity(Gravity.CENTER_VERTICAL);
        b.setMinimumHeight(Ui.dp(c, 56));
        b.setPadding(Ui.dp(c, 8), Ui.dp(c, 6), Ui.dp(c, 8), Ui.dp(c, 6));
        android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
                new android.graphics.drawable.ColorDrawable(col(c, R.color.site_divider)), new android.graphics.drawable.ColorDrawable(surface(c))});
        ld.setLayerInset(1, 0, 0, 0, Math.max(1, Ui.dp(c, 1)));
        b.setBackground(ld);
        return b;
    }
    static TextView label(Context c, String s, int sp, int color, boolean bold) {
        TextView t = new TextView(c); t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        return t;
    }

    /** Нижняя панель (#bottomNav): чаты / профиль / настройки, активная — в «пилюле» primary-container. */
    static View bottomNav(android.app.Activity a, int active) {
        LinearLayout nav = new LinearLayout(a);
        nav.setOrientation(LinearLayout.HORIZONTAL); nav.setGravity(Gravity.CENTER_VERTICAL);
        nav.setBackgroundColor(surface(a));
        nav.setElevation(Ui.dp(a, 3));
        String[][] items = {{"chat", "Чаты"}, {"person", "Профиль"}, {"settings", "Настройки"}};
        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            android.widget.FrameLayout cell = new android.widget.FrameLayout(a);
            TextView ic = icon(a, items[i][0], 24, i == active ? onPrimaryContainer(a) : text2(a));
            if (i == active) ic.setBackground(round(primaryContainer(a), Ui.dp(a, 16)));
            cell.addView(ic, new android.widget.FrameLayout.LayoutParams(Ui.dp(a, 56), Ui.dp(a, 32), Gravity.CENTER));
            cell.setContentDescription(items[i][1]);
            cell.setOnClickListener(v -> {
                if (idx == active) return;
                Class<?> k = idx == 0 ? ChatsActivity.class : idx == 1 ? ProfileActivity.class : SettingsActivity.class;
                a.startActivity(new android.content.Intent(a, k).addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            });
            nav.addView(cell, new LinearLayout.LayoutParams(0, Ui.dp(a, 56), 1f));
        }
        return nav;
    }

    /** Маленький спиннер 14dp, как .spin14. */
    static View spinner(Context c) {
        android.widget.ProgressBar p = new android.widget.ProgressBar(c, null, android.R.attr.progressBarStyleSmall);
        p.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(text2(c)));
        p.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(c, 14), Ui.dp(c, 14)));
        return p;
    }

    static final int READ = 0xFF4FC3F7;
    /** Пузырь .msg: радиус 18, «хвост» 4 у последнего в серии, отступы 8/14/6, фон in/out. */
    static LinearLayout bubble(Context c, boolean mine, String who, CharSequence body, View extra, View meta, boolean tail) {
        LinearLayout b = new LinearLayout(c);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setPadding(Ui.dp(c, 14), Ui.dp(c, 8), Ui.dp(c, 14), Ui.dp(c, 6));
        float r = Ui.dp(c, 18), t = tail ? Ui.dp(c, 4) : r;
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadii(mine ? new float[]{r, r, r, r, t, t, r, r} : new float[]{r, r, r, r, r, r, t, t});
        g.setColor(col(c, mine ? R.color.site_out : R.color.site_in));
        b.setBackground(g);
        b.setElevation(mine ? 0 : Ui.dp(c, 1) / 2f);
        int fg = mine ? 0xFFFFFFFF : text(c);
        if (who != null) { TextView w = label(c, who, 13, primary(c), true); b.addView(w); }
        if (extra != null) b.addView(extra, new LinearLayout.LayoutParams(-2, -2));
        if (body != null && body.length() > 0) {
            TextView tv = new TextView(c); tv.setText(body); tv.setTextSize(16); tv.setTextColor(fg);
            tv.setLineSpacing(0, 1.1f); tv.setTextIsSelectable(false);
            android.text.util.Linkify.addLinks(tv, android.text.util.Linkify.WEB_URLS);
            tv.setLinkTextColor(mine ? 0xFFFFFFFF : primary(c));
            b.addView(tv, new LinearLayout.LayoutParams(-2, -2));
        }
        if (extra != null && "round".equals(extra.getTag())) { // кружок — без пузыря, время на полупрозрачной плашке
            b.setBackground(null); b.setElevation(0); b.setPadding(0, 0, 0, 0);
            if (meta != null) { meta.setBackground(round(0x66000000, Ui.dp(c, 10))); meta.setPadding(Ui.dp(c, 8), Ui.dp(c, 2), Ui.dp(c, 8), Ui.dp(c, 2)); }
        }
        if (meta != null) { LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-2, -2); ml.gravity = Gravity.END; ml.topMargin = Ui.dp(c, 2); b.addView(meta, ml); }
        b.setMinimumWidth(Ui.dp(c, 64));
        int side = Ui.dp(c, 56);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.gravity = mine ? Gravity.END : Gravity.START;
        lp.topMargin = Ui.dp(c, 4); lp.bottomMargin = Ui.dp(c, 2);
        if (mine) lp.leftMargin = side; else lp.rightMargin = side;
        b.setLayoutParams(lp);
        return b;
    }
    /** .foot: «🔒 12:00 ✓» — замок, время, галочки (done/done_all/schedule/error). tick: 0 нет, 1 sent, 2 delivered, 3 read, 4 sending, 5 failed. */
    static LinearLayout meta(Context c, boolean mine, boolean locked, String extraPrefix, String time, int tick) {
        LinearLayout f = new LinearLayout(c); f.setGravity(Gravity.CENTER_VERTICAL);
        int mc = mine ? 0xB3FFFFFF : col(c, R.color.site_inmeta);
        if (extraPrefix != null) { TextView e = label(c, extraPrefix + " ", 11, mc, false); f.addView(e); }
        if (locked) { TextView l = label(c, "🔒", 9, mc, false); l.setAlpha(0.8f); l.setPadding(0, 0, Ui.dp(c, 4), 0); f.addView(l); }
        f.addView(label(c, time, 11, mc, false));
        if (tick > 0) {
            String ic = tick == 4 ? "schedule" : tick == 5 ? "error" : tick == 1 ? "done" : "done_all";
            int tc = tick == 3 ? READ : tick == 5 ? col(c, R.color.site_danger) : mc;
            TextView t = icon(c, ic, 16, tc); t.setPadding(Ui.dp(c, 4), 0, 0, 0);
            t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            t.setContentDescription(tick == 4 ? "Отправляется" : tick == 5 ? "Не отправлено — нажмите" : tick == 3 ? "Прочитано" : tick == 2 ? "Доставлено" : "Отправлено");
            f.addView(t);
        }
        return f;
    }
    /** .date-sep: «Сегодня», «Вчера», «15 октября». */
    static View dateSep(Context c, String label) {
        TextView t = label(c, label, 13, text2(c), true);
        t.setBackground(round(container(c), Ui.dp(c, 999)));
        t.setPadding(Ui.dp(c, 12), Ui.dp(c, 3), Ui.dp(c, 12), Ui.dp(c, 3));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.gravity = Gravity.CENTER_HORIZONTAL; lp.topMargin = Ui.dp(c, 10); lp.bottomMargin = Ui.dp(c, 6);
        t.setLayoutParams(lp);
        return t;
    }
    private static final String[] MONTHS = {"января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря"};
    static String dayLabel(long ms, long now) {
        java.util.Calendar d = java.util.Calendar.getInstance(); d.setTimeInMillis(ms);
        java.util.Calendar n = java.util.Calendar.getInstance(); n.setTimeInMillis(now);
        java.util.Calendar y = (java.util.Calendar) n.clone(); y.add(java.util.Calendar.DATE, -1);
        if (same(d, n)) return "Сегодня";
        if (same(d, y)) return "Вчера";
        String s = d.get(java.util.Calendar.DAY_OF_MONTH) + " " + MONTHS[d.get(java.util.Calendar.MONTH)];
        return d.get(java.util.Calendar.YEAR) == n.get(java.util.Calendar.YEAR) ? s : s + " " + d.get(java.util.Calendar.YEAR) + " г.";
    }
    static long dayKey(long ms) { java.util.Calendar d = java.util.Calendar.getInstance(); d.setTimeInMillis(ms); return d.get(java.util.Calendar.YEAR) * 1000L + d.get(java.util.Calendar.DAY_OF_YEAR); }
    private static boolean same(java.util.Calendar a, java.util.Calendar b) { return a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) && a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR); }

    private static final String[] MON_SHORT = {"янв.", "февр.", "мар.", "апр.", "мая", "июн.", "июл.", "авг.", "сент.", "окт.", "нояб.", "дек."};
    /** «в сети» / «был(а) …» — как seenText на сайте. */
    static String seenText(long t, long now) {
        if (t <= 0) return "был(а) недавно";
        long ms = now - t;
        java.util.Calendar d = java.util.Calendar.getInstance(); d.setTimeInMillis(t);
        java.util.Calendar n = java.util.Calendar.getInstance(); n.setTimeInMillis(now);
        String hm = String.format(java.util.Locale.ROOT, "%02d:%02d", d.get(java.util.Calendar.HOUR_OF_DAY), d.get(java.util.Calendar.MINUTE));
        if (ms < 120000) return "в сети";
        if (ms < 3600000) return "был(а) " + (ms / 60000) + " мин. назад";
        java.util.Calendar y = (java.util.Calendar) n.clone(); y.add(java.util.Calendar.DATE, -1);
        if (same(d, n)) return "был(а) в " + hm;
        if (same(d, y)) return "был(а) вчера в " + hm;
        if (d.get(java.util.Calendar.YEAR) == n.get(java.util.Calendar.YEAR)) return "был(а) " + d.get(java.util.Calendar.DAY_OF_MONTH) + " " + MON_SHORT[d.get(java.util.Calendar.MONTH)] + " в " + hm;
        return String.format(java.util.Locale.ROOT, "был(а) %02d.%02d.%d", d.get(java.util.Calendar.DAY_OF_MONTH), d.get(java.util.Calendar.MONTH) + 1, d.get(java.util.Calendar.YEAR));
    }
}
