// ported from petalOS SystemUI LiquidGlassDrawable.kt + RefractionShader.kt (Apache 2.0, petalOS 2026)
package org.petal.catback.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.annotation.TargetApi;
import android.os.Build;
import java.util.Random;

// island only: blur + rim refraction, slab, grain, rim, sheen, neon comet. blur-only on 31-32, flat tint below
final class IslandGlass {
    // tiny capsule barely bends, big cards cap at 18dp
    private static final float BAND_FRACTION = 0.20f, BAND_MAX_DP = 18f, STRENGTH_DP = 24f, BLUR_BEHIND_DP = 24f;
    private static final float NEON_CHAIN = 0.24f, NEON_FADE = 0.12f;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), grain = new Paint(Paint.ANTI_ALIAS_FLAG),
            rim = new Paint(Paint.ANTI_ALIAS_FLAG), sheen = new Paint(Paint.ANTI_ALIAS_FLAG),
            neonCore = new Paint(Paint.ANTI_ALIAS_FLAG), neonGlow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path(), rimPath = new Path(), pixelPath = new Path(), segment = new Path(), wrapped = new Path();
    private final PathMeasure measure = new PathMeasure();
    private final Matrix sheenMatrix = new Matrix();
    private final RectF rimRect = new RectF(), clip = new RectF();
    private final Object slab, lens; // RuntimeShader on 33+
    private final RenderNode backdrop = new RenderNode("island-backdrop");
    private LinearGradient sheenShader;
    private RenderEffect blurEffect, lensEffect; private float blurFor = Float.NaN;
    private float rimTop = Float.NaN, rimBottom = Float.NaN;
    // blurred+bent backdrop only rebuilt when the scene or island box moves
    private int cachedVersion = -1, cachedW, cachedH; private float cachedLeft = Float.NaN, cachedTop = Float.NaN, cachedRadius = Float.NaN;
    private float sheenWidth = -1;

    IslandGlass() {
        rim.setStyle(Paint.Style.STROKE); rim.setStrokeWidth(1f);
        neonCore.setStyle(Paint.Style.STROKE); neonGlow.setStyle(Paint.Style.STROKE);
        neonGlow.setMaskFilter(new BlurMaskFilter(5f, BlurMaskFilter.Blur.NORMAL));
        grain.setShader(new BitmapShader(noise(), Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));
        grain.setAlpha(0x14);
        sheen.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP));
        Object s = null, l = null;
        if (Build.VERSION.SDK_INT >= 33) {
            try { s = new RuntimeShader(SLAB_AGSL); l = new RuntimeShader(LENS_AGSL); }
            catch (RuntimeException unsupported) { s = null; l = null; }
        }
        slab = s; lens = l;
    }

    static float band(float w, float h) { return Math.min(Math.min(w, h) * BAND_FRACTION, BAND_MAX_DP); }

    // canvas in layout units (sx/sy/ox/oy from pixels), scene = stuff behind in pixel space or null, rect in layout units
    void draw(Canvas canvas, RenderNode scene, int sceneVersion, float ox, float oy, float sx, float sy,
              RectF rect, float radius, float sheenProgress, float neonProgress, int neonColor) {
        draw(canvas, scene, sceneVersion, ox, oy, sx, sy, rect, radius, sheenProgress, neonProgress, neonColor, 0);
    }

    // 0 = island tint, else argb (panels go lighter)
    void draw(Canvas canvas, RenderNode scene, int sceneVersion, float ox, float oy, float sx, float sy,
              RectF rect, float radius, float sheenProgress, float neonProgress, int neonColor, int tintOverride) {
        if (rect.width() < 1 || rect.height() < 1) return;
        path.reset(); path.addRoundRect(rect, radius, radius, Path.Direction.CW);
        boolean hardware = canvas.isHardwareAccelerated() && scene != null;
        boolean blurred = hardware && Build.VERSION.SDK_INT >= 31;

        if (blurred) backdropLens(canvas, scene, sceneVersion, ox, oy, sx, sy, rect, radius);

        // same as IslandView.themeBackground()
        int tint = tintOverride != 0 ? tintOverride : blurred ? 0xA8101012 : 0xF2101012;
        if (slab != null && blurred && Build.VERSION.SDK_INT >= 33) { fill.setShader(slab(rect, radius, tint)); fill.setColor(Color.WHITE); }
        else { fill.setShader(null); fill.setColor(tint); }
        canvas.drawPath(path, fill);
        canvas.drawPath(path, grain);

        rimRect.set(rect); rimRect.inset(.5f, .5f);
        rimPath.reset(); rimPath.addRoundRect(rimRect, radius, radius, Path.Direction.CW);
        if (rect.top != rimTop || rect.bottom != rimBottom) {
            rimTop = rect.top; rimBottom = rect.bottom;
            rim.setShader(new LinearGradient(0, rect.top, 0, rect.bottom, 0x2EFFFFFF, 0x0AFFFFFF, Shader.TileMode.CLAMP));
        }
        canvas.drawPath(rimPath, rim);

        if (neonProgress >= 0f && neonProgress <= 1f) neon(canvas, neonProgress, neonColor);
        if (sheenProgress >= 0f && sheenProgress <= 1f) sheen(canvas, rect, sheenProgress);
    }

    @TargetApi(33)
    private Shader slab(RectF rect, float radius, int tint) {
        RuntimeShader shader = (RuntimeShader) slab;
        shader.setFloatUniform("center", rect.centerX(), rect.centerY());
        shader.setFloatUniform("halfSize", rect.width() * .5f, rect.height() * .5f);
        shader.setFloatUniform("radius", Math.min(radius, Math.min(rect.width(), rect.height()) * .5f));
        shader.setFloatUniform("bandWidth", band(rect.width(), rect.height()));
        shader.setFloatUniform("gain", 1f);
        shader.setFloatUniform("surface", luminance(tint));
        shader.setColorUniform("tint", tint);
        return shader;
    }

    // blur whatever's behind and bend it at the edge
    @TargetApi(31)
    private void backdropLens(Canvas canvas, RenderNode scene, int sceneVersion, float ox, float oy, float sx, float sy, RectF rect, float radius) {
        float left = ox + rect.left * sx, top = oy + rect.top * sy;
        int w = Math.max(1, Math.round(rect.width() * sx)), h = Math.max(1, Math.round(rect.height() * sy));
        boolean same = sceneVersion == cachedVersion && w == cachedW && h == cachedH && left == cachedLeft && top == cachedTop;
        if (!same) {
            backdrop.setPosition(0, 0, w, h);
            Canvas record = backdrop.beginRecording(w, h);
            try { record.translate(-left, -top); record.drawRenderNode(scene); }
            finally { backdrop.endRecording(); }
            if (blurEffect == null || blurFor != sy) {
                float blur = BLUR_BEHIND_DP * sy * .5f; blurFor = sy;
                blurEffect = RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP);
            }
            RenderEffect effect = blurEffect;
            if (lens != null && Build.VERSION.SDK_INT >= 33 && (w != cachedW || h != cachedH || radius != cachedRadius || lensEffect == null))
                lensEffect = lens(blurEffect, w, h, radius * sy, sy);
            if (lens != null && Build.VERSION.SDK_INT >= 33 && lensEffect != null) effect = lensEffect;
            backdrop.setRenderEffect(effect);
            cachedVersion = sceneVersion; cachedW = w; cachedH = h; cachedLeft = left; cachedTop = top; cachedRadius = radius;
        }
        int saved = canvas.save();
        // backdrop is in frame pixels, get out of layout space
        canvas.scale(1f / sx, 1f / sy, rect.left, rect.top);
        clip.set(rect.left, rect.top, rect.left + w, rect.top + h);
        pixelPath.reset();
        pixelPath.addRoundRect(clip, radius * sy, radius * sy, Path.Direction.CW);
        canvas.clipPath(pixelPath);
        canvas.translate(rect.left, rect.top);
        canvas.drawRenderNode(backdrop);
        canvas.restoreToCount(saved);
    }

    @TargetApi(33)
    private RenderEffect lens(RenderEffect blurred, int w, int h, float radiusPx, float sy) {
        RuntimeShader shader = (RuntimeShader) lens;
        shader.setFloatUniform("halfSize", w * .5f, h * .5f);
        shader.setFloatUniform("radius", Math.min(radiusPx, Math.min(w, h) * .5f));
        // .65 lens like the shade glass
        shader.setFloatUniform("strength", STRENGTH_DP * sy * .65f);
        shader.setFloatUniform("bandWidth", Math.max(.001f, Math.min(Math.min(w, h) * BAND_FRACTION, BAND_MAX_DP * sy)));
        return RenderEffect.createChainEffect(RenderEffect.createRuntimeShaderEffect(shader, "originalInput"), blurred);
    }

    private void neon(Canvas canvas, float progress, int color) {
        measure.setPath(rimPath, false);
        float perimeter = measure.getLength();
        if (perimeter <= 0) return;
        float chain = perimeter * NEON_CHAIN, head = progress * (perimeter + chain), tail = Math.max(0, head - chain);
        if (tail >= perimeter) return;
        segment.reset();
        float over = head - perimeter;
        if (over <= 0) measure.getSegment(tail, head, segment, true);
        else {
            measure.getSegment(tail, perimeter, segment, true);
            wrapped.reset(); measure.getSegment(0, over, wrapped, true); segment.addPath(wrapped);
        }
        float alpha = clamp(progress / NEON_FADE) * clamp((1 - progress) / NEON_FADE);
        neonGlow.setColor(withAlpha(color, (int) (alpha * 0x30))); neonGlow.setStrokeWidth(5f);
        canvas.drawPath(segment, neonGlow);
        neonCore.setColor(withAlpha(color, (int) (alpha * 0xFF))); neonCore.setStrokeWidth(1.6f);
        canvas.drawPath(segment, neonCore);
    }

    private void sheen(Canvas canvas, RectF rect, float progress) {
        float w = rect.width(), h = rect.height(), band = w * .4f, x = rect.left - band + progress * (w + band);
        float fade = clamp((1 - progress) / .25f);
        if (fade <= 0) return;
        if (sheenWidth != band) {
            sheenWidth = band;
            sheenShader = new LinearGradient(0, 0, band, 0, new int[]{0x00FFFFFF, 0x38FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP);
            sheen.setShader(sheenShader);
        }
        sheenMatrix.setTranslate(x, 0); sheenShader.setLocalMatrix(sheenMatrix);
        sheen.setAlpha((int) (255 * fade));
        int saved = canvas.save();
        canvas.clipPath(path);
        canvas.rotate(-22f, rect.centerX(), rect.centerY());
        canvas.drawRect(x, rect.top - h, x + band, rect.bottom + h, sheen);
        canvas.restoreToCount(saved);
    }

    private static float clamp(float v) { return Math.max(0f, Math.min(1f, v)); }
    private static int withAlpha(int color, int alpha) { return (color & 0x00FFFFFF) | (Math.max(0, Math.min(255, alpha)) << 24); }
    private static float luminance(int c) {
        return (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f;
    }
    private static Bitmap noise() {
        int size = 96; int[] px = new int[size * size]; Random random = new Random(0x5EED);
        for (int i = 0; i < px.length; i++) {
            int a = random.nextInt(0x40);
            px[i] = random.nextBoolean() ? Color.argb(a, 255, 255, 255) : Color.argb(a, 0, 0, 0);
        }
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(px, 0, size, 0, 0, size, size);
        return bitmap;
    }

    // SLAB_AGSL straight copy
    private static final String SLAB_AGSL =
            "uniform float2 center;\nuniform float2 halfSize;\nuniform float radius;\nuniform float bandWidth;\n"
            + "uniform float gain;\nuniform float surface;\nlayout(color) uniform half4 tint;\n"
            + "half4 main(float2 xy) {\n"
            + "    float2 p = xy - center;\n"
            + "    float2 inner = halfSize - max(radius, bandWidth);\n"
            + "    if (abs(p.x) <= inner.x && abs(p.y) <= inner.y) return half4(tint.rgb * tint.a, tint.a);\n"
            + "    float2 q = abs(p) - halfSize + radius;\n"
            + "    float2 outside = max(q, 0.0);\n"
            + "    float outsideLength = length(outside);\n"
            + "    float d = outsideLength + min(max(q.x, q.y), 0.0) - radius;\n"
            + "    float u = clamp(1.0 + d / bandWidth, 0.0, 1.0);\n"
            + "    float shoulder = smoothstep(0.0, 1.0, u);\n"
            + "    if (shoulder == 0.0) return half4(tint.rgb * tint.a, tint.a);\n"
            + "    float2 edgeNormal = outsideLength > 0.001 ? outside / outsideLength\n"
            + "            : (q.x > q.y ? float2(1.0, 0.0) : float2(0.0, 1.0));\n"
            + "    edgeNormal *= sign(p);\n"
            + "    float3 n = float3(edgeNormal * u, sqrt(max(1.0 - u * u, 0.0)));\n"
            + "    float3 lightDir = normalize(float3(-0.45, -0.60, 0.80));\n"
            + "    float3 halfway = normalize(lightDir + float3(0.0, 0.0, 1.0));\n"
            + "    float reflection = pow(max(dot(n, halfway), 0.0), 18.0);\n"
            + "    float facing = dot(edgeNormal, normalize(float2(-0.45, -0.60)));\n"
            + "    float fresnel = u * u * u * u;\n"
            + "    float darkMode = 1.0 - surface;\n"
            + "    float highlight = gain * shoulder * (mix(0.12, 0.20, darkMode) * reflection\n"
            + "            + mix(0.06, 0.10, darkMode) * fresnel * max(facing, 0.0));\n"
            + "    float shade = gain * shoulder * mix(0.045, 0.13, darkMode) * max(-facing, 0.0);\n"
            + "    float clearK = mix(0.18, 0.55, darkMode);\n"
            + "    half a = tint.a * half(1.0 - clearK * shoulder * min(gain, 1.0));\n"
            + "    half4 col = half4(tint.rgb * a, a);\n"
            + "    half darkAlpha = half(clamp(shade, 0.0, 0.22));\n"
            + "    col = half4(col.rgb * (1.0 - darkAlpha), darkAlpha + col.a * (1.0 - darkAlpha));\n"
            + "    half lightAlpha = half(clamp(highlight, 0.0, 0.32));\n"
            + "    return half4(half3(lightAlpha) + col.rgb * (1.0 - lightAlpha),\n"
            + "                 lightAlpha + col.a * (1.0 - lightAlpha));\n"
            + "}\n";

    // ARTWORK_AGSL sidewall bend on the blurred backdrop
    private static final String LENS_AGSL =
            "uniform shader originalInput;\nuniform float2 halfSize;\nuniform float radius;\n"
            + "uniform float strength;\nuniform float bandWidth;\n"
            + "float2 bounded(float2 xy) { return clamp(xy, float2(0.5), halfSize * 2.0 - 0.5); }\n"
            + "half4 main(float2 xy) {\n"
            + "    float2 p = xy - halfSize;\n"
            + "    float2 inner = halfSize - max(radius, bandWidth);\n"
            + "    if (abs(p.x) <= inner.x && abs(p.y) <= inner.y) return originalInput.eval(xy);\n"
            + "    float2 q = abs(p) - halfSize + radius;\n"
            + "    float2 outside = max(q, 0.0);\n"
            + "    float outsideLength = length(outside);\n"
            + "    float d = outsideLength + min(max(q.x, q.y), 0.0) - radius;\n"
            + "    float u = clamp(1.0 + d / bandWidth, 0.0, 1.0);\n"
            + "    float rim = smoothstep(0.0, 1.0, u);\n"
            + "    if (rim <= 0.001) return originalInput.eval(xy);\n"
            + "    float2 normal = outsideLength > 0.001 ? outside / outsideLength\n"
            + "            : (q.x > q.y ? float2(1.0, 0.0) : float2(0.0, 1.0));\n"
            + "    normal *= sign(p);\n"
            + "    float bend = 0.6 * u / sqrt(max(1.0 - 0.64 * u * u, 0.36));\n"
            + "    half4 lens = originalInput.eval(bounded(xy + normal * bend * strength));\n"
            + "    return mix(originalInput.eval(xy), lens, half(rim));\n"
            + "}\n";
}
