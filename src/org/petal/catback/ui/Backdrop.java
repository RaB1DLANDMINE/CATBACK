package org.petal.catback.ui;

import android.app.WallpaperColors;
import android.app.WallpaperManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

// car background = phone wallpaper, cropped + dimmed, plus a blurred copy for the frosted panels.
// can't read it (13+ locks non-system apps out)? aurora in its colours instead. loads off the render thread
final class Backdrop {
    // 0..1 of 48dp max blur, .5 = the 50% that was asked for
    static final float FROST = .5f;
    private static final float MAX_BLUR_DP = 48;

    private final Context context;
    private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG), blob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect src = new Rect();
    private final RectF dst = new RectF();
    private final Path clip = new Path();
    private volatile Bitmap sharp, frosted;
    private volatile int[] aurora = {0x40FF6FAE, 0x4D7C5CFF, 0x3357E0D2}; // same as PetalCam aurora
    private volatile boolean readable = true;
    private volatile int version;
    private int wallpaperId = Integer.MIN_VALUE;
    private long checkedAt = -1;
    private boolean loading;

    Backdrop(Context context) { this.context = context; }

    boolean showingWallpaper() { return sharp != null; }
    boolean wallpaperReadable() { return readable; }

    private void refresh() {
        long now = SystemClock.elapsedRealtime();
        if (loading || (checkedAt >= 0 && now - checkedAt < 5000)) return;
        checkedAt = now;
        WallpaperManager wm = WallpaperManager.getInstance(context);
        int id = wm.getWallpaperId(WallpaperManager.FLAG_SYSTEM);
        if (id == wallpaperId && sharp != null) return; // unreadable before, maybe access got granted since
        wallpaperId = id; loading = true;
        new Thread(() -> {
            try { load(wm); } finally { loading = false; }
        }, "Catback-Wallpaper").start();
    }
    private void load(WallpaperManager wm) {
        WallpaperColors colors = wm.getWallpaperColors(WallpaperManager.FLAG_SYSTEM);
        if (colors != null) {
            Color p = colors.getPrimaryColor(), s = colors.getSecondaryColor(), t = colors.getTertiaryColor();
            aurora = new int[]{withAlpha(p.toArgb(), 0x55), withAlpha(s != null ? s.toArgb() : 0xFF7C5CFF, 0x4D),
                    withAlpha(t != null ? t.toArgb() : 0xFF57E0D2, 0x40)};
        }
        // getDrawable() on some wallpaper services (scrolling multi-panel home screens especially) reports a
        // SMALLER intrinsic size than the real asset, so we'd stretch that up and it looks grainy. getBitmap()
        // is the actual stored asset at full res; only fall back to the drawable for a live wallpaper (no bitmap)
        Bitmap source = null;
        try { source = wm.getBitmap(false); } catch (OutOfMemoryError | RuntimeException denied) { }
        Drawable drawable = null;
        if (source == null) {
            try { drawable = wm.getDrawable(); } catch (SecurityException | OutOfMemoryError denied) { drawable = null; }
        }
        readable = source != null || drawable != null;
        android.util.Log.i("Catback", readable ? "Car background: phone wallpaper (" + (source != null
                ? source.getWidth() + "x" + source.getHeight() + " bitmap" : drawable.getIntrinsicWidth() + "x" + drawable.getIntrinsicHeight() + " drawable")
                + ")" : "Car background: wallpaper not readable, using its colours");
        if (source == null && drawable == null) { sharp = null; frosted = null; version++; return; }
        int w = Math.max(1, source != null ? source.getWidth() : drawable.getIntrinsicWidth());
        int h = Math.max(1, source != null ? source.getHeight() : drawable.getIntrinsicHeight());
        float k = Math.min(1f, 1600f / Math.max(w, h));
        Bitmap full = Bitmap.createBitmap(Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(full);
        Paint scale = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
        if (source != null) canvas.drawBitmap(source, null, new RectF(0, 0, full.getWidth(), full.getHeight()), scale);
        else { drawable.setBounds(0, 0, full.getWidth(), full.getHeight()); drawable.draw(canvas); }
        frosted = blur(full);
        sharp = full; version++;
    }

    // shrink + 3 box blurs, cheap and bilinear upscale hides the mush
    private static Bitmap blur(Bitmap source) {
        int w = Math.max(8, source.getWidth() / 12), h = Math.max(8, source.getHeight() / 12);
        Bitmap small = Bitmap.createScaledBitmap(source, w, h, true);
        int[] px = new int[w * h], tmp = new int[w * h];
        small.getPixels(px, 0, w, 0, 0, w, h);
        int radius = Math.max(1, Math.round(FROST * 3));
        for (int pass = 0; pass < 3; pass++) { boxBlur(px, tmp, w, h, radius, true); boxBlur(tmp, px, w, h, radius, false); }
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, w, 0, 0, w, h);
        return out;
    }
    private static void boxBlur(int[] in, int[] out, int w, int h, int r, boolean horizontal) {
        int len = horizontal ? w : h, lines = horizontal ? h : w;
        for (int line = 0; line < lines; line++) {
            for (int i = 0; i < len; i++) {
                int a = 0, red = 0, g = 0, b = 0, n = 0;
                for (int k = -r; k <= r; k++) {
                    int j = Math.max(0, Math.min(len - 1, i + k));
                    int c = in[horizontal ? line * w + j : j * w + line];
                    a += c >>> 24; red += (c >> 16) & 255; g += (c >> 8) & 255; b += c & 255; n++;
                }
                out[horizontal ? line * w + i : i * w + line] = (a / n) << 24 | (red / n) << 16 | (g / n) << 8 | (b / n);
            }
        }
    }

    // bumps when the wallpaper changes so baked glass gets redone
    int version() { return version; }

    synchronized void draw(Canvas c, float width, float height, float now) {
        refresh();
        Bitmap wallpaper = sharp;
        if (wallpaper != null) {
            cover(wallpaper, width, height);
            c.drawBitmap(wallpaper, src, dst, image);
            c.drawColor(0x40000000); // white text stays readable on bright walls
            return;
        }
        c.drawColor(0xFF05060A);
        int[] colors = aurora;
        float[] phase = {0f, 2.1f, 4.2f}, speed = {0.00010f, -0.00013f, 0.00008f};
        float radius = Math.max(width, height) * .9f, t = now * 1000f;
        for (int i = 0; i < 3; i++) {
            float a = phase[i] + t * speed[i];
            float cx = width * (.5f + .42f * (float) Math.cos(a)), cy = height * (.42f + .38f * (float) Math.sin(a * .9f + i));
            blob.setShader(new RadialGradient(cx, cy, radius, colors[i], Color.TRANSPARENT, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, radius, blob);
        }
    }

    // blurred wallpaper lined up with the sharp one, for panels
    synchronized void frost(Canvas c, RectF rect, float radius, float width, float height) {
        Bitmap blurred = frosted, wallpaper = sharp;
        if (blurred == null || wallpaper == null) return; // aurora is already soft
        cover(wallpaper, width, height);
        float kx = blurred.getWidth() / (float) wallpaper.getWidth(), ky = blurred.getHeight() / (float) wallpaper.getHeight();
        src.set(Math.round(src.left * kx), Math.round(src.top * ky), Math.round(src.right * kx), Math.round(src.bottom * ky));
        int saved = c.save();
        clip.reset(); clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
        c.clipPath(clip);
        c.drawBitmap(blurred, src, dst, image);
        c.drawColor(0x40000000);
        c.restoreToCount(saved);
    }

    private void cover(Bitmap b, float width, float height) {
        float k = Math.max(width / b.getWidth(), height / b.getHeight());
        float sw = width / k, sh = height / k, sl = (b.getWidth() - sw) / 2, st = (b.getHeight() - sh) / 2;
        src.set(Math.round(sl), Math.round(st), Math.round(sl + sw), Math.round(st + sh));
        dst.set(0, 0, width, height);
    }
    private static int withAlpha(int color, int alpha) { return (color & 0x00FFFFFF) | (alpha << 24); }
}
