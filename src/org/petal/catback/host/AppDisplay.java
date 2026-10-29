package org.petal.catback.host;

import android.Manifest;
import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.hardware.input.InputManager;
import android.hardware.input.InputManagerGlobal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

// runs a normal phone app on its own hidden display so the car can show it, scrcpy --new-display style.
// needs the platform cert: trusted display + launching other apps onto it + injecting touches
public final class AppDisplay {
    private static final int FLAGS = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
            | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            | DisplayManager.VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH
            | DisplayManager.VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL
            | DisplayManager.VIRTUAL_DISPLAY_FLAG_TRUSTED
            | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP
            | DisplayManager.VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED // phone locked in your pocket, car still works
            | DisplayManager.VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED
            | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_FOCUS
            | DisplayManager.VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED; // don't yank focus off the phone screen

    // signature-level perms, only true on the platform-signed rom build. sideloaded copies can never get these,
    // not even rooted — it's a cert match, not a grantable permission, so don't bother trying without it
    public static boolean available(Context context) {
        return context.checkSelfPermission(Manifest.permission.ADD_TRUSTED_DISPLAY) == PackageManager.PERMISSION_GRANTED
                && context.checkSelfPermission(Manifest.permission.INJECT_EVENTS) == PackageManager.PERMISSION_GRANTED;
    }

    public final String pkg;
    private final Context context;
    private final HandlerThread thread;
    private final Handler handler;
    private VirtualDisplay display;
    private volatile FrameSink sink;
    private int dpi;
    private long downTime;
    private boolean launched, closed;

    public AppDisplay(Context context, String pkg) {
        this.context = context.getApplicationContext(); this.pkg = pkg;
        thread = new HandlerThread("Catback-AppDisplay-" + pkg); thread.start();
        handler = new Handler(thread.getLooper());
    }

    public Bitmap frame() { FrameSink s = sink; return s == null ? null : s.frame(); }
    public boolean sized(int w, int h) { FrameSink s = sink; return s != null && s.width == w && s.height == h; }

    // first call makes the display + launches, later ones resize it. off the render thread, both are binder heavy
    public void size(int w, int h, int densityDpi) { handler.post(() -> resize(w, h, densityDpi)); }
    private synchronized void resize(int w, int h, int densityDpi) {
        if (closed || w < 16 || h < 16 || (sized(w, h) && dpi == densityDpi)) return;
        FrameSink old = sink, next = new FrameSink(w, h, handler);
        sink = next; dpi = densityDpi;
        try {
            if (display == null) {
                display = context.getSystemService(DisplayManager.class)
                        .createVirtualDisplay("CATBACK " + pkg, w, h, densityDpi, next.surface(), FLAGS);
            } else {
                display.setSurface(next.surface());
                display.resize(w, h, densityDpi);
            }
        } catch (RuntimeException e) {
            Log.w("Catback", "Virtual display for " + pkg + " failed", e);
            next.close(); sink = old; return;
        }
        if (old != null) old.close();
        if (!launched) launch();
    }

    private void launch() {
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(pkg);
        if (intent == null) { Log.w("Catback", pkg + " has no launcher activity"); return; }
        // this display is fresh every time, but w/o MULTIPLE_TASK android just brings an old task from a
        // previous open back to front wherever IT lives, never attaching anything here — looks "stuck".
        // excluded from recents too: it lives in the car window, the phone's own recents shouldn't know
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        ActivityOptions options = ActivityOptions.makeBasic().setLaunchDisplayId(display.getDisplay().getDisplayId());
        try { context.startActivity(intent, options.toBundle()); launched = true; }
        catch (RuntimeException e) { Log.w("Catback", "Couldn't launch " + pkg + " on the car display", e); }
    }

    // x/y in display pixels, 0 down 1 up 2 move like AAP
    public void touch(float x, float y, int action) {
        VirtualDisplay d = display;
        if (d == null) return;
        long now = SystemClock.uptimeMillis();
        int motion = action == 0 ? MotionEvent.ACTION_DOWN : action == 1 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
        if (action == 0) downTime = now;
        MotionEvent event = MotionEvent.obtain(downTime, now, motion, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        event.setDisplayId(d.getDisplay().getDisplayId());
        inject(event);
        event.recycle();
    }
    public void back() {
        VirtualDisplay d = display;
        if (d == null) return;
        long now = SystemClock.uptimeMillis();
        for (int action : new int[]{KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
            KeyEvent key = new KeyEvent(now, now, action, KeyEvent.KEYCODE_BACK, 0, 0,
                    KeyEvent.KEYCODE_UNKNOWN, 0, 0, InputDevice.SOURCE_KEYBOARD);
            key.setDisplayId(d.getDisplay().getDisplayId());
            inject(key);
        }
    }
    private static void inject(android.view.InputEvent event) {
        try { InputManagerGlobal.getInstance().injectInputEvent(event, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC); }
        catch (RuntimeException e) { Log.w("Catback", "Input injection refused", e); }
    }

    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (display != null) display.release(); // DESTROY_CONTENT_ON_REMOVAL ends its activity
        display = null;
        FrameSink s = sink; sink = null;
        if (s != null) s.close();
        thread.quitSafely();
        if (launched) {
            // activity gone isn't the app gone — process can sit cached, services/alarms keep running.
            // same as hitting "force stop" in settings, so closing the car window really means closed
            try { context.getSystemService(ActivityManager.class).forceStopPackage(pkg); }
            catch (RuntimeException e) { Log.w("Catback", "Couldn't force-stop " + pkg, e); }
        }
    }
}
