package com.darkcompany.messenger;

import static org.junit.Assert.*;

import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Пузырь с файлом: имя и размер файла должны получать ненулевую ширину (раньше был виден только значок 📄). */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
public class AttachmentBubbleTest {
    static TextView findText(View v, String prefix) {
        if (v instanceof TextView && ((TextView) v).getText().toString().startsWith(prefix)) return (TextView) v;
        if (v instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) { TextView t = findText(((ViewGroup) v).getChildAt(i), prefix); if (t != null) return t; }
        return null;
    }

    @Test
    public void fileNameAndSizeAreVisible() {
        android.app.Activity a = Robolectric.buildActivity(androidx.appcompat.app.AppCompatActivity.class).setup().get();
        a.setTheme(R.style.Theme_Messenger);
        for (boolean mine : new boolean[]{true, false}) {
            LinearLayout col = new LinearLayout(a);
            col.setOrientation(LinearLayout.VERTICAL);
            View att = Ui.attachment(a, mine, "e2e_test.jpg", 693, v -> { });
            LinearLayout extra = new LinearLayout(a);
            extra.setOrientation(LinearLayout.VERTICAL);
            extra.addView(att);
            col.addView(Ui.bubble(a, mine, mine ? null : "@dark", "", "🔒 21:28  ✓", extra));
            FrameLayout root = new FrameLayout(a);
            root.addView(col, new FrameLayout.LayoutParams(-1, -2));
            root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.AT_MOST));
            root.layout(0, 0, 1080, root.getMeasuredHeight());
            TextView name = findText(root, "e2e_test.jpg"), size = findText(root, "693");
            assertNotNull(name); assertNotNull(size);
            assertTrue("ширина имени файла " + name.getWidth() + " < нужной " + (int) Math.ceil(name.getPaint().measureText("e2e_test.jpg")), name.getWidth() >= (int) Math.floor(name.getPaint().measureText("e2e_test.jpg")) && name.getWidth() > 50);
            assertTrue("ширина размера файла " + size.getWidth(), size.getWidth() > 50);
            assertEquals("имя файла в одну строку", 1, name.getLineCount());
        }
    }
}
