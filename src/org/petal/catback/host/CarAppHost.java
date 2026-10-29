package org.petal.catback.host;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.location.Location;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import androidx.car.app.AppInfo;
import androidx.car.app.HandshakeInfo;
import androidx.car.app.IAppHost;
import androidx.car.app.IAppManager;
import androidx.car.app.ICarApp;
import androidx.car.app.ICarHost;
import androidx.car.app.IOnDoneCallback;
import androidx.car.app.ISurfaceCallback;
import androidx.car.app.OnDoneCallback;
import androidx.car.app.SurfaceContainer;
import androidx.car.app.constraints.IConstraintHost;
import androidx.car.app.model.Action;
import androidx.car.app.model.Template;
import androidx.car.app.model.TemplateWrapper;
import androidx.car.app.navigation.INavigationHost;
import androidx.car.app.navigation.model.Trip;
import androidx.car.app.serialization.Bundleable;
import androidx.car.app.serialization.BundlerException;
import androidx.car.app.suggestion.ISuggestionHost;
import androidx.car.app.versioning.CarAppApiLevels;

// we're the "car" for Car App Library apps (waze, osmand...). they pass HostValidator because we hold
// android.car.permission.TEMPLATE_RENDERER, which we define ourselves since nothing on a phone does
public final class CarAppHost {
    public static final String ACTION = "androidx.car.app.CarAppService";
    private static final String TAG = "Catback";

    public final ComponentName service;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread frames = new HandlerThread("Catback-CarAppFrames");
    private final Handler frameHandler;
    private final Runnable onChange;
    private ICarApp app;
    private IAppManager appManager;
    private ISurfaceCallback surfaceCallback;
    private FrameSink sink;
    private int dpi;
    private final Rect visible = new Rect();
    private boolean bound, created, closed;
    public volatile Template template;
    public volatile Trip trip;
    public volatile boolean navigating;
    // handshake said no (e.g. not uid 1000, or no automotive feature) — not coming back, caller should fall back
    public volatile boolean rejected;
    public volatile String status = "Starting";
    public volatile String toast;
    public volatile long toastAt;

    public CarAppHost(Context context, ComponentName service, Runnable onChange) {
        this.context = context.getApplicationContext(); this.service = service; this.onChange = onChange;
        frames.start(); frameHandler = new Handler(frames.getLooper());
    }

    public Bitmap frame() { FrameSink s = sink; return s == null ? null : s.frame(); }
    public boolean live() { return created && template != null; }

    public void start() {
        main.post(() -> {
            if (closed || bound) return;
            Intent intent = new Intent(ACTION).setComponent(service);
            try {
                bound = context.bindService(intent, connection,
                        Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT | Context.BIND_INCLUDE_CAPABILITIES);
            } catch (SecurityException e) { bound = false; }
            Log.i(TAG, "Car app bind " + service.flattenToShortString() + " -> " + bound);
            if (!bound) { status = "Couldn't reach " + service.getPackageName(); changed(); }
        });
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            Log.i(TAG, "Car app connected " + name.flattenToShortString());
            app = ICarApp.Stub.asInterface(binder);
            created = false;
            call(() -> app.getAppInfo(done("getAppInfo", result -> handshake((AppInfo) result))));
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            app = null; appManager = null; surfaceCallback = null; created = false; template = null;
            status = "App stopped"; changed();
        }
    };

    private void handshake(AppInfo info) {
        int level = Math.min(CarAppApiLevels.getLatest(), info.getLatestCarAppApiLevel());
        Log.i(TAG, "Car app api " + info.getMinCarAppApiLevel() + ".." + info.getLatestCarAppApiLevel() + ", we speak " + level);
        if (level < info.getMinCarAppApiLevel()) { status = "App needs a newer car host"; changed(); return; }
        call(() -> app.onHandshakeCompleted(Bundleable.create(new HandshakeInfo(context.getPackageName(), level)),
                done("handshake", ok -> create())));
    }

    private void create() {
        Intent intent = new Intent(ACTION).setComponent(service);
        call(() -> app.onAppCreate(carHost, intent, configuration(), done("onAppCreate", ok -> {
            created = true;
            Log.i(TAG, "Car app session created");
            call(() -> app.getManager("app", done("getManager", manager -> { appManager = (IAppManager) manager; refresh(); })));
            call(() -> app.onAppStart(done(null)));
            call(() -> app.onAppResume(done(null)));
            status = "Running"; changed();
        })));
    }

    // CarContext builds its own display context off this, so it must match the surface
    private Configuration configuration() {
        Configuration config = new Configuration(context.getResources().getConfiguration());
        FrameSink s = sink;
        int d = dpi > 0 ? dpi : 160, w = s != null ? s.width : 800, h = s != null ? s.height : 480;
        config.densityDpi = d;
        config.screenWidthDp = Math.round(w * 160f / d); config.screenHeightDp = Math.round(h * 160f / d);
        config.smallestScreenWidthDp = Math.min(config.screenWidthDp, config.screenHeightDp);
        config.orientation = w >= h ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT;
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_YES;
        return config;
    }

    private void refresh() {
        IAppManager m = appManager;
        if (m == null) return;
        call(() -> m.getTemplate(done("getTemplate", result -> {
            if (result instanceof TemplateWrapper) {
                template = ((TemplateWrapper) result).getTemplate(); changed();
                Log.i(TAG, "Car app template " + template.getClass().getSimpleName());
            }
        })));
    }

    // panel size in px. visible = the bit of the map our overlays don't cover, so the app centres the car there
    public void size(int w, int h, int densityDpi, Rect visibleArea) {
        main.post(() -> {
            if (closed || w < 16 || h < 16) return;
            boolean resize = sink == null || sink.width != w || sink.height != h || dpi != densityDpi;
            if (resize) {
                FrameSink old = sink;
                sink = new FrameSink(w, h, frameHandler); dpi = densityDpi;
                if (old != null) { sendDestroyed(old); old.close(); }
                sendSurface();
                if (created) call(() -> app.onConfigurationChanged(configuration(), done(null)));
            }
            if (!visible.equals(visibleArea) || resize) { visible.set(visibleArea); sendAreas(); }
        });
    }
    private void sendSurface() {
        ISurfaceCallback cb = surfaceCallback; FrameSink s = sink;
        if (cb == null || s == null) return;
        call(() -> cb.onSurfaceAvailable(Bundleable.create(new SurfaceContainer(s.surface(), s.width, s.height, dpi)), done(null)));
        sendAreas();
    }
    private void sendAreas() {
        ISurfaceCallback cb = surfaceCallback;
        if (cb == null || visible.isEmpty()) return;
        Rect area = new Rect(visible);
        call(() -> cb.onVisibleAreaChanged(area, done(null)));
        call(() -> cb.onStableAreaChanged(area, done(null)));
    }
    private void sendDestroyed(FrameSink s) {
        ISurfaceCallback cb = surfaceCallback;
        if (cb == null) return;
        call(() -> cb.onSurfaceDestroyed(Bundleable.create(new SurfaceContainer(s.surface(), s.width, s.height, dpi)), done(null)));
    }

    // map gestures, px on the surface
    public void click(float x, float y) { ISurfaceCallback cb = surfaceCallback; if (cb != null) call(() -> cb.onClick(x, y)); }
    public void scroll(float dx, float dy) { ISurfaceCallback cb = surfaceCallback; if (cb != null) call(() -> cb.onScroll(dx, dy)); }
    public void scale(float x, float y, float factor) { ISurfaceCallback cb = surfaceCallback; if (cb != null) call(() -> cb.onScale(x, y, factor)); }
    public void back() { IAppManager m = appManager; if (m != null) call(() -> m.onBackPressed(done(null))); }

    // buttons in the templates, back type goes through the app's own back handling
    public void press(Action action) {
        if (action == null) return;
        if (action.getType() == Action.TYPE_BACK) { back(); return; }
        if (action.getOnClickDelegate() != null) action.getOnClickDelegate().sendClick(new OnDoneCallback() { });
    }

    public void close() {
        main.post(() -> {
            if (closed) return;
            closed = true;
            ICarApp a = app;
            if (a != null && created) {
                call(() -> a.onAppPause(done(null)));
                call(() -> a.onAppStop(done(null)));
            }
            FrameSink s = sink; sink = null;
            if (s != null) { sendDestroyed(s); s.close(); }
            if (bound) try { context.unbindService(connection); } catch (IllegalArgumentException ignored) { }
            bound = false; app = null; appManager = null; surfaceCallback = null; template = null;
            frames.quitSafely();
        });
    }

    private void changed() { if (onChange != null) onChange.run(); }

    private interface RemoteCall { void run() throws RemoteException, BundlerException; }
    private void call(RemoteCall call) {
        try { call.run(); }
        catch (RemoteException | BundlerException | RuntimeException e) { Log.w(TAG, "Car app call failed: " + e); }
    }
    private interface Result { void accept(Object value); }
    // results land on a binder thread, bounce to main and unwrap the Bundleable there
    private IOnDoneCallback done(Result result) { return done("call", result); }
    private IOnDoneCallback done(String step, Result result) {
        return new IOnDoneCallback.Stub() {
            @Override public void onSuccess(Bundleable response) {
                if (result == null) return;
                main.post(() -> {
                    if (closed) return;
                    try { result.accept(response == null ? null : response.get()); }
                    catch (BundlerException | RuntimeException e) { Log.w(TAG, "Bad car app response: " + e); }
                });
            }
            // dig the app's real exception out so we can see what it actually hated
            @Override public void onFailure(Bundleable failure) {
                String why = "?";
                try {
                    Object f = failure == null ? null : failure.get();
                    if (f instanceof androidx.car.app.FailureResponse) {
                        androidx.car.app.FailureResponse fr = (androidx.car.app.FailureResponse) f;
                        why = "type " + fr.getErrorType() + ": " + fr.getStackTrace();
                    }
                } catch (BundlerException | RuntimeException e) { why = "unreadable: " + e; }
                Log.w(TAG, "Car app " + service.getPackageName() + " failed at " + step + " -> " + why);
                String firstLine = why.split("\n")[0];
                // a no at the handshake is the app's HostValidator saying never, not just-this-once — stop retrying it
                boolean giveUp = "handshake".equals(step);
                main.post(() -> {
                    status = service.getPackageName() + " refused at " + step + ": " + firstLine;
                    if (giveUp) rejected = true;
                    changed();
                });
            }
        };
    }

    private final IAppHost.Stub appHost = new IAppHost.Stub() {
        @Override public void invalidate() { main.post(CarAppHost.this::refresh); }
        @Override public void showToast(CharSequence text, int duration) {
            toast = text == null ? null : text.toString(); toastAt = SystemClock.elapsedRealtime(); changed();
        }
        @Override public void setSurfaceCallback(ISurfaceCallback callback) {
            main.post(() -> { surfaceCallback = callback; sendSurface(); });
        }
        @Override public void sendLocation(Location location) { }
        @Override public void showAlert(Bundleable alert) { }
        @Override public void dismissAlert(int id) { }
        @Override public Bundleable openMicrophone(Bundleable request) { return null; }
    };
    private final INavigationHost.Stub navigationHost = new INavigationHost.Stub() {
        @Override public void navigationStarted() { navigating = true; changed(); }
        @Override public void navigationEnded() { navigating = false; trip = null; changed(); }
        @Override public void updateTrip(Bundleable bundle) {
            try { trip = (Trip) bundle.get(); changed(); } catch (BundlerException | ClassCastException e) { Log.w(TAG, "Bad trip: " + e); }
        }
    };
    // same limits AA hands out on a normal screen
    private final IConstraintHost.Stub constraintHost = new IConstraintHost.Stub() {
        @Override public int getContentLimit(int type) { return type == 3 ? 3 : type == 4 ? 4 : 6; }
        @Override public boolean isAppDrivenRefreshEnabled() { return false; }
    };
    private final ISuggestionHost.Stub suggestionHost = new ISuggestionHost.Stub() {
        @Override public void updateSuggestions(Bundleable suggestions) { }
    };
    private final ICarHost.Stub carHost = new ICarHost.Stub() {
        @Override public void startCarApp(Intent intent) { Log.i(TAG, "Car app wants to start " + intent); }
        @Override public IBinder getHost(String type) {
            switch (type) {
                case "app": return appHost.asBinder();
                case "navigation": return navigationHost.asBinder();
                case "constraints": return constraintHost.asBinder();
                case "suggestion": return suggestionHost.asBinder();
                default: return null;
            }
        }
        @Override public void finish() { main.post(() -> { status = "App closed itself"; template = null; changed(); }); }
    };
}
