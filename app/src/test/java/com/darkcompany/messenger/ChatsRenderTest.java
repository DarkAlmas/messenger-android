package com.darkcompany.messenger;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

/** Экран «Чаты» на реальном сервере: строка чата должна реально отрисоваться (ненулевой размер, видимый текст). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public class ChatsRenderTest {
    static void dump(View v, String ind, StringBuilder sb) {
        sb.append(ind).append(v.getClass().getSimpleName()).append(" vis=").append(v.getVisibility())
          .append(" ").append(v.getLeft()).append(",").append(v.getTop()).append(" ").append(v.getWidth()).append("x").append(v.getHeight())
          .append(" alpha=").append(v.getAlpha());
        if (v instanceof TextView) sb.append(" text='").append(((TextView) v).getText()).append("' color=#").append(Integer.toHexString(((TextView) v).getCurrentTextColor()));
        sb.append('\n');
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) dump(((ViewGroup) v).getChildAt(i), ind + "  ", sb);
    }
    static ListView find(View v) {
        if (v instanceof ListView) return (ListView) v;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) { ListView l = find(((ViewGroup) v).getChildAt(i)); if (l != null) return l; }
        return null;
    }

    @Test
    public void chatRowIsVisible() throws Exception {
        String a = System.getenv("E2E_A_EMAIL"), b = System.getenv("E2E_B_EMAIL"), pw = System.getenv("E2E_PASSWORD");
        assumeTrue(a != null && b != null && pw != null);
        Api api = Api.get(RuntimeEnvironment.getApplication());
        api.signIn(b, pw); String bUid = api.uid();
        api.signIn(a, pw);
        api.rpc("create_direct_chat", new JSONObject().put("p_other", bUid));
        ActivityController<ChatsActivity> c = Robolectric.buildActivity(ChatsActivity.class).setup();
        ChatsActivity act = c.get();
        View root = act.findViewById(android.R.id.content);
        ListView list = null;
        for (int i = 0; i < 100; i++) {
            ShadowLooper.idleMainLooper();
            list = find(root);
            if (list != null && list.getAdapter() != null && list.getAdapter().getCount() > 0) break;
            Thread.sleep(100);
        }
        ShadowLooper.idleMainLooper();
        View decor = act.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2200, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 1080, 2200);
        ShadowLooper.idleMainLooper(); // fitSystemBars пересчитывает отступ после layout
        decor.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2200, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 1080, 2200);
        StringBuilder sb = new StringBuilder(); dump(decor, "", sb);
        System.out.println("BUILD " + act.getSupportActionBar().getSubtitle() + "\n" + sb);
        assertNotNull("ListView", list);
        assertEquals("сервер вернул 1 чат", 1, list.getAdapter().getCount());
        assertTrue("строка списка создана", list.getChildCount() >= 1);
        View row = list.getChildAt(0);
        assertTrue("строка видна по размеру", row.getHeight() > 0 && row.getWidth() > 0);
        assertTrue("ListView видим", list.getVisibility() == View.VISIBLE && list.getHeight() > 0);
        // Строка не должна быть под тулбаром: её верх на экране — не выше низа панели действий
        View bar = decor.findViewById(androidx.appcompat.R.id.action_bar_container);
        int[] rb = new int[2], bb = new int[2];
        row.getLocationInWindow(rb); bar.getLocationInWindow(bb);
        assertTrue("строка чата перекрыта тулбаром: row.top=" + rb[1] + " bar.bottom=" + (bb[1] + bar.getHeight()), rb[1] >= bb[1] + bar.getHeight());
        c.pause().stop().destroy();
    }
}
