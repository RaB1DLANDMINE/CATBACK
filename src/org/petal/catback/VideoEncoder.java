package org.petal.catback;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.view.Surface;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import org.petal.catback.protocol.ProjectionSession;
import org.petal.catback.ui.CarUi;

// hw avc encoder, draws straight into the surface w/ a hw canvas (agsl + blur need it), sw bitmap if that dies
public final class VideoEncoder implements Closeable {
    private final CarUi ui;
    private final ProjectionSession session;
    private final ProjectionSession.VideoConfig config;
    private volatile boolean stopped;
    private final Thread worker;

    public VideoEncoder(CarUi ui, ProjectionSession session, ProjectionSession.VideoConfig config) {
        this.ui = ui; this.session = session; this.config = config;
        worker = new Thread(this::run, "Catback-Video"); worker.start();
    }
    private void run() {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY);
        ui.invalidate();
        MediaCodec codec = null;
        Surface input = null;
        GlSurface gl = null;
        Bitmap bitmap = null;
        try {
            MediaFormat format = MediaFormat.createVideoFormat("video/avc", config.width, config.height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            // bitrate for 30fps and kept at 60, ui barely moves so no point doubling car flow control load
            int bitrate = (int) Math.min(8_000_000L, Math.max(2_000_000L, config.width * (long) config.height * Math.min(config.fps, 30) / 5));
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            format.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
            format.setInteger(MediaFormat.KEY_PRIORITY, 0);                      // realtime
            format.setInteger(MediaFormat.KEY_OPERATING_RATE, config.fps);
            if (android.os.Build.VERSION.SDK_INT >= 30) format.setInteger(MediaFormat.KEY_LATENCY, 1);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, config.fps);
            // rare keyframes + intra refresh, no big burst every second choking the link
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 4);
            if (android.os.Build.VERSION.SDK_INT >= 30) format.setInteger(MediaFormat.KEY_INTRA_REFRESH_PERIOD, config.fps);
            format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
            format.setInteger(MediaFormat.KEY_LEVEL, config.width > 1280 ? MediaCodecInfo.CodecProfileLevel.AVCLevel41 : MediaCodecInfo.CodecProfileLevel.AVCLevel31);
            codec = MediaCodec.createEncoderByType("video/avc");
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            input = codec.createInputSurface();
            codec.start();
            boolean hardware = true;
            Canvas canvas = null;
            try { canvas = input.lockHardwareCanvas(); }
            catch (RuntimeException unsupported) { hardware = false; }
            if (!hardware) {
                gl = new GlSurface(input, config.width, config.height);
                bitmap = Bitmap.createBitmap(config.width, config.height, Bitmap.Config.ARGB_8888);
                canvas = new Canvas(bitmap);
            }
            // sending blocks on car flow control, not on the render thread thanks
            final MediaCodec encoder = codec;
            drainer = new Thread(() -> {
                MediaCodec.BufferInfo out = new MediaCodec.BufferInfo();
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY);
                try { while (!stopped) drain(encoder, out, 20_000); }
                catch (Exception error) { if (!stopped) session.fail(error); }
            }, "Catback-VideoSend");
            drainer.start();
            long interval = 1_000_000_000L / config.fps, next = System.nanoTime();
            long statStart = next; int statFrames = 0, late = 0; long statWorst = 0, statTotal = 0;
            long worstLock = 0, worstDraw = 0, worstPost = 0, worstDrain = 0;
            while (!stopped) {
                long frameStart = System.nanoTime();
                if (hardware && canvas == null) canvas = input.lockHardwareCanvas();
                long t1 = System.nanoTime();
                ui.draw(canvas, config.contentLeft, config.contentTop, config.contentWidth, config.contentHeight,
                        config.layoutWidth);
                long t2 = System.nanoTime();
                if (hardware) { input.unlockCanvasAndPost(canvas); canvas = null; }
                else gl.draw(bitmap, System.nanoTime());
                long t3 = System.nanoTime();
                long t4 = t3;
                worstLock = Math.max(worstLock, t1 - frameStart); worstDraw = Math.max(worstDraw, t2 - t1);
                worstPost = Math.max(worstPost, t3 - t2); worstDrain = Math.max(worstDrain, t4 - t3);
                long spent = System.nanoTime() - frameStart;
                statFrames++; statTotal += spent; statWorst = Math.max(statWorst, spent);
                next += interval;
                long delay = next - System.nanoTime();
                if (delay > 0) Thread.sleep(delay / 1_000_000, (int) (delay % 1_000_000));
                else { late++; if (delay < -2 * interval) next = System.nanoTime(); } // drop only proper stalls
                long now = System.nanoTime();
                if (now - statStart >= 5_000_000_000L) {
                    android.util.Log.i("Catback", String.format(java.util.Locale.ROOT,
                            "Render: %.1f fps (target %d), frame avg %.1f ms worst %.1f ms, late frames %d | worst lock %.0f draw %.0f post %.0f ms, send %.0f ms",
                            statFrames * 1e9 / (now - statStart), config.fps, statTotal / 1e6 / statFrames, statWorst / 1e6, late,
                            worstLock / 1e6, worstDraw / 1e6, worstPost / 1e6, sendWorstNs / 1e6));
                    sendWorstNs = 0;
                    statStart = now; statFrames = 0; late = 0; statWorst = 0; statTotal = 0;
                    worstLock = worstDraw = worstPost = worstDrain = 0;
                }
            }
        } catch (Exception error) {
            if (!stopped) session.fail(error);
        } finally {
            stopped = true;
            Thread sender = drainer;
            if (sender != null) { sender.interrupt(); try { sender.join(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); } }
            if (codec != null) {
                try { codec.stop(); } catch (RuntimeException ignored) { }
                codec.release();
            }
            if (gl != null) gl.close();
            if (input != null) input.release();
            if (bitmap != null) bitmap.recycle();
        }
    }
    private volatile Thread drainer;
    private volatile long sendWorstNs;
    private void drain(MediaCodec codec, MediaCodec.BufferInfo info, long timeoutUs) throws IOException {
        for (int count = 0; count < 20; count++) {
            int index = codec.dequeueOutputBuffer(info, count == 0 ? timeoutUs : 0);
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return;
            if (index < 0) continue;
            try {
                ByteBuffer buffer = codec.getOutputBuffer(index);
                if (buffer != null && info.size > 0) {
                    buffer.position(info.offset); buffer.limit(info.offset + info.size);
                    byte[] data = new byte[info.size]; buffer.get(data);
                    long sendStart = System.nanoTime();
                    if (!stopped) session.video(data, info.presentationTimeUs, (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0);
                    sendWorstNs = Math.max(sendWorstNs, System.nanoTime() - sendStart);
                }
            } finally { codec.releaseOutputBuffer(index, false); }
        }
    }
    @Override public void close() { stopped = true; worker.interrupt(); Thread d = drainer; if (d != null) d.interrupt(); }

    private static final class GlSurface implements Closeable {
        final EGLDisplay display;
        final EGLContext context;
        final EGLSurface surface;
        final int program, texture, width, height;
        final FloatBuffer vertices;
        boolean uploaded;
        GlSurface(Surface input, int width, int height) throws IOException {
            this.width = width; this.height = height;
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] versions = new int[2];
            if (!EGL14.eglInitialize(display, versions, 0, versions, 1)) throw new IOException("EGL initialization failed");
            int[] attrs = {EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,
                    EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,
                    0x3142,1,EGL14.EGL_NONE}; // EGL_RECORDABLE_ANDROID
            EGLConfig[] configs = new EGLConfig[1]; int[] count = new int[1];
            if (!EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) || count[0] == 0)
                throw new IOException("No recordable EGL config");
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE}, 0);
            surface = EGL14.eglCreateWindowSurface(display, configs[0], input, new int[]{EGL14.EGL_NONE}, 0);
            if (!EGL14.eglMakeCurrent(display, surface, surface, context)) throw new IOException("EGL make-current failed");
            int vertex = shader(GLES20.GL_VERTEX_SHADER,
                    "attribute vec2 position; attribute vec2 uv; varying vec2 tex; void main(){gl_Position=vec4(position,0.,1.);tex=uv;}");
            int fragment = shader(GLES20.GL_FRAGMENT_SHADER,
                    "precision mediump float; varying vec2 tex; uniform sampler2D image; void main(){gl_FragColor=texture2D(image,tex);}");
            program = GLES20.glCreateProgram(); GLES20.glAttachShader(program, vertex); GLES20.glAttachShader(program, fragment);
            GLES20.glLinkProgram(program);
            int[] linked = new int[1]; GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0);
            if (linked[0] == 0) throw new IOException("GL program link: " + GLES20.glGetProgramInfoLog(program));
            GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment);
            int[] ids = new int[1]; GLES20.glGenTextures(1, ids, 0); texture = ids[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            vertices = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            vertices.put(new float[]{-1,-1,0,1, 1,-1,1,1, -1,1,0,0, 1,1,1,0}).position(0);
        }
        void draw(Bitmap image, long timeNs) throws IOException {
            GLES20.glViewport(0, 0, width, height); GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            if (!uploaded) { GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, image, 0); uploaded = true; }
            else GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, image);
            int pos = GLES20.glGetAttribLocation(program, "position"), uv = GLES20.glGetAttribLocation(program, "uv");
            vertices.position(0); GLES20.glVertexAttribPointer(pos, 2, GLES20.GL_FLOAT, false, 16, vertices);
            vertices.position(2); GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 16, vertices);
            GLES20.glEnableVertexAttribArray(pos); GLES20.glEnableVertexAttribArray(uv);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "image"), 0);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            EGLExt.eglPresentationTimeANDROID(display, surface, timeNs);
            if (!EGL14.eglSwapBuffers(display, surface)) throw new IOException("EGL buffer swap failed");
        }
        static int shader(int type, String source) throws IOException {
            int shader = GLES20.glCreateShader(type); GLES20.glShaderSource(shader, source); GLES20.glCompileShader(shader);
            int[] ok = new int[1]; GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0);
            if (ok[0] == 0) throw new IOException(GLES20.glGetShaderInfoLog(shader));
            return shader;
        }
        @Override public void close() {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroySurface(display, surface); EGL14.eglDestroyContext(display, context);
            EGL14.eglReleaseThread(); EGL14.eglTerminate(display);
        }
    }
}
