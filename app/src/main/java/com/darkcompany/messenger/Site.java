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
    // Все цвета — из web_tokens.xml (сгенерирован из web/tokens.css)
    static int primary(Context c) { return col(c, R.color.web_color_primary); }
    static int surface(Context c) { return col(c, R.color.web_color_surface); }
    static int container(Context c) { return col(c, R.color.web_color_surface_container); }
    static int high(Context c) { return col(c, R.color.web_color_surface_high); }
    static int primaryContainer(Context c) { return col(c, R.color.web_color_primary_container); }
    static int onPrimaryContainer(Context c) { return col(c, R.color.web_color_on_primary_container); }
    static int divider(Context c) { return col(c, R.color.web_color_divider); }
    /** line-height в CSS-пикселях (= dp). */
    /** line-height как в CSS: межстрочный интервал + высота строки не меньше lh, глиф по центру (half-leading) — и для одной строки. */
    static TextView lh(TextView t, float cssPx) {
        int px = Math.round(cssPx * t.getResources().getDisplayMetrics().density);
        t.setLineHeight(px); t.setIncludeFontPadding(false);
        t.setMinHeight(px); t.setGravity((t.getGravity() & Gravity.HORIZONTAL_GRAVITY_MASK) | Gravity.CENTER_VERTICAL);
        return t;
    }
    /** font-weight 500/600 → sans-serif-medium, 700 → bold. */
    static TextView weight(TextView t, int w) { t.setTypeface(w >= 700 ? Typeface.DEFAULT_BOLD : w >= 500 ? Typeface.create("sans-serif-medium", Typeface.NORMAL) : Typeface.DEFAULT); return t; }

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
        t.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(c, 40), Ui.dp(c, 40)));
        return t;
    }
    static GradientDrawable round(int color, float radiusPx) { GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(radiusPx); return g; }

    /** Шапка .bar: высота 56, фон surface, нижняя линия. */
    static LinearLayout bar(Context c) {
        LinearLayout b = new LinearLayout(c);
        b.setOrientation(LinearLayout.HORIZONTAL); b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(Ui.dp(c, 12), Ui.dp(c, 8), Ui.dp(c, 12), Ui.dp(c, 8) + Math.max(1, Ui.dp(c, 1)));
        android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
                new android.graphics.drawable.ColorDrawable(col(c, R.color.site_divider)), new android.graphics.drawable.ColorDrawable(surface(c))});
        ld.setLayerInset(1, 0, 0, 0, Math.max(1, Ui.dp(c, 1)));
        b.setBackground(ld);
        return b;
    }
    static TextView label(Context c, String s, int sp, int color, boolean bold) { return label(c, s, (float) sp, color, bold ? 700 : 400); }
    /** Текст как в CSS: размер px=sp, line-height 1.35 (var(--line-height)), вес 400/500/600/700. */
    static TextView label(Context c, String s, float sp, int color, int w) {
        TextView t = new TextView(c); t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        weight(t, w); lh(t, sp * 1.35f);
        return t;
    }

    /** Нижняя панель (#bottomNav): чаты / профиль / настройки, активная — в «пилюле» primary-container. */
    /** #bottomNav: высота 56, фон surface, линия сверху (--shadow-bar); пункты чаты / профиль / настройки (порядок сайта);
     *  .pill 56×32 r16, активная — primary-container, значок on-primary-container; неактивные — text-secondary. active: 0 чаты, 1 профиль, 2 настройки. */
    static View bottomNav(android.app.Activity a, int active) {
        LinearLayout nav = new LinearLayout(a);
        nav.setOrientation(LinearLayout.HORIZONTAL); nav.setGravity(Gravity.CENTER_VERTICAL);
        android.graphics.drawable.LayerDrawable ld = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{
                new android.graphics.drawable.ColorDrawable(divider(a)), new android.graphics.drawable.ColorDrawable(surface(a))});
        ld.setLayerInset(1, 0, Math.max(1, Ui.dp(a, 1)), 0, 0);
        nav.setBackground(ld);
        String[][] items = {{"chat", "Чаты"}, {"person", "Профиль"}, {"settings", "Настройки"}};
        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            android.widget.FrameLayout cell = new android.widget.FrameLayout(a);
            TextView ic = icon(a, items[i][0], 24, i == active ? onPrimaryContainer(a) : text2(a));
            ic.setGravity(Gravity.CENTER);
            if (i == active) ic.setBackground(round(primaryContainer(a), Ui.dp(a, 16)));
            cell.addView(ic, new android.widget.FrameLayout.LayoutParams(Ui.dp(a, 56), Ui.dp(a, 32), Gravity.CENTER));
            cell.setContentDescription(items[i][1]);
            cell.setOnClickListener(v -> {
                if (idx == active) return;
                Class<?> k = idx == 0 ? ChatsActivity.class : idx == 1 ? ProfileActivity.class : SettingsActivity.class;
                a.startActivity(new android.content.Intent(a, k).addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
                a.overridePendingTransition(0, 0);
            });
            nav.addView(cell, new LinearLayout.LayoutParams(0, -1, 1f));
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
    /** .msg: padding 8 14 6, r18, «хвост» 4 (in — нижний левый, mine — нижний правый), max-width = ширина − 56, отступ между сообщениями 6;
     *  .body 16px/21.6px, .foot 11px/14.85px. */
    static LinearLayout bubble(Context c, boolean mine, String who, CharSequence body, View extra, View meta, boolean tail) {
        LinearLayout b = new LinearLayout(c);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setPadding(Ui.dp(c, 14), Ui.dp(c, 8), Ui.dp(c, 14), Ui.dp(c, 6));
        float r = Ui.dp(c, 18), t = tail ? Ui.dp(c, 4) : r;
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadii(mine ? new float[]{r, r, r, r, t, t, r, r} : new float[]{r, r, r, r, r, r, t, t});
        g.setColor(col(c, mine ? R.color.site_out : R.color.site_in));
        b.setBackground(g);
        int fg = mine ? col(c, R.color.web_bubble_out_text) : col(c, R.color.web_bubble_in_text);
        if (who != null) { TextView w = label(c, who, 13f, primary(c), 700); b.addView(w); }
        if (extra != null) b.addView(extra, new LinearLayout.LayoutParams(-2, -2));
        if (body != null && body.length() > 0) {
            TextView tv = new TextView(c); tv.setText(body); tv.setTextSize(16); tv.setTextColor(fg);
            lh(tv, 21.6f); tv.setTextIsSelectable(false); tv.setTag("body");
            android.text.util.Linkify.addLinks(tv, android.text.util.Linkify.WEB_URLS);
            tv.setLinkTextColor(mine ? 0xFFFFFFFF : primary(c));
            b.addView(tv, new LinearLayout.LayoutParams(-2, -2));
        }
        if (extra != null && "round".equals(extra.getTag())) { // кружок — без пузыря, время на полупрозрачной плашке
            b.setBackground(null); b.setPadding(0, 0, 0, 0);
            if (meta != null) { meta.setBackground(round(0x66000000, Ui.dp(c, 10))); meta.setPadding(Ui.dp(c, 8), Ui.dp(c, 2), Ui.dp(c, 8), Ui.dp(c, 2)); }
        }
        if (meta != null) { LinearLayout.LayoutParams ml = new LinearLayout.LayoutParams(-2, -2); ml.gravity = Gravity.END; ml.topMargin = Ui.dp(c, 3); b.addView(meta, ml); }
        b.setMinimumWidth(Ui.dp(c, 64));
        int side = Ui.dp(c, 56);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.gravity = mine ? Gravity.END : Gravity.START;
        lp.topMargin = Ui.dp(c, 6);
        if (mine) lp.leftMargin = side; else lp.rightMargin = side;
        b.setLayoutParams(lp);
        return b;
    }
    /** .foot: «🔒 12:00 ✓» — замок, время, галочки (done/done_all/schedule/error). tick: 0 нет, 1 sent, 2 delivered, 3 read, 4 sending, 5 failed. */
    /** .foot: «🔒 12:00 ✓» 11px; .ticks — Material Symbols 16px, margin-left 3. tick: 0 нет, 1 sent, 2 delivered, 3 read, 4 sending, 5 failed. */
    static LinearLayout meta(Context c, boolean mine, boolean locked, String extraPrefix, String time, int tick) {
        LinearLayout f = new LinearLayout(c); f.setGravity(Gravity.CENTER_VERTICAL);
        int mc = mine ? col(c, R.color.web_bubble_out_meta) : col(c, R.color.site_inmeta);
        if (extraPrefix != null) f.addView(label(c, extraPrefix + " ", 11f, mc, 400));
        if (locked) { TextView l = label(c, "🔒", 11f, mc, 400); l.setPadding(0, 0, Ui.dp(c, 5), 0); f.addView(l); }
        f.addView(label(c, time, 11f, mc, 400));
        if (tick > 0) {
            String ic = tick == 4 ? "schedule" : tick == 5 ? "error" : tick == 1 ? "done" : "done_all";
            int tc = tick == 3 ? READ : tick == 5 ? col(c, R.color.site_danger) : mc;
            TextView t = icon(c, ic, 16, tc); t.setPadding(Ui.dp(c, 3), 0, 0, 0);
            t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
            t.setContentDescription(tick == 4 ? "Отправляется" : tick == 5 ? "Не отправлено — нажмите" : tick == 3 ? "Прочитано" : tick == 2 ? "Доставлено" : "Отправлено");
            f.addView(t);
        }
        return f;
    }
    /** .date-sep: «Сегодня», «Вчера», «15 октября». */
    /** .date-sep: 13px/500, фон surface-container, r999, padding 3 12, тень 0 1px 2px; отступы 10 сверху и снизу. */
    static View dateSep(Context c, String label) {
        TextView t = label(c, label, 13f, text2(c), 500);
        // .date-sep: color-mix(surface-container 85%, transparent), r999, padding 3 12, margin 10 0 6
        t.setBackground(round((container(c) & 0x00FFFFFF) | 0xD9000000, Ui.dp(c, 999)));
        t.setPadding(Ui.dp(c, 12), Ui.dp(c, 3), Ui.dp(c, 12), Ui.dp(c, 3));
        t.setMinHeight(Math.round(23.55f * c.getResources().getDisplayMetrics().density));
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

    // ---------- .page / .settings-group / .settings-row (style.css 249–281) ----------
    static int page(Context c) { return col(c, R.color.site_page); }
    static int danger(Context c) { return col(c, R.color.site_danger); }

    /** header.bar страницы: кнопка «назад» 40×40, заголовок #pageTitle 15px/700, справа необязательная кнопка (✎). Высота 56 + линия 1. */
    static LinearLayout pageTitle(android.app.Activity c, String title, String rightIcon, String rightLabel, View.OnClickListener l) {
        LinearLayout b = bar(c);
        TextView back = iconButton(c, "arrow_back", "Назад", v -> {
            c.startActivity(new android.content.Intent(c, ChatsActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            c.overridePendingTransition(0, 0);
        });
        b.addView(back);
        TextView t = label(c, title, 15f, text(c), 700);
        t.setPadding(Ui.dp(c, 8), 0, 0, 0);
        b.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        if (rightIcon != null) b.addView(iconButton(c, rightIcon, rightLabel, l));
        else { View sp = new View(c); b.addView(sp, new LinearLayout.LayoutParams(0, Ui.dp(c, 40))); }
        return b;
    }

    /** .settings-group: фон surface-container, r16, margin-bottom 12; .settings-group__title внутри: 14px/500 primary, padding 16 16 8. */
    static LinearLayout group(Context c, LinearLayout parent, String title) {
        LinearLayout g = new LinearLayout(c);
        g.setOrientation(LinearLayout.VERTICAL);
        g.setBackground(round(container(c), Ui.dp(c, 16)));
        g.setClipToOutline(true);
        if (title != null) {
            TextView t = label(c, title, 14f, primary(c), 500);
            lh(t, 18.9f); t.setPadding(Ui.dp(c, 16), Ui.dp(c, 16), Ui.dp(c, 16), Ui.dp(c, 8));
            t.setMinHeight(Math.round(42.9f * c.getResources().getDisplayMetrics().density));
            t.setTag("title");
            g.addView(t);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = Ui.dp(c, 12);
        parent.addView(g, lp);
        return g;
    }

    /** .settings-row: min-height 56, padding 0 16, gap 16; значок 24 text-secondary; подпись 16px (small 13px); значение 14px справа.
     *  Между строками — линия 1px от x=56 (.settings-row + .settings-row::before). */
    static LinearLayout row(Context c, LinearLayout group, String icon, String label, String sub, String value, View.OnClickListener l) {
        android.widget.FrameLayout holder = null;
        boolean afterRow = group.getChildCount() > 0 && !"title".equals(group.getChildAt(group.getChildCount() - 1).getTag());
        LinearLayout r = new LinearLayout(c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(Ui.dp(c, 56));
        r.setPadding(Ui.dp(c, 16), 0, Ui.dp(c, 16), 0);
        boolean danger = "logout".equals(icon) && label.startsWith("Выйти");
        TextView ic = icon(c, icon, 24, danger ? danger(c) : text2(c));
        r.addView(ic, new LinearLayout.LayoutParams(Ui.dp(c, 24), Ui.dp(c, 24)));
        LinearLayout tc = new LinearLayout(c); tc.setOrientation(LinearLayout.VERTICAL);
        TextView lt = label(c, label, 16f, danger ? danger(c) : text(c), 400);
        lt.setTag("label");
        tc.addView(lt);
        if (sub != null) { TextView st = label(c, sub, 13f, text2(c), 400); st.setTag("sub"); tc.addView(st); }
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1f); tl.leftMargin = Ui.dp(c, 16);
        r.addView(tc, tl);
        if (value != null) {
            TextView v = label(c, value, 14f, text2(c), 400);
            v.setTag("value"); v.setSingleLine(true); v.setEllipsize(android.text.TextUtils.TruncateAt.END);
            v.setMaxWidth(Ui.dp(c, 175)); v.setPadding(Ui.dp(c, 16), 0, 0, 0);
            r.addView(v);
        }
        if (l != null) {
            r.setOnClickListener(l);
            android.util.TypedValue tv = new android.util.TypedValue();
            c.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
            r.setForeground(c.getDrawable(tv.resourceId));
        }
        if (afterRow) {
            View d = new View(c); d.setBackgroundColor(divider(c));
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, Math.max(1, Ui.dp(c, 1)));
            dl.leftMargin = Ui.dp(c, 56);
            group.addView(d, dl);
            r.setMinimumHeight(Ui.dp(c, 56) - Math.max(1, Ui.dp(c, 1)));
        }
        group.addView(r);
        return r;
    }

    static void setValue(View row, String v) { TextView t = row.findViewWithTag("value"); if (t != null) t.setText(v); }
    static void setSub(View row, String v) { TextView t = row.findViewWithTag("sub"); if (t != null) t.setText(v); }

    /** .seg: сегменты-пилюли, активный — primary-container. */
    static LinearLayout seg(Context c, String[] labels, int active, java.util.function.IntConsumer pick) {
        LinearLayout s = new LinearLayout(c);
        s.setPadding(Ui.dp(c, 16), Ui.dp(c, 4), Ui.dp(c, 16), Ui.dp(c, 12));
        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            TextView b = label(c, labels[i], 14f, i == active ? onPrimaryContainer(c) : text(c), 500);
            b.setGravity(Gravity.CENTER);
            b.setBackground(round(i == active ? primaryContainer(c) : high(c), Ui.dp(c, 18)));
            b.setOnClickListener(v -> pick.accept(idx));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, Ui.dp(c, 36), 1f);
            lp.setMargins(Ui.dp(c, 3), 0, Ui.dp(c, 3), 0);
            s.addView(b, lp);
        }
        return s;
    }

    /** .hint.small.pad: 12px text-secondary, padding 0 16. */
    static TextView hint(Context c, String s) {
        TextView t = label(c, s, 12f, text2(c), 400);
        t.setPadding(Ui.dp(c, 16), 0, Ui.dp(c, 16), Ui.dp(c, 12));
        return t;
    }

    /** Переключатель .switch 52×32: дорожка r16 (primary / surface-highest), бегунок 24 (белый / text-secondary). */
    static android.widget.CompoundButton toggle(Context c, boolean on) {
        android.widget.CheckBox cb = new android.widget.CheckBox(c) {
            @Override protected void onDraw(android.graphics.Canvas k) {
                float d = getResources().getDisplayMetrics().density, w = getWidth(), h = getHeight();
                android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                p.setColor(isChecked() ? primary(c) : col(c, R.color.web_color_surface_highest));
                k.drawRoundRect(0, 0, w, h, h / 2, h / 2, p);
                p.setColor(isChecked() ? 0xFFFFFFFF : text2(c));
                float r = 12 * d, cx = isChecked() ? w - 4 * d - r : 4 * d + r;
                k.drawCircle(cx, h / 2, r, p);
            }
        };
        cb.setButtonDrawable(null); cb.setBackground(null); cb.setChecked(on);
        cb.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(c, 52), Ui.dp(c, 32)));
        return cb;
    }

    /** Каркас .page: шапка, .page-body (фон surface-lowest, padding 12) с прокруткой, #bottomNav 56. */
    static LinearLayout scaffold(androidx.appcompat.app.AppCompatActivity a, View title, int nav) {
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(surface(a));
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));
        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        sv.setBackgroundColor(page(a));
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout body = new LinearLayout(a);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(Ui.dp(a, 12), Ui.dp(a, 12), Ui.dp(a, 12), Ui.dp(a, 12));
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(bottomNav(a, nav), new LinearLayout.LayoutParams(-1, Ui.dp(a, 56)));
        a.setContentView(root);
        Ui.fitSystemBars(a, root);
        a.getWindow().setStatusBarColor(surface(a));
        a.getWindow().setNavigationBarColor(surface(a));
        return body;
    }

    /** Нижний лист (dialog.sheet сайта): скругление 16 сверху, заголовок 17px/700, содержимое. */
    static com.google.android.material.bottomsheet.BottomSheetDialog sheet(Context c, String title, View... content) {
        com.google.android.material.bottomsheet.BottomSheetDialog d = new com.google.android.material.bottomsheet.BottomSheetDialog(c);
        LinearLayout col = new LinearLayout(c); col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(Ui.dp(c, 16), Ui.dp(c, 8), Ui.dp(c, 16), Ui.dp(c, 24));
        LinearLayout head = new LinearLayout(c); head.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = label(c, title, 17f, text(c), 700);
        head.addView(t, new LinearLayout.LayoutParams(0, -2, 1f));
        head.addView(iconButton(c, "close", "Закрыть", v -> d.dismiss()));
        col.addView(head);
        for (View v : content) { LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = Ui.dp(c, 8); col.addView(v, lp); }
        android.widget.ScrollView sv = new android.widget.ScrollView(c); sv.addView(col);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(surface(c));
        float r = Ui.dp(c, 16); bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        sv.setBackground(bg);
        d.setContentView(sv);
        d.setOnShowListener(x -> { View p = d.findViewById(com.google.android.material.R.id.design_bottom_sheet); if (p != null) p.setBackground(null); });
        return d;
    }
    /** kvRow сайта: подпись 13px серым над значением 15px. */
    static View kv(Context c, String k, String v) {
        LinearLayout l = new LinearLayout(c); l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(0, Ui.dp(c, 6), 0, Ui.dp(c, 6));
        l.addView(label(c, k, 13f, text2(c), 400));
        TextView t = label(c, v, 15f, text(c), 400); t.setTextIsSelectable(true); l.addView(t);
        return l;
    }

    /** input сайта: высота 46, фон surface-container, рамка 1px divider, r14, padding 11 12, 16px. */
    static android.widget.EditText input(Context c, String hint) {
        android.widget.EditText e = new android.widget.EditText(c);
        e.setHint(hint); e.setSingleLine(true); e.setTextSize(16);
        e.setTextColor(text(c)); e.setHintTextColor(col(c, R.color.web_color_gray));
        GradientDrawable g = round(container(c), Ui.dp(c, 14));
        g.setStroke(Math.max(1, Ui.dp(c, 1)), divider(c));
        e.setBackground(g);
        e.setPadding(Ui.dp(c, 12), Ui.dp(c, 11), Ui.dp(c, 12), Ui.dp(c, 11));
        e.setMinHeight(Ui.dp(c, 46));
        return e;
    }
    /** label сайта над полем: 13px text-secondary, отступ сверху 14, снизу 6. */
    static TextView caption(Context c, String s) { TextView t = label(c, s, 13f, text2(c), 400); t.setPadding(0, Ui.dp(c, 14), 0, Ui.dp(c, 6)); return t; }
    /** button сайта: высота 46, r26, фон primary, белый 16px/500. */
    static TextView button(Context c, String s) {
        TextView b = label(c, s, 16f, col(c, R.color.web_color_on_primary), 500);
        b.setGravity(Gravity.CENTER); b.setBackground(round(primary(c), Ui.dp(c, 26)));
        b.setMinHeight(Ui.dp(c, 46)); b.setPadding(Ui.dp(c, 16), Ui.dp(c, 10), Ui.dp(c, 16), Ui.dp(c, 10));
        b.setClickable(true); b.setFocusable(true);
        return b;
    }
    /** button.link: текст primary 15px/500, без фона, высота 44. */
    static TextView link(Context c, String s) {
        TextView b = label(c, s, 15f, primary(c), 500);
        b.setGravity(Gravity.CENTER_VERTICAL); b.setMinHeight(Ui.dp(c, 44)); b.setClickable(true); b.setFocusable(true);
        return b;
    }

    /** Пункт #ctxMenu: значок (Material Symbols или эмодзи), текст, красный для danger; null — разделитель .sep. */
    static final class Item {
        final String icon, text; final boolean danger; final Runnable run;
        Item(String icon, String text, boolean danger, Runnable run) { this.icon = icon; this.text = text; this.danger = danger; this.run = run; }
    }
    /** #ctxMenu: min-width 200, фон surface-high (тёмная) / surface-lowest (светлая), r12, тень, padding 4 0;
     *  кнопка: padding 10 16, gap 14, 15px; значок 20 серым. Открывается у точки нажатия (x, y — экранные координаты). */
    static android.widget.PopupWindow menu(View anchor, float x, float y, java.util.List<Item> items) {
        Context c = anchor.getContext();
        LinearLayout col = new LinearLayout(c); col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(0, Ui.dp(c, 4), 0, Ui.dp(c, 4));
        boolean dark = (c.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        col.setBackground(round(dark ? high(c) : col(c, R.color.web_color_surface_lowest), Ui.dp(c, 12)));
        col.setElevation(Ui.dp(c, 12));
        col.setMinimumWidth(Ui.dp(c, 200));
        final android.widget.PopupWindow pw = new android.widget.PopupWindow(col, -2, -2, true);
        for (Item it : items) {
            if (it == null) { View sep = new View(c); sep.setBackgroundColor(divider(c)); LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, Math.max(1, Ui.dp(c, 1))); sl.setMargins(Ui.dp(c, 6), Ui.dp(c, 4), Ui.dp(c, 6), Ui.dp(c, 4)); col.addView(sep, sl); continue; }
            LinearLayout b = new LinearLayout(c); b.setGravity(Gravity.CENTER_VERTICAL);
            b.setPadding(Ui.dp(c, 16), Ui.dp(c, 10), Ui.dp(c, 16), Ui.dp(c, 10));
            boolean ms = it.icon.matches("[a-z_]+");
            TextView ic = ms ? icon(c, it.icon, 20, it.danger ? danger(c) : text2(c)) : label(c, it.icon, 16f, text(c), 400);
            ic.setGravity(Gravity.CENTER);
            b.addView(ic, new LinearLayout.LayoutParams(Ui.dp(c, 20), -2));
            TextView t = label(c, it.text, 15f, it.danger ? danger(c) : text(c), 400);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2); tl.leftMargin = Ui.dp(c, 14);
            b.addView(t, tl);
            android.util.TypedValue tv = new android.util.TypedValue();
            c.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
            b.setBackgroundResource(tv.resourceId);
            b.setOnClickListener(v -> { pw.dismiss(); it.run.run(); });
            col.addView(b, new LinearLayout.LayoutParams(-1, -2));
        }
        pw.setElevation(Ui.dp(c, 12));
        pw.setOutsideTouchable(true);
        col.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
        int w = col.getMeasuredWidth(), h = col.getMeasuredHeight(), m = Ui.dp(c, 6);
        int px = (int) Math.max(m, Math.min(x, dm.widthPixels - w - m)), py = (int) Math.max(m, Math.min(y, dm.heightPixels - h - m));
        pw.showAtLocation(anchor.getRootView(), Gravity.TOP | Gravity.START, px, py);
        return pw;
    }
    /** Последняя точка касания (для меню у пальца, как openMenu на сайте). */
    static final float[] lastTouch = {0, 0};
    static void trackTouch(View v) {
        v.setOnTouchListener((x, e) -> { if (e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) { lastTouch[0] = e.getRawX(); lastTouch[1] = e.getRawY(); } return false; });
    }
    static String plural(int n, String a, String b, String c) { int m = n % 10, h = n % 100; return m == 1 && h != 11 ? a : m >= 2 && m <= 4 && (h < 12 || h > 14) ? b : c; }

    /** .or-sep сайта: линия — «или» 13px text-secondary — линия, gap 10, margin 12 0. */
    static LinearLayout orSep(Context c) {
        LinearLayout r = new LinearLayout(c); r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL);
        for (int i = 0; i < 3; i++) {
            if (i == 1) { TextView t = label(c, "или", 13f, text2(c), 400); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2); lp.leftMargin = lp.rightMargin = Ui.dp(c, 10); r.addView(t, lp); continue; }
            View v = new View(c); v.setBackgroundColor(divider(c)); r.addView(v, new LinearLayout.LayoutParams(0, Math.max(1, Ui.dp(c, 1)), 1f));
        }
        return r;
    }
    static boolean night(Context c) { return (c.getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES; }
    /** .gsi сайта (кнопка Google): высота 40, r20, светлая — фон #fff, рамка #747775, текст #1f1f1f; тёмная — #131314 / #8e918f / #e3e3e3;
     *  значок G 18 + «Войти через Google» 14px/500, gap 10. */
    static TextView googleButton(Context c) {
        boolean dk = night(c);
        TextView b = label(c, "Войти через Google", 14f, dk ? 0xFFE3E3E3 : 0xFF1F1F1F, 500);
        GradientDrawable g = new GradientDrawable(); g.setColor(dk ? 0xFF131314 : 0xFFFFFFFF); g.setCornerRadius(Ui.dp(c, 20));
        g.setStroke(Math.max(1, Ui.dp(c, 1)), dk ? 0xFF8E918F : 0xFF747775);
        b.setBackground(g); b.setGravity(Gravity.CENTER); b.setPadding(Ui.dp(c, 12), 0, Ui.dp(c, 12), 0);
        android.graphics.drawable.Drawable gi = c.getDrawable(R.drawable.ic_google_g);
        if (gi != null) { gi.setBounds(0, 0, Ui.dp(c, 18), Ui.dp(c, 18)); b.setCompoundDrawables(gi, null, null, null); b.setCompoundDrawablePadding(Ui.dp(c, 10)); }
        b.setClickable(true); b.setFocusable(true); b.setContentDescription("Войти через Google");
        return b;
    }
}
