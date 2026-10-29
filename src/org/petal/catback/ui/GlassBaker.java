package org.petal.catback.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.HardwareRenderer;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.RenderNode;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.util.Log;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

// bakes island glass for static panels into a bitmap once, so it costs a single draw per frame and no gpu/battery.
// redone when geometry or wallpaper changes, worker thread, silk until it lands
final class GlassBaker {
    // same spec = same bitmap
    static final class Spec {
        final String key; final int canvasW, canvasH, left, top; final float sx, sy, layoutW, radius, band;
        final RectF rect; final int wallpaperVersion, tint;
        Spec(String key, int canvasW, int canvasH, int left, int top, float sx, float sy, float layoutW,
             RectF rect, float radius, int wallpaperVersion, int tint) {
            this.key = key; this.canvasW = canvasW; this.canvasH = canvasH; this.left = left; this.top = top;
            this.sx = sx; this.sy = sy; this.layoutW = layoutW; this.rect = new RectF(rect); this.radius = radius;
            this.wallpaperVersion = wallpaperVersion; this.tint = tint; this.band = 0;
        }
        @Override public boolean equals(Object o) {
            if (!(o instanceof Spec)) return false;
            Spec s = (Spec) o;
            return key.equals(s.key) && canvasW == s.canvasW && canvasH == s.canvasH && left == s.left && top == s.top
                    && sx == s.sx && sy == s.sy && layoutW == s.layoutW && rect.equals(s.rect) && radius == s.radius
                    && wallpaperVersion == s.wallpaperVersion && tint == s.tint;
        }
        @Override public int hashCode() { return key.hashCode() * 31 + canvasW * 7 + left; }
    }

    interface Wallpaper { void draw(Canvas canvas); }

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Catback-GlassBake"); t.setDaemon(true); return t;
    });
    private final Map<String, Bitmap> baked = new HashMap<>();
    private final Map<String, Spec> bakedSpec = new HashMap<>(), pending = new HashMap<>();
    private volatile int version;
    // one bad bake (gpu hiccup while everything else is starting up too) used to kill this for the whole
    // session. cooldown instead: skip retrying for a bit, not forever
    private volatile long retryAt;

    // bumps when a new bitmap lands
    int version() { return version; }
    boolean available() { return android.os.SystemClock.elapsedRealtime() >= retryAt; }

    synchronized Bitmap get(String key) { return baked.get(key); }

    // skip if that exact bake exists or is cooking
    synchronized void request(Spec spec, Wallpaper wallpaper) {
        if (!available() || spec.equals(bakedSpec.get(spec.key)) || spec.equals(pending.get(spec.key))) return;
        pending.put(spec.key, spec);
        worker.execute(() -> {
            Bitmap result = null;
            try { result = bake(spec, wallpaper); }
            catch (Throwable error) {
                Log.w("Catback", "Glass bake failed, retrying shortly; using Silk panels meanwhile", error);
                retryAt = android.os.SystemClock.elapsedRealtime() + 4000;
            }
            synchronized (GlassBaker.this) {
                if (result != null && spec.equals(pending.get(spec.key))) {
                    baked.put(spec.key, result); bakedSpec.put(spec.key, spec); version++;
                }
                if (spec.equals(pending.get(spec.key))) pending.remove(spec.key);
            }
        });
    }

    private Bitmap bake(Spec spec, Wallpaper wallpaper) throws Exception {
        int pw = Math.max(1, Math.round(spec.rect.width() * spec.sx)), ph = Math.max(1, Math.round(spec.rect.height() * spec.sy));

        // wallpaper only, placed exactly like the real frame
        RenderNode wall = new RenderNode("glass-wall");
        wall.setPosition(0, 0, spec.canvasW, spec.canvasH);
        Canvas wc = wall.beginRecording(spec.canvasW, spec.canvasH);
        try {
            wc.clipRect(spec.left, spec.top, spec.left + Math.round(spec.layoutW * spec.sx), spec.top + Math.round(480 * spec.sy));
            wc.translate(spec.left, spec.top); wc.scale(spec.sx, spec.sy);
            wallpaper.draw(wc);
        } finally { wall.endRecording(); }

        // glass drawn once into its own node, lens samples wall
        IslandGlass glass = new IslandGlass();
        RenderNode panel = new RenderNode("glass-panel");
        panel.setPosition(0, 0, pw, ph);
        Canvas pc = panel.beginRecording(pw, ph);
        try {
            pc.scale(spec.sx, spec.sy); pc.translate(-spec.rect.left, -spec.rect.top);
            glass.draw(pc, wall, 1, spec.left, spec.top, spec.sx, spec.sy, spec.rect, spec.radius, -1f, -1f, 0xFFB9A5FF, spec.tint);
        } finally { panel.endRecording(); }

        ImageReader reader = ImageReader.newInstance(pw, ph, PixelFormat.RGBA_8888, 2,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT);
        HardwareRenderer renderer = new HardwareRenderer();
        CountDownLatch ready = new CountDownLatch(1);
        reader.setOnImageAvailableListener(r -> ready.countDown(), new android.os.Handler(android.os.Looper.getMainLooper()));
        try {
            renderer.setSurface(reader.getSurface());
            renderer.setContentRoot(panel);
            renderer.createRenderRequest().syncAndDraw();
            if (!ready.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("glass render timed out");
            try (Image image = reader.acquireLatestImage()) {
                if (image == null) throw new IllegalStateException("no glass image");
                Bitmap hardware = Bitmap.wrapHardwareBuffer(image.getHardwareBuffer(), ColorSpace.get(ColorSpace.Named.SRGB));
                if (hardware == null) throw new IllegalStateException("glass buffer not readable");
                Bitmap copy = hardware.copy(Bitmap.Config.ARGB_8888, false); // outlives the hw buffer
                hardware.recycle();
                return copy;
            }
        } finally { renderer.destroy(); reader.close(); }
    }
}
