// ported from petalOS SystemUI NotificationSilkDrawable (Apache 2.0, petalOS 2026)
package org.petal.catback.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;

// solid panel, baked satin light, lit 1dp edge. no blur no motion
final class Silk {
    private final Paint surface = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint satin = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF last = new RectF(), inset = new RectF();
    private int lastAccent, lastBase;

    Silk() {
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(1f); // layout units are dp here
    }

    // base = surface colour, accent ~ system_accent1_200
    void draw(Canvas canvas, RectF rect, float radius, int base, int accent, boolean dark) {
        if (!rect.equals(last) || accent != lastAccent || base != lastBase) {
            last.set(rect); lastAccent = accent; lastBase = base;
            surface.setColor(base);
            // accent kept faint on purpose or text contrast goes to shit
            int tint = (accent & 0x00ffffff) | 0x10000000;
            satin.setShader(new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                    new int[]{dark ? 0x0cffffff : 0x30ffffff, tint, Color.TRANSPARENT}, null, Shader.TileMode.CLAMP));
            edge.setShader(new LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
                    new int[]{dark ? 0x50ffffff : 0x90ffffff, 0x08ffffff, dark ? 0x12ffffff : 0x10000000},
                    new float[]{0f, .38f, 1f}, Shader.TileMode.CLAMP));
        }
        canvas.drawRoundRect(rect, radius, radius, surface);
        canvas.drawRoundRect(rect, radius, radius, satin);
        inset.set(rect); inset.inset(.5f, .5f);
        canvas.drawRoundRect(inset, radius - .5f, radius - .5f, edge);
    }
}
