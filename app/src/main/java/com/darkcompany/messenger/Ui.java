package com.darkcompany.messenger;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** Material 3 UI helpers (layouts built in code, no XML). */
final class Ui {
    private static final int[] AVATAR = {0xFF5C6BC0, 0xFF26A69A, 0xFFEF6C00, 0xFFAB47BC, 0xFF42A5F5, 0xFFEC407A, 0xFF7CB342, 0xFF8D6E63};

    static int dp(Context c, int v) { return (int) (v * c.getResources().getDisplayMetrics().density); }

    static int attr(Context c, int a) {
        TypedValue tv = new TypedValue();
        c.getTheme().resolveAttribute(a, tv, true);
        return tv.resourceId != 0 ? c.getColor(tv.resourceId) : tv.data;
    }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        int p = dp(c, 16);
        l.setPadding(p, p, p, p);
        return l;
    }

    static EditText input(Context c, String hint) {
        TextInputEditText e = new TextInputEditText(c);
        e.setHint(hint);
        e.setSingleLine(true);
        return e;
    }

    /** Wraps an input in an outlined Material text field. */
    static TextInputLayout field(EditText e) {
        Context c = e.getContext();
        TextInputLayout l = new TextInputLayout(c, null, com.google.android.material.R.attr.textInputOutlinedStyle);
        l.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        float r = dp(c, 14);
        l.setBoxCornerRadii(r, r, r, r);
        l.setHint(e.getHint());
        e.setHint(null);
        if ((e.getInputType() & 0xFFF) == (android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD))
            l.setEndIconMode(TextInputLayout.END_ICON_PASSWORD_TOGGLE);
        l.addView(e, new LinearLayout.LayoutParams(-1, -2));
        return l;
    }

    /** Rounded pill background for the composer input. */
    static void pill(EditText e) {
        Context c = e.getContext();
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(c, 24));
        g.setColor(attr(c, com.google.android.material.R.attr.colorSurfaceContainerHighest));
        e.setBackground(g);
        e.setPadding(dp(c, 18), dp(c, 12), dp(c, 18), dp(c, 12));
        e.setTextSize(16);
    }

    static Button button(Context c, String text) {
        MaterialButton b = new MaterialButton(c);
        b.setText(text);
        b.setMinHeight(dp(c, 52));
        b.setCornerRadius(dp(c, 26));
        b.setTextSize(16);
        return b;
    }

    static Button textButton(Context c, String text) {
        MaterialButton b = new MaterialButton(c, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        b.setText(text);
        return b;
    }

    static View sendButton(Context c) {
        MaterialButton b = new MaterialButton(c, null, com.google.android.material.R.attr.materialIconButtonFilledStyle);
        b.setIconResource(android.R.drawable.ic_menu_send);
        b.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_START);
        b.setIconPadding(0);
        b.setInsetTop(0); b.setInsetBottom(0);
        b.setPadding(0, 0, 0, 0);
        b.setCornerRadius(dp(c, 24));
        b.setContentDescription("Отправить");
        return b;
    }

    /**
     * targetSdk 35+ на Android 15+ — принудительный edge-to-edge: контент рисуется под статус-баром и панелью
     * действий (тулбаром), а снизу — под панелью навигации/клавиатурой. Сдвигаем корень экрана так, чтобы он
     * начинался под тулбаром и заканчивался над системными панелями/клавиатурой. Проверяется на каждом layout.
     */
    static void fitSystemBars(android.app.Activity a, View root) {
        final int pl = root.getPaddingLeft(), pt = root.getPaddingTop(), pr = root.getPaddingRight(), pb = root.getPaddingBottom();
        final int[] ins = new int[4]; // left, top, right, bottom (системные панели + клавиатура)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root, (v, w) -> {
            androidx.core.graphics.Insets b = w.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars()
                    | androidx.core.view.WindowInsetsCompat.Type.displayCutout() | androidx.core.view.WindowInsetsCompat.Type.ime());
            ins[0] = b.left; ins[1] = b.top; ins[2] = b.right; ins[3] = b.bottom;
            applyFit(a, v, pl, pt, pr, pb, ins);
            return w;
        });
        root.getViewTreeObserver().addOnGlobalLayoutListener(() -> applyFit(a, root, pl, pt, pr, pb, ins));
    }

    private static void applyFit(android.app.Activity a, View root, int pl, int pt, int pr, int pb, int[] ins) {
        int[] r = new int[2];
        root.getLocationInWindow(r);
        int top = Math.max(0, ins[1] - r[1]); // статус-бар
        View bar = a.getWindow().getDecorView().findViewById(androidx.appcompat.R.id.action_bar_container);
        if (bar != null && bar.getVisibility() == View.VISIBLE && bar.getHeight() > 0) {
            int[] br = new int[2];
            bar.getLocationInWindow(br);
            top = Math.max(top, br[1] + bar.getHeight() - r[1]); // перекрытие тулбаром
        }
        View decor = a.getWindow().getDecorView();
        int below = decor.getHeight() - (r[1] + root.getHeight()); // сколько окна уже ниже корня
        int bottom = Math.max(0, ins[3] - Math.max(0, below));
        int nl = pl + ins[0], nt = pt + top, nr = pr + ins[2], nb = pb + bottom;
        if (root.getPaddingLeft() != nl || root.getPaddingTop() != nt || root.getPaddingRight() != nr || root.getPaddingBottom() != nb)
            root.setPadding(nl, nt, nr, nb);
    }

    static TextView text(Context c, String s, int sp) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(attr(c, com.google.android.material.R.attr.colorOnSurface));
        return t;
    }

    static LinearLayout.LayoutParams match() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams spaced(Context c) {
        LinearLayout.LayoutParams lp = match();
        lp.topMargin = dp(c, 12);
        return lp;
    }

    static TextView avatar(Context c, String name, String seed, int sizeDp) {
        TextView a = new TextView(c);
        String n = name == null ? "" : name.trim();
        a.setText(n.isEmpty() ? "?" : n.substring(0, n.offsetByCodePoints(0, 1)).toUpperCase());
        a.setGravity(Gravity.CENTER);
        a.setTextColor(Color.WHITE);
        a.setTextSize(sizeDp * 0.4f);
        a.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(AVATAR[Math.abs((seed == null ? n : seed).hashCode()) % AVATAR.length]);
        a.setBackground(g);
        return a;
    }

    static View logo(Context c) {
        TextView a = new TextView(c);
        a.setText("💬");
        a.setTextSize(40);
        a.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(c, 28));
        g.setColor(attr(c, com.google.android.material.R.attr.colorPrimaryContainer));
        a.setBackground(g);
        return a;
    }

    static View chatRow(Context c, org.json.JSONObject o, String me) {
        String title = o.optString("_title", "Чат");
        String id = o.optString("chat_id");
        int unread = o.optInt("unread", 0);
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(c, 16), dp(c, 10), dp(c, 16), dp(c, 10));
        row.setMinimumHeight(dp(c, 72));
        String seed = o.isNull("peer_id") ? id : o.optString("peer_id");
        if (o.optBoolean("_archive")) { // .archive-row: значок archive / arrow_back на сером круге
            TextView av = Site.icon(c, o.optString("_sub").startsWith("Назад") ? "arrow_back" : "archive", 24, Site.text2(c));
            av.setBackground(Site.round(Site.container(c), dp(c, 26)));
            row.addView(av, new LinearLayout.LayoutParams(dp(c, 52), dp(c, 52)));
        } else
        row.addView(o.isNull("peer_id") ? Avatars.render(c, null, title, seed, 52, null) : Avatars.view(c, o.optString("peer_id"), title, 52),
                new LinearLayout.LayoutParams(dp(c, 52), dp(c, 52)));
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(c, 14), 0, dp(c, 8), 0);
        TextView t = Site.label(c, title, 17f, Site.text(c), 700);
        t.setSingleLine(true);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        String last;
        if (o.optBoolean("_archive")) last = o.optString("_sub");
        else if (o.isNull("last_at")) last = "Нет сообщений";
        else {
            String b = o.isNull("last_body") ? "" : o.optString("last_body", "");
            last = !o.optBoolean("last_has_file") && b.trim().isEmpty() && !o.isNull("last_at") ? "🔒 зашифрованное сообщение" :
                    o.optBoolean("last_has_file") && b.trim().isEmpty() ? "📎 Файл" : (o.optBoolean("last_has_file") ? "📎 " : "") + b.replace('\n', ' ');
            // Шифрованные сообщения (личные и групповые) хранятся с пустым body; непустой body = открытый текст (сайт/старая версия)
            if (!b.trim().isEmpty()) last = "⚠ не зашифровано: " + last;
            else if (o.has("_preview") && !o.optString("_preview").isEmpty()) last = o.optString("_preview").replace('\n', ' ');
            if (me != null && me.equals(o.optString("last_sender"))) last = "Вы: " + last;
        }
        TextView s = Site.label(c, last, 14f, Site.text2(c), 400);
        s.setSingleLine(true);
        s.setEllipsize(android.text.TextUtils.TruncateAt.END);
        col.addView(t);
        col.addView(s);
        row.addView(col, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout side = new LinearLayout(c);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setGravity(Gravity.END);
        int primary = Site.primary(c);
        boolean muted = ChatsActivity.muted(o);
        TextView tm = Site.label(c, o.isNull("last_at") ? "" : listTime(instant(o.optString("last_at")), System.currentTimeMillis()), 12f, unread > 0 && !muted ? primary : Site.text2(c), 400);
        side.addView(tm);
        LinearLayout flags = new LinearLayout(c); flags.setGravity(Gravity.CENTER_VERTICAL);
        if (muted) flags.addView(Site.icon(c, "notifications_off", 16, Site.text2(c)));
        if (unread <= 0 && o.optBoolean("pinned")) flags.addView(Site.icon(c, "push_pin", 16, Site.text2(c)));
        if (flags.getChildCount() > 0) { LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(-2, dp(c, 22)); fl.topMargin = dp(c, 4); fl.gravity = Gravity.END; side.addView(flags, fl); }
        if (unread > 0) {
            TextView bd = Site.label(c, unread > 99 ? "99+" : String.valueOf(unread), 12f, 0xFFFFFFFF, 700);
            bd.setGravity(Gravity.CENTER);
            bd.setTextColor(attr(c, com.google.android.material.R.attr.colorOnPrimary));
            bd.setMinWidth(dp(c, 22));
            bd.setPadding(dp(c, 6), 0, dp(c, 6), 0);
            GradientDrawable g = new GradientDrawable();
            g.setCornerRadius(dp(c, 11));
            g.setColor(muted || o.optBoolean("_archive") ? c.getColor(R.color.web_color_muted_badge) : primary);
            bd.setBackground(g);
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(-2, dp(c, 22));
            bl.topMargin = dp(c, 4);
            bl.gravity = Gravity.END;
            side.addView(bd, bl);
        }
        row.addView(side, new LinearLayout.LayoutParams(-2, -2));
        return row;
    }

    static String size(long b) {
        if (b < 1024) return b + " Б";
        if (b < 1024 * 1024) return String.format(Locale.getDefault(), "%.1f КБ", b / 1024.0);
        return String.format(Locale.getDefault(), "%.1f МБ", b / 1048576.0);
    }

    /** Карточка вложения внутри пузырька. */
    static View attachment(Context c, boolean mine, String name, long bytes, View.OnClickListener click) {
        int fg = attr(c, mine ? com.google.android.material.R.attr.colorOnPrimary : com.google.android.material.R.attr.colorOnSurface);
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(c, 4), dp(c, 6), dp(c, 8), dp(c, 6));
        TextView ic = new TextView(c);
        ic.setText(ChatFiles.risky(name) ? "⚠️" : "📄");
        ic.setTextSize(20);
        ic.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor((fg & 0x00FFFFFF) | 0x33000000);
        ic.setBackground(g);
        card.addView(ic, new LinearLayout.LayoutParams(dp(c, 42), dp(c, 42)));
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(c, 10), 0, 0, 0);
        TextView n = text(c, name, 15);
        n.setTypeface(Typeface.DEFAULT_BOLD);
        n.setTextColor(fg);
        n.setMaxLines(2);
        n.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        TextView sz = text(c, size(bytes) + " · нажмите, чтобы открыть", 12);
        sz.setTextColor(fg);
        sz.setAlpha(0.75f);
        col.addView(n);
        col.addView(sz);
        card.addView(col, new LinearLayout.LayoutParams(-2, -2));
        card.setOnClickListener(click);
        return card;
    }

    static View bubble(Context c, boolean mine, String who, String body, String time) { return bubble(c, mine, who, body, time, null); }

    static View bubble(Context c, boolean mine, String who, String body, String time, View extra) {
        LinearLayout b = new LinearLayout(c);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setPadding(dp(c, 14), dp(c, 8), dp(c, 14), dp(c, 6));
        GradientDrawable g = new GradientDrawable();
        float r = dp(c, 18), s = dp(c, 6);
        g.setCornerRadii(mine ? new float[]{r, r, r, r, s, s, r, r} : new float[]{r, r, r, r, r, r, s, s});
        g.setColor(attr(c, mine ? androidx.appcompat.R.attr.colorPrimary : com.google.android.material.R.attr.colorSurfaceContainerHigh));
        b.setBackground(g);
        int fg = attr(c, mine ? com.google.android.material.R.attr.colorOnPrimary : com.google.android.material.R.attr.colorOnSurface);
        if (who != null) {
            TextView w = text(c, who, 13);
            w.setTypeface(Typeface.DEFAULT_BOLD);
            w.setTextColor(AVATAR[Math.abs(who.hashCode()) % AVATAR.length]);
            b.addView(w);
        }
        // WRAP_CONTENT, а не MATCH_PARENT по умолчанию: иначе вертикальный LinearLayout с wrap-шириной
        // сжимал карточку файла до ширины строки времени — имя и размер файла получали 0 px (виден был только значок)
        if (extra != null) b.addView(extra, new LinearLayout.LayoutParams(-2, -2));
        if (body != null && !body.isEmpty()) {
            TextView t = text(c, body, 16);
            t.setTextColor(fg);
            b.addView(t);
        }
        if (time != null && !time.isEmpty()) {
            TextView tm = text(c, time, 11);
            tm.setTextColor(fg);
            tm.setAlpha(0.7f);
            LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(-2, -2);
            tl.gravity = Gravity.END;
            b.addView(tm, tl);
        }
        b.setMinimumWidth(dp(c, 64));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.gravity = mine ? Gravity.END : Gravity.START;
        lp.topMargin = dp(c, 4);
        lp.bottomMargin = dp(c, 2);
        if (mine) lp.leftMargin = dp(c, 56); else lp.rightMargin = dp(c, 56);
        b.setLayoutParams(lp);
        return b;
    }

    /** "2026-10-14T01:02:03.123+00:00" -> local "HH:mm" (or "dd.MM HH:mm" if not today). */
    private static final String[] WD = {"вс", "пн", "вт", "ср", "чт", "пт", "сб"};
    /** fmtListTime сайта: сегодня — ЧЧ:ММ, меньше 6 дней — «пн», иначе ДД.ММ(.ГГ). */
    static String listTime(long t, long now) {
        java.util.Calendar d = java.util.Calendar.getInstance(); d.setTimeInMillis(t);
        java.util.Calendar n = java.util.Calendar.getInstance(); n.setTimeInMillis(now);
        if (d.get(java.util.Calendar.YEAR) == n.get(java.util.Calendar.YEAR) && d.get(java.util.Calendar.DAY_OF_YEAR) == n.get(java.util.Calendar.DAY_OF_YEAR))
            return String.format(java.util.Locale.ROOT, "%02d:%02d", d.get(java.util.Calendar.HOUR_OF_DAY), d.get(java.util.Calendar.MINUTE));
        if (now - t < 6 * 86400000L) return WD[d.get(java.util.Calendar.DAY_OF_WEEK) - 1];
        String s = String.format(java.util.Locale.ROOT, "%02d.%02d", d.get(java.util.Calendar.DAY_OF_MONTH), d.get(java.util.Calendar.MONTH) + 1);
        return s + "." + String.format(java.util.Locale.ROOT, "%02d", d.get(java.util.Calendar.YEAR) % 100);
    }
    static String time(String iso) {
        if (iso == null || iso.length() < 19) return "";
        try {
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            in.setTimeZone(TimeZone.getTimeZone("UTC"));
            Date d = in.parse(iso.substring(0, 19));
            return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(d); // fmtTime сайта: только время, день — в .date-sep
        } catch (Exception e) { return ""; }
    }

    /** ISO-время PostgREST -> микросекунды UTC (для сравнения прочтения). */
    /** Миллисекунды epoch (как Date.parse на сайте). Раньше возвращались микросекунды, и все, кто передавал результат в Date/Calendar
     *  (даты в ленте, «был(а)…», без звука, устройства), получали год 58771. Точность до микросекунд — instantMicros(). */
    static long instant(String iso) { long us = instantMicros(iso); return us / 1000; }
    static long instantMicros(String iso) {
        if (iso == null || iso.length() < 19) return 0;
        try {
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
            in.setTimeZone(TimeZone.getTimeZone("UTC"));
            long ms = in.parse(iso.substring(0, 19)).getTime();
            String f = "";
            if (iso.length() > 19 && iso.charAt(19) == '.') {
                int j = 20; while (j < iso.length() && Character.isDigit(iso.charAt(j))) j++;
                f = iso.substring(20, j);
            }
            f = (f + "000000").substring(0, 6);
            return ms * 1000 + Long.parseLong(f);
        } catch (Exception e) { return 0; }
    }

    static void toast(Activity a, String m) {
        a.runOnUiThread(() -> Toast.makeText(a, m, Toast.LENGTH_LONG).show());
    }

    /** Понятный текст ошибки: сеть/таймаут вместо технического сообщения. */
    static String err(Throwable e) {
        if (e instanceof java.net.UnknownHostException || e instanceof java.net.ConnectException || e instanceof java.net.NoRouteToHostException)
            return "Нет соединения с интернетом";
        if (e instanceof java.net.SocketTimeoutException || e instanceof java.io.InterruptedIOException) return "Сервер не отвечает, попробуйте позже";
        if (e instanceof javax.net.ssl.SSLException) return "Ошибка защищённого соединения";
        String m = e == null ? null : e.getMessage();
        if (m == null || m.trim().isEmpty()) return "Что-то пошло не так";
        if (e instanceof Api.HttpError && ((Api.HttpError) e).code == 429) return "Слишком много попыток. Подождите немного";
        if (e instanceof Api.HttpError && ((Api.HttpError) e).code == 413) return "Слишком большой размер (сообщение или файл)";
        return ru(m);
    }

    /** Перевод типовых ошибок Supabase/валидации на русский. */
    static String ru(String m) {
        String l = m.toLowerCase(java.util.Locale.ROOT);
        if (l.contains("e2e required")) return "Сервер принимает в этом чате только зашифрованные сообщения. Обновите приложение у обоих собеседников";
        if (l.contains("stale key epoch")) return "Ключ группы сменился (кто-то вышел или сменил устройство). Попробуйте ещё раз";
        if (l.contains("group e2e")) return "Сервер отклонил зашифрованное сообщение группы. Обновите приложение";
        if (l.startsWith("edit:") || l.contains(" edit:")) {
            if (l.contains("48")) return "Изменить можно только в течение 48 часов";
            if (l.contains("author") || l.contains("own")) return "Можно изменять только свои сообщения";
            if (l.contains("member")) return "Вы больше не участник этого чата";
            if (l.contains("not encrypted") || l.contains("plaintext")) return "Это сообщение не зашифровано — изменить его нельзя";
            if (l.contains("not found")) return "Сообщение не найдено";
            if (l.contains("too large") || l.contains("size")) return "Сообщение слишком большое";
            return "Не удалось изменить сообщение";
        }
        if (l.contains("invalid login credentials")) return "Неверная почта или пароль";
        if (l.contains("email not confirmed")) return "Почта не подтверждена. Откройте письмо и подтвердите адрес";
        if (l.contains("already registered") || l.contains("already been registered")) return "Пользователь с такой почтой уже зарегистрирован";
        if (l.contains("password should be at least") || l.contains("weak password") || l.contains("weak_password")) return "Пароль слишком короткий или простой (минимум 10 символов)";
        if (l.contains("unable to validate email") || l.contains("invalid format") || (l.contains("email") && l.contains("invalid"))) return "Неверный формат почты";
        if (l.contains("rate limit") || l.contains("too many")) return "Слишком много попыток. Подождите немного";
        if (l.contains("too big") || l.contains("too large") || l.contains("payload")) return "Слишком большой размер (сообщение или файл)";
        if (l.contains("duplicate key") && l.contains("username")) return "Этот никнейм уже занят";
        if (l.contains("duplicate key")) return "Такая запись уже существует";
        if (l.contains("jwt expired") || l.contains("refresh token")) return "Сессия истекла, войдите снова";
        if (l.contains("row-level security") || l.contains("permission denied")) return "Нет прав на это действие";
        if (l.contains("check constraint") || l.contains("violates")) return "Недопустимое значение";
        if (l.contains("signup") && l.contains("disabled")) return "Регистрация временно отключена";
        if (l.contains("user not found")) return "Пользователь не найден";
        if (l.contains("failed to fetch") || l.contains("unable to resolve host") || l.contains("failed to connect")) return "Нет соединения с сервером";
        if (l.contains("timeout")) return "Сервер не отвечает, попробуйте позже";
        // уже русский текст — как есть; иначе с пометкой
        return m.matches("(?s).*[А-Яа-яЁё].*") ? m : "Ошибка: " + m;
    }

    static int gray() { return Color.parseColor("#8E8E93"); }
}
