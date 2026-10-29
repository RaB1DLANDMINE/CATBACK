package org.petal.catback.host;

import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.graphics.PixelFormat;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.view.Surface;

// someone else draws into surface(), we hand the encoder a gpu bitmap of the newest frame
final class FrameSink implements ImageReader.OnImageAvailableListener {
    final int width, height;
    private final ImageReader reader;
    // keep the last two open, closing the one on screen hands its buffer back and it tears
    private final Image[] held = new Image[2];
    private volatile Bitmap frame;
    private volatile boolean closed;

    FrameSink(int width, int height, Handler handler) {
        this.width = width; this.height = height;
        reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 4,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT);
        reader.setOnImageAvailableListener(this, handler);
    }
    Surface surface() { return reader.getSurface(); }
    Bitmap frame() { return frame; }

    @Override public void onImageAvailable(ImageReader r) {
        if (closed) return;
        Image image;
        try { image = r.acquireLatestImage(); } catch (IllegalStateException full) { return; }
        if (image == null) return;
        HardwareBuffer buffer = image.getHardwareBuffer();
        Bitmap bitmap = buffer == null ? null : Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB));
        if (buffer != null) buffer.close();
        if (bitmap == null) { image.close(); return; }
        synchronized (held) {
            if (closed) { image.close(); return; }
            if (held[1] != null) held[1].close();
            held[1] = held[0]; held[0] = image;
            frame = bitmap;
        }
    }

    void close() {
        synchronized (held) {
            closed = true;
            for (int i = 0; i < held.length; i++) if (held[i] != null) { held[i].close(); held[i] = null; }
            frame = null;
        }
        reader.close();
    }
}
