package org.petal.catback.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.BatteryManager;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import org.petal.catback.R;
import org.petal.catback.car.CarData;
import org.petal.catback.host.AppDisplay;
import org.petal.catback.host.CarAppHost;

// the car ui, ported from design/car-ui/index.html. 480 tall x visible width, silk panels, glass only on the island.
// touches hit-test against last frame's regions
public final class CarUi {
    private static CarUi instance;
    public static synchronized CarUi get(Context context) {
        if (instance == null) instance = new CarUi(context.getApplicationContext());
        return instance;
    }

    // dark roles, accent ~ system_accent1_200
    // 50% surface over frosted wall then silk
    static final int PANEL = 0x80191B23, SHEET = 0xCC1D1F28;
    static final int ACCENT = 0xFFB9A5FF, ACCENT_CONTAINER = 0xFF3B3156,
            TEXT = 0xFFF5F5F8, TEXT2 = 0xFFB9BAC4, ON_ACCENT = 0xFF17112E, CHIP = 0x14FFFFFF;
    private static final float H = 480, BAR_TOP = 52, BAR_BOTTOM = 460;
    private static final float[] OMEGA = {5.1f, 6.3f, 4.4f, 7.0f, 5.7f, 6.9f}; // IslandConstants.BAR_OMEGA

    private final Context context;
    private final CarData data;
    final Typeface regular, medium, semibold, bold;
    final TextPaint measure = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG),
            image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Silk navSilk = new Silk(), mediaSilk = new Silk(), messageSilk = new Silk(),
            sheetSilk = new Silk(), drawerSilk = new Silk();
    private final IslandGlass glass = new IslandGlass();
    private final Backdrop backdrop;
    private final Island island = new Island();
    private final RenderNode scene = new RenderNode("car-scene");
    private final RectF r = new RectF(), r2 = new RectF();
    private final Matrix matrix = new Matrix();
    private final long start = SystemClock.elapsedRealtime();
    private final List<Hit> hits = new ArrayList<>(), pending = new ArrayList<>(), sceneHits = new ArrayList<>();
    // only re-record the static scene when it actually changes, eq + island draw on top
    private long sceneSignature = Long.MIN_VALUE;
    private int sceneVersion;
    private float eqX, eqY, eqH; private boolean eqVisible, eqPlaying;
    private int frameCanvasW, frameCanvasH, frameLeft, frameTop; private float frameSx = 1, frameSy = 1;
    private final GlassBaker baker = new GlassBaker();
    // island glass but lighter tint, 50% see-through
    private static final int PANEL_GLASS = 0x8C14161E;
    private int batteryLevel = -1; private long batteryAt = -100000;
    private final java.util.HashMap<Integer, Drawable> icons = new java.util.HashMap<>();
    private final java.util.HashMap<Bitmap, BitmapShader> shaders = new java.util.HashMap<>();
    private String picking; // null, MAPS or MEDIA
    private float width = 800;
    // drawer: swipe right opens, left/back/scrim closes. 0 shut 1 open
    private static final float SLOP = 24, SWIPE = 80, TILE_W = 132, TILE_H = 150, TILE_GAP = 12;
    private boolean drawerOpen, dragging, scrolling;
    private float drawer, drawerVel, drawerScroll, drawerMaxScroll, downX, downY, downScroll, lastX;
    private long drawerAt = SystemClock.elapsedRealtime();
    private final RectF drawerRow = new RectF();
    // live bits, only while a car's actually connected: nav app in the maps panel, phone app in a floating window
    private volatile boolean session;
    private CarAppHost mapHost;
    private AppDisplay mapDisplay;
    private String mapSource;
    private CarAppPanel carPanel;
    private final RectF mapsBox = new RectF();
    private int mapW, mapH, mapDpi, winW, winH, winDpi;
    private AppDisplay window;
    private CarData.App windowApp;
    private boolean windowOpen, windowFull;
    private float windowAnim, windowVel, fullAnim, fullVel;
    private long windowAt = SystemClock.elapsedRealtime();
    private final RectF windowRect = new RectF(), windowContent = new RectF(), windowTarget = new RectF();
    // 0 nothing, 1 map, 2 app window, 3 car app list scroll
    private int gesture;
    private boolean gestureMoved;
    private float gestureX, gestureY, tapX, tapY;
    // messages carousel, index never wraps so looping round just keeps sliding the same way
    private static final long CAROUSEL_MS = 420, CAROUSEL_AUTO_MS = 6000, CAROUSEL_HOLD_MS = 12000;
    private final RectF msgBox = new RectF();
    private int carouselIndex;
    private float carouselFrom;
    private long carouselAt = SystemClock.elapsedRealtime(), carouselTouchedAt = -100000;

    private static final class Hit {
        final RectF rect, visual; final Runnable action;
        Hit(RectF rect, RectF visual, Runnable action) {
            this.rect = new RectF(rect); this.visual = visual == null ? null : new RectF(visual); this.action = action;
        }
    }
    // tapped thing squashes and boings back, damped spring
    private static final float WOBBLE_SECONDS = .55f;
    private final RectF wobbling = new RectF();
    private long wobbleAt = -100000;
    // sheet waits a beat after a pick so you see the wobble, 0 = nothing
    private long closeSheetsAt;
    private void closeSheetsSoon() { closeSheetsAt = SystemClock.elapsedRealtime() + 260; }

    // only true on the platform-signed rom build; never changes at runtime so check it once
    private final boolean displayHost;
    // CAL apps that said no at the handshake, don't keep knocking — fall back for these from here on
    private final java.util.Set<String> calRejected = new java.util.HashSet<>();

    private CarUi(Context context) {
        this.context = context;
        data = CarData.get(context);
        displayHost = AppDisplay.available(context);

        backdrop = new Backdrop(context);
        regular = font(400); medium = font(500); semibold = font(600); bold = font(700);
        island.measure = (string, size, style) -> {
            text.setTypeface(style == 2 ? bold : style == 1 ? semibold : regular); text.setTextSize(size);
            return text.measureText(string);
        };
    }
    private Typeface font(int weight) {
        try {
            return new Typeface.Builder(context.getAssets(), "fonts/Commissioner-VF.ttf")
                    .setFontVariationSettings("'wght' " + weight).setWeight(weight).build();
        } catch (RuntimeException e) { return Typeface.create("sans-serif", weight >= 600 ? Typeface.BOLD : Typeface.NORMAL); }
    }

    // input
    // layout units, AAP 0 down 1 up 2 move. tap fires on release unless the finger wandered (swipe/drag)
    public synchronized void touch(float x, float y, int action) {
        if (action == 0) {
            gesture = 0; gestureMoved = false; gestureX = x; gestureY = y;
            boolean free = !drawerOpen && picking == null;
            if (free && windowOpen && window != null && windowContent.contains(x, y)) { gesture = 2; windowTouch(x, y, 0); return; }
            boolean inMaps = free && !(windowOpen && windowRect.contains(x, y)) && mapsBox.contains(x, y);
            if (inMaps && mapDisplay != null) { gesture = 1; mapTouch(x, y, 0); return; }
            if (inMaps && mapHost != null) gesture = carPanel != null && carPanel.listBox.contains(x, y) ? 3 : 1;
            downX = lastX = x; downY = y; downScroll = drawerScroll; dragging = true;
            scrolling = drawerOpen && drawerRow.contains(x, y);
            return;
        }
        if (action == 2) {
            if (gesture == 2) { windowTouch(x, y, 2); return; }
            if (gesture == 1 && mapDisplay != null) { mapTouch(x, y, 2); return; }
            if ((gesture == 1 || gesture == 3) && (gestureMoved || Math.abs(x - downX) > SLOP || Math.abs(y - downY) > SLOP)) {
                if (gesture == 1 && mapHost != null) mapHost.scroll((gestureX - x) * frameSx, (gestureY - y) * frameSy);
                else if (gesture == 3 && carPanel != null) carPanel.listScroll += gestureY - y;
                gestureX = x; gestureY = y; gestureMoved = true;
                return;
            }
            if (dragging) { lastX = x; if (scrolling) scrollDrawer(downScroll - (x - downX)); }
            return;
        }
        if (action != 1) return;
        int g = gesture; gesture = 0;
        if (g == 2) { windowTouch(x, y, 1); return; }
        if (g == 1 && mapDisplay != null) { mapTouch(x, y, 1); return; }
        if ((g == 1 || g == 3) && (gestureMoved || Math.abs(x - downX) > SLOP || Math.abs(y - downY) > SLOP)) {
            // no move events from this car, do the whole drag on lift
            if (!gestureMoved && g == 1 && mapHost != null) mapHost.scroll((downX - x) * frameSx, (downY - y) * frameSy);
            else if (!gestureMoved && g == 3 && carPanel != null) carPanel.listScroll += downY - y;
            dragging = false;
            return;
        }
        tapX = x; tapY = y;
        boolean tracked = dragging; dragging = false;
        float dx = tracked ? x - downX : 0, dy = tracked ? y - downY : 0;
        if (Math.abs(dx) > SLOP || Math.abs(dy) > SLOP) {
            // some cars never send moves, use the lift spot
            if (scrolling) scrollDrawer(downScroll - dx);
            else if (!drawerOpen && picking == null && msgBox.contains(downX, downY) && Math.abs(dy) > 1.2f * Math.abs(dx))
                stepCarousel(dy < 0 ? 1 : -1, true);
            else if (Math.abs(dx) > SWIPE && Math.abs(dx) > 1.5f * Math.abs(dy) && picking == null && !mapsBox.contains(downX, downY)) {
                if (dx > 0 && !drawerOpen) openDrawer(); else if (dx < 0 && drawerOpen) drawerOpen = false;
            }
            scrolling = false;
            return;
        }
        scrolling = false;
        for (int i = hits.size() - 1; i >= 0; i--) {
            Hit hit = hits.get(i);
            if (hit.rect.contains(x, y)) {
                if (hit.visual != null) { wobbling.set(hit.visual); wobbleAt = SystemClock.elapsedRealtime(); }
                if (hit.action != null) hit.action.run();
                return;
            }
        }
    }
    private float carouselPos() {
        float t = Math.min(1, (SystemClock.elapsedRealtime() - carouselAt) / (float) CAROUSEL_MS);
        float e = t < .5f ? 4 * t * t * t : 1 - (float) Math.pow(-2 * t + 2, 3) / 2; // ease in out cubic
        return carouselFrom + (carouselIndex - carouselFrom) * e;
    }
    private boolean carouselMoving() { return SystemClock.elapsedRealtime() - carouselAt < CAROUSEL_MS + 20; }
    private void stepCarousel(int by, boolean manual) {
        if (data.showing.size() < 2) return;
        carouselFrom = carouselPos(); carouselIndex += by; carouselAt = SystemClock.elapsedRealtime();
        if (manual) carouselTouchedAt = carouselAt;
    }
    private void windowTouch(float x, float y, int action) {
        if (window == null || windowContent.width() < 1) return;
        window.touch(clamp((x - windowContent.left) / windowContent.width()) * winW, clamp((y - windowContent.top) / windowContent.height()) * winH, action);
    }
    private void mapTouch(float x, float y, int action) {
        if (mapDisplay == null || mapsBox.width() < 1) return;
        mapDisplay.touch(clamp((x - mapsBox.left) / mapsBox.width()) * mapW, clamp((y - mapsBox.top) / mapsBox.height()) * mapH, action);
    }
    private static float clamp(float v) { return Math.max(0, Math.min(1, v)); }
    private void mapClick() {
        if (mapHost != null) mapHost.click((tapX - mapsBox.left) * frameSx, (tapY - mapsBox.top) * frameSy);
    }

    // projection service flips this, nothing live runs in the phone-side preview
    public synchronized void session(boolean on) {
        session = on;
        if (!on) {
            closeMaps();
            if (window != null) window.close();
            window = null; windowApp = null; windowOpen = false; windowAnim = 0; windowVel = 0;
        }
    }
    private void closeMaps() {
        if (mapHost != null) mapHost.close();
        if (mapDisplay != null) mapDisplay.close();
        mapHost = null; mapDisplay = null; mapSource = null; mapW = mapH = mapDpi = 0;
    }
    private boolean liveMaps() { return mapWant(session ? data.chosenApp(CarData.MAPS) : null) != null; }
    // car app lib first if the app has that and hasn't already said no; else the phone screen, rom build only.
    // no display-host capability and no (or rejected) CAL = nothing live, panel falls back to the notif turn card
    private String mapWant(CarData.App app) {
        if (app == null) return null;
        if (app.carService != null && !calRejected.contains(app.pkg)) return app.pkg + "#car";
        return displayHost ? app.pkg + "#display" : null;
    }

    private void openWindow(CarData.App app) {
        if (!session || !displayHost) return; // sideloaded build has no capability for this, tap does nothing
        if (window != null && !app.pkg.equals(window.pkg)) { window.close(); window = null; }
        if (window == null) { window = new AppDisplay(context, app.pkg); winW = winH = winDpi = 0; }
        windowApp = app; windowOpen = true; island.dismiss();
    }
    private void openDrawer() { data.refreshAppsSoon(); drawerOpen = true; drawerScroll = 0; island.dismiss(); }
    private void scrollDrawer(float to) { drawerScroll = Math.max(0, Math.min(drawerMaxScroll, to)); }
    public void key(int code) {
        if (code == 85 || code == 126 || code == 127) data.mediaAction(0);
        else if (code == 87) data.mediaAction(1);
        else if (code == 88) data.mediaAction(-1);
        else if (code == 4 && windowOpen && window != null && picking == null && !drawerOpen) window.back();
        else if (code == 3 || code == 4) synchronized (this) { picking = null; drawerOpen = false; windowOpen = false; island.dismiss(); }
    }
    void hit(RectF rect, Runnable action) { pending.add(new Hit(rect, null, action)); }
    // tap target, visual wobbles, null action = just the boing
    void hit(RectF rect, RectF visual, Runnable action) { pending.add(new Hit(rect, visual, action)); }
    private float wobbleTime() { return (SystemClock.elapsedRealtime() - wobbleAt) / 1000f; }
    // scales around box if it's the wobbling one, restoreToCount after
    private int wobble(Canvas c, RectF box) {
        int saved = c.save();
        float t = wobbleTime();
        if (t < WOBBLE_SECONDS && near(box, wobbling)) {
            // big stuff moves less, buttons bounce
            float amplitude = Math.max(.025f, Math.min(.09f, 9f / Math.max(box.width(), box.height())));
            float k = 1 - amplitude * (float) (Math.exp(-t * 7) * Math.cos(t * 26));
            c.scale(k, k, box.centerX(), box.centerY());
        }
        return saved;
    }
    private static boolean near(RectF a, RectF b) {
        return Math.abs(a.left - b.left) < .5f && Math.abs(a.top - b.top) < .5f
                && Math.abs(a.right - b.right) < .5f && Math.abs(a.bottom - b.bottom) < .5f;
    }

    // frame
    // ui in the visible rect, car margins black
    public synchronized void draw(Canvas canvas, int left, int top, int w, int h, float layoutWidth) {
        width = layoutWidth;
        if (closeSheetsAt != 0 && SystemClock.elapsedRealtime() >= closeSheetsAt) { closeSheetsAt = 0; picking = null; drawerOpen = false; }
        float sx = w / layoutWidth, sy = h / H, now = (SystemClock.elapsedRealtime() - start) / 1000f;
        island.step(data, now);
        long nowMs = SystemClock.elapsedRealtime();
        if (picking == null && !drawerOpen && nowMs - carouselAt > CAROUSEL_AUTO_MS && nowMs - carouselTouchedAt > CAROUSEL_HOLD_MS)
            stepCarousel(1, false);
        canvas.drawColor(Color.BLACK);
        boolean hardware = canvas.isHardwareAccelerated();
        pending.clear();
        frameCanvasW = canvas.getWidth(); frameCanvasH = canvas.getHeight(); frameLeft = left; frameTop = top; frameSx = sx; frameSy = sy;
        if (!hardware) eqVisible = false;
        if (hardware) {
            long signature = signature(canvas, left, top, w, h, layoutWidth);
            if (signature != sceneSignature) {
                sceneSignature = signature; sceneVersion++;
                eqVisible = false; // media() re-adds the eq while recording
                scene.setPosition(0, 0, canvas.getWidth(), canvas.getHeight());
                Canvas record = scene.beginRecording(canvas.getWidth(), canvas.getHeight());
                try { layoutSpace(record, left, top, w, h, sx, sy); drawScene(record, now); }
                finally { scene.endRecording(); }
                sceneHits.clear(); sceneHits.addAll(pending);
            } else { pending.addAll(sceneHits); }
            canvas.drawRenderNode(scene);
        }
        int saved = canvas.save();
        layoutSpace(canvas, left, top, w, h, sx, sy);
        if (!hardware) drawScene(canvas, now);
        drawLiveMaps(canvas);
        if (eqVisible) equalizer(canvas, eqX, eqY, eqH, eqPlaying, now);
        drawWindow(canvas);
        drawIsland(canvas, hardware ? scene : null, left, top, sx, sy, now);
        drawDrawer(canvas);
        if (picking != null) drawPicker(canvas);
        canvas.restoreToCount(saved);
        hits.clear(); hits.addAll(pending);
    }
    // what changes the static scene, cheap and no allocs
    private long signature(Canvas canvas, int left, int top, int w, int h, float layoutWidth) {
        CarData.Media media = data.media; CarData.Navigation nav = data.navigation; CarData.Signal message = data.latestMessage;
        long minute = System.currentTimeMillis() / 60000;
        if (SystemClock.elapsedRealtime() - batteryAt > 30000) {
            batteryAt = SystemClock.elapsedRealtime();
            batteryLevel = context.getSystemService(BatteryManager.class).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        }
        long h1 = 17;
        h1 = h1 * 31 + canvas.getWidth(); h1 = h1 * 31 + canvas.getHeight(); h1 = h1 * 31 + left; h1 = h1 * 31 + top;
        h1 = h1 * 31 + w; h1 = h1 * 31 + h; h1 = h1 * 31 + Float.floatToIntBits(layoutWidth);
        h1 = h1 * 31 + minute; h1 = h1 * 31 + batteryLevel;
        h1 = h1 * 31 + System.identityHashCode(media); h1 = h1 * 31 + System.identityHashCode(nav);
        h1 = h1 * 31 + System.identityHashCode(message);
        h1 = h1 * 31 + System.identityHashCode(data.showing); h1 = h1 * 31 + carouselIndex;
        if (carouselMoving()) h1 = h1 * 31 + SystemClock.elapsedRealtime();
        h1 = h1 * 31 + (media != null ? media.positionNow() / 1000 : -1);
        h1 = h1 * 31 + String.valueOf(data.chosen(CarData.MAPS)).hashCode(); h1 = h1 * 31 + String.valueOf(data.chosen(CarData.MEDIA)).hashCode();
        h1 = h1 * 31 + (data.listenerConnected ? 1 : 0) + (backdrop.showingWallpaper() ? 2 : 0) + (liveMaps() ? 4 : 0);
        h1 = h1 * 31 + baker.version(); h1 = h1 * 31 + backdrop.version();
        if (wobbleTime() < WOBBLE_SECONDS + .05f) h1 = h1 * 31 + SystemClock.elapsedRealtime();
        return h1;
    }
    private void layoutSpace(Canvas canvas, int left, int top, int w, int h, float sx, float sy) {
        canvas.clipRect(left, top, left + w, top + h);
        canvas.translate(left, top); canvas.scale(sx, sy);
    }

    // new surface = old recording is trash
    public synchronized void invalidate() { sceneSignature = Long.MIN_VALUE; }

    // phone ui asks if the real wallpaper is showing
    public boolean wallpaperReadable() { return backdrop.wallpaperReadable(); }

    // static panel glass baked once, no per-frame shader
    private void panel(Canvas c, String key, Silk silk, RectF box, float radius, int tint) {
        if (c.isHardwareAccelerated() && baker.available()) {
            final float layoutWidth = width;
            baker.request(new GlassBaker.Spec(key, frameCanvasW, frameCanvasH, frameLeft, frameTop, frameSx, frameSy,
                    layoutWidth, box, radius, backdrop.version(), PANEL_GLASS), canvas -> backdrop.draw(canvas, layoutWidth, H, 0f));
            Bitmap glassImage = baker.get(key);
            if (glassImage != null && Math.abs(glassImage.getWidth() - box.width() * frameSx) <= 1.5f
                    && Math.abs(glassImage.getHeight() - box.height() * frameSy) <= 1.5f) {
                c.drawBitmap(glassImage, null, box, image);
                return;
            }
        }
        // bake not there yet (or no hw canvas), silk over frost
        backdrop.frost(c, box, radius, width, H);
        silk.draw(c, box, radius, tint, ACCENT, true);
    }

    private void drawScene(Canvas c, float now) {
        backdrop.draw(c, width, H, 0f); // animated aurora would redraw every frame, nope
        status(c);
        float x0 = 20, x1 = width - 20, gap = 12, navW = (x1 - x0 - gap) * 1.45f / 2.45f;
        r.set(x0, BAR_TOP, x0 + navW, BAR_BOTTOM); RectF mapsBox = new RectF(r);
        float sideL = x0 + navW + gap, mediaH = (BAR_BOTTOM - BAR_TOP - gap) * 1.35f / 2.35f;
        RectF mediaBox = new RectF(sideL, BAR_TOP, x1, BAR_TOP + mediaH);
        RectF messageBox = new RectF(sideL, BAR_TOP + mediaH + gap, x1, BAR_BOTTOM);
        this.mapsBox.set(mapsBox);
        // live map lives on top of the scene, wobbling the panel under it would just look broken
        if (liveMaps()) maps(c, mapsBox);
        else { int s0 = wobble(c, mapsBox); hit(mapsBox, mapsBox, null); maps(c, mapsBox); c.restoreToCount(s0); }
        int saved;
        saved = wobble(c, mediaBox); hit(mediaBox, mediaBox, null); media(c, mediaBox, now); c.restoreToCount(saved);
        saved = wobble(c, messageBox); messages(c, messageBox); c.restoreToCount(saved);
    }

    private void status(Canvas c) {
        String clock = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date());
        label(c, clock, 20, 33, 20, semibold, TEXT);
        // 3x3 dots by the clock, same drawer as the swipe
        text.setTypeface(semibold); text.setTextSize(20);
        float ax = 20 + text.measureText(clock) + 18, ay = 17;
        RectF visual = new RectF(ax - 4, ay - 4, ax + 20, ay + 20);
        int saved = wobble(c, visual);
        paint.setColor(TEXT2);
        for (int i = 0; i < 9; i++) c.drawCircle(ax + 2 + (i % 3) * 6, ay + 2 + (i / 3) * 6, 1.8f, paint);
        c.restoreToCount(saved);
        r.set(ax - 12, ay - 12, ax + 28, ay + 28); hit(r, visual, this::openDrawer);
        String battery = batteryLevel > 0 ? batteryLevel + "%" : "";
        text.setTypeface(medium); text.setTextSize(15);
        float bw = text.measureText(battery);
        label(c, battery, width - 20 - bw, 31, 15, medium, TEXT2);
        icon(c, R.drawable.car_wifi, width - 20 - bw - 26, 16, 18, TEXT2);
    }

    // maps
    private void maps(Canvas c, RectF box) {
        panel(c, "maps", navSilk, box, 24, PANEL);
        CarData.App app = data.chosenApp(CarData.MAPS);
        if (app != null && liveMaps()) {
            centered(c, "Opening " + app.label + "…", box.centerX(), box.centerY(), 16, semibold, TEXT2, box.width() - 40);
            return;
        }
        if (app == null) {
            empty(c, box, R.drawable.car_map, "Choose a maps app",
                    "Directions from your phone show up here, with turns in the island.", "Select maps app", () -> pick(CarData.MAPS));
            return;
        }
        CarData.Navigation nav = data.navigation;
        if (nav == null) {
            float cy = box.centerY();
            bitmapRound(c, app.icon, box.centerX() - 28, cy - 78, 56, 16);
            centered(c, app.label + " is ready", box.centerX(), cy + 4, 18, semibold, TEXT, box.width() - 40);
            centered(c, "Start a route in " + app.label + " on your phone.", box.centerX(), cy + 30, 14, regular, TEXT2, box.width() - 40);
            centered(c, "Turn-by-turn directions will appear here.", box.centerX(), cy + 50, 14, regular, TEXT2, box.width() - 40);
        } else {
            float tile = 76;
            r.set(box.left + 20, box.top + 20, box.left + 20 + tile, box.top + 20 + tile);
            fill(c, r, 22, ACCENT_CONTAINER);
            if (nav.maneuver != null) bitmapFit(c, nav.maneuver, r, 12);
            else icon(c, R.drawable.car_turn, r.left + 18, r.top + 18, 40, ACCENT);
            float tx = r.right + 16, maxW = box.right - 20 - tx;
            label(c, nav.instruction, tx, box.top + 52, 24, bold, TEXT, maxW);
            label(c, nav.detail, tx, box.top + 80, 15, regular, TEXT2, maxW);
            if (!nav.eta.isEmpty()) label(c, nav.eta, box.left + 20, box.bottom - 24, 18, semibold, TEXT, box.width() - 200);
        }
        appChip(c, app, box.right - 14, box.bottom - 14, () -> pick(CarData.MAPS));
    }

    // media
    private void media(Canvas c, RectF box, float now) {
        panel(c, "media", mediaSilk, box, 28, PANEL);
        CarData.App app = data.chosenApp(CarData.MEDIA);
        if (app == null) {
            empty(c, box, R.drawable.car_music, "Choose a media app", "Music, podcasts and audiobooks play here.",
                    "Select media app", () -> pick(CarData.MEDIA));
            return;
        }
        CarData.Media m = data.media;
        float pad = 16, art = 64;
        r.set(box.left + pad, box.top + pad, box.left + pad + art, box.top + pad + art);
        if (m != null && m.art != null) bitmapRound(c, m.art, r.left, r.top, art, 14);
        else { fill(c, r, 14, ACCENT_CONTAINER); bitmapRound(c, app.icon, r.left + 16, r.top + 16, 32, 9); }
        float tx = r.right + 14, maxW = box.right - pad - tx - 30;
        String title = m == null || m.title.isEmpty() ? "Not playing" : m.title;
        String artist = m == null ? "Tap play to start " + app.label : m.artist;
        label(c, title, tx, box.top + pad + 18, 17, semibold, TEXT, maxW);
        label(c, artist, tx, box.top + pad + 38, 14, regular, TEXT2, maxW);
        appChipInline(c, app, tx, box.top + pad + 46, () -> pick(CarData.MEDIA));
        boolean playing = m != null && m.playing;
        eqX = box.right - pad - 26; eqY = box.top + pad + 8; eqH = 18; eqPlaying = playing; eqVisible = true;
        if (!c.isHardwareAccelerated()) { eqVisible = false; equalizer(c, eqX, eqY, eqH, playing, now); }

        // seek + times
        float seekY = box.bottom - 102;
        long position = m == null ? 0 : m.positionNow(), duration = m == null ? 0 : m.duration;
        r.set(box.left + pad, seekY, box.right - pad, seekY + 6); fill(c, r, 3, 0x1FFFFFFF);
        if (duration > 0) {
            float k = Math.min(1f, position / (float) duration);
            r2.set(r.left, r.top, r.left + r.width() * k, r.bottom); fill(c, r2, 3, ACCENT);
            paint.setColor(Color.WHITE); c.drawCircle(r2.right, r.centerY(), 7, paint);
            label(c, time(position), box.left + pad, seekY + 22, 12, regular, TEXT2);
            String total = time(duration);
            text.setTypeface(regular); text.setTextSize(12);
            label(c, total, box.right - pad - text.measureText(total), seekY + 22, 12, regular, TEXT2);
        }
        // controls
        float cy = box.bottom - 44, cx = box.centerX();
        control(c, cx - 70, cy, 24, R.drawable.car_prev, false, () -> data.mediaAction(-1));
        control(c, cx, cy, 28, playing ? R.drawable.car_pause : R.drawable.car_play, true, () -> data.mediaAction(0));
        control(c, cx + 70, cy, 24, R.drawable.car_next, false, () -> data.mediaAction(1));
    }
    private void control(Canvas c, float cx, float cy, float radius, int icon, boolean primary, Runnable action) {
        r.set(cx - radius, cy - radius, cx + radius, cy + radius);
        RectF visual = new RectF(r);
        int saved = wobble(c, visual);
        if (primary) { paint.setColor(ACCENT); c.drawCircle(cx, cy, radius, paint); }
        icon(c, icon, cx - 13, cy - 13, 26, primary ? ON_ACCENT : TEXT);
        c.restoreToCount(saved);
        r.set(visual); r.inset(-6, -6); hit(r, visual, action);
    }
    private void equalizer(Canvas c, float x, float y, float h, boolean playing, float now) {
        paint.setColor(ACCENT);
        for (int i = 0; i < 6; i++) {
            float k = playing ? .3f + .7f * (.5f + .5f * (float) Math.sin(now * OMEGA[i] * .5f + i * 1.7f)) : .25f;
            float bh = h * k;
            r2.set(x + i * 4.5f, y + (h - bh) / 2, x + i * 4.5f + 2.5f, y + (h + bh) / 2);
            c.drawRoundRect(r2, 1.25f, 1.25f, paint);
        }
    }

    // messages: vertical carousel of what's in the shade, swipe up/down or it drifts on its own
    private void messages(Canvas c, RectF box) {
        panel(c, "messages", messageSilk, box, 24, PANEL);
        msgBox.set(box);
        List<CarData.Signal> list = data.showing;
        if (list.isEmpty()) {
            hit(box, box, null);
            r.set(box.left + 16, box.centerY() - 22, box.left + 60, box.centerY() + 22);
            fill(c, r, 14, ACCENT_CONTAINER);
            icon(c, R.drawable.car_message, r.left + 10, r.top + 10, 24, ACCENT);
            float tx = r.right + 12, maxW = box.right - 16 - tx;
            label(c, data.listenerConnected ? "No notifications" : "Notifications are off", tx, box.centerY() - 3, 15, semibold, TEXT, maxW);
            label(c, data.listenerConnected ? "Anything in your phone's shade shows up here" : "Allow notification access on your phone",
                    tx, box.centerY() + 17, 13, regular, TEXT2, maxW);
            return;
        }
        int n = list.size();
        float pos = n < 2 ? carouselIndex : carouselPos(), h = box.height();
        int saved = c.save();
        c.clipRect(box.left, box.top + 4, box.right, box.bottom - 4);
        for (int k = (int) Math.floor(pos) - 1; k <= (int) Math.floor(pos) + 1; k++) {
            float offset = (k - pos) * h;
            if (Math.abs(offset) >= h) continue;
            CarData.Signal s = list.get(Math.floorMod(k, n));
            float fade = 1 - Math.min(1, Math.abs(offset) / h);
            int layer = fade >= .99f ? c.save() : c.saveLayerAlpha(box, Math.round(255 * fade));
            c.translate(0, offset);
            messageCard(c, box, s, n > 1);
            c.restoreToCount(layer);
        }
        c.restoreToCount(saved);
        if (n > 1) carouselDots(c, box, n, Math.floorMod(Math.round(pos), n));
        CarData.Signal current = list.get(Math.floorMod(carouselIndex, n));
        hit(box, box, () -> island.show(current, true));
    }
    private void messageCard(Canvas c, RectF box, CarData.Signal s, boolean dots) {
        float pad = 16, size = 44, cy = box.centerY();
        r.set(box.left + pad, cy - size / 2, box.left + pad + size, cy + size / 2);
        if (s.avatar != null) bitmapRound(c, s.avatar, r.left, r.top, size, size / 2);
        else {
            fill(c, r, 14, ACCENT_CONTAINER);
            if (s.appIcon != null) bitmapRound(c, s.appIcon, r.left + 8, r.top + 8, 28, 8);
            else icon(c, R.drawable.car_message, r.left + 10, r.top + 10, 24, ACCENT);
        }
        float tx = r.right + 12, maxW = box.right - (dots ? 30 : 16) - tx;
        if (s.avatar != null && s.appIcon != null) bitmapRound(c, s.appIcon, tx, cy - 31, 13, 3);
        label(c, Island.header(s), tx + (s.avatar != null && s.appIcon != null ? 18 : 0), cy - 20, 11, medium, TEXT2, maxW - 18);
        label(c, s.title, tx, cy + 1, 15, semibold, TEXT, maxW);
        label(c, s.text, tx, cy + 20, 13, regular, TEXT2, maxW);
    }
    // >6 notifs just says 3/9, dots get silly past that
    private void carouselDots(Canvas c, RectF box, int n, int active) {
        float x = box.right - 14;
        if (n > 6) {
            String count = (active + 1) + "/" + n;
            text.setTypeface(medium); text.setTextSize(11);
            label(c, count, x + 4 - text.measureText(count), box.top + 22, 11, medium, TEXT2);
            return;
        }
        float spacing = 9, top = box.centerY() - (n - 1) * spacing / 2;
        for (int i = 0; i < n; i++) {
            paint.setColor(i == active ? ACCENT : 0x40FFFFFF);
            c.drawCircle(x, top + i * spacing, i == active ? 3 : 2.2f, paint);
        }
    }

    // picker sheet
    private void pick(String slot) { data.refreshAppsSoon(); picking = slot; }
    private void drawPicker(Canvas c) {
        paint.setColor(0x73000000); c.drawRect(0, 0, width, H, paint);
        hit(new RectF(0, 0, width, H), () -> picking = null); // tap outside closes
        List<CarData.App> apps = data.apps(picking);
        int rows = Math.max(1, Math.min(3, (apps.size() + 2) / 3));
        float w = Math.min(560, width - 48), h = 18 + 3 + 12 + 26 + 20 + 14 + rows * 72 + 54;
        RectF sheet = new RectF((width - w) / 2, (H - h) / 2, (width + w) / 2, (H + h) / 2);
        backdrop.frost(c, sheet, 28, width, H); sheetSilk.draw(c, sheet, 28, SHEET, ACCENT, true);
        hit(sheet, () -> { }); // eat taps on the sheet
        r.set(sheet.centerX() - 14, sheet.top + 18, sheet.centerX() + 14, sheet.top + 21); fill(c, r, 1.5f, 0x40FFFFFF);
        boolean maps = CarData.MAPS.equals(picking);
        float x = sheet.left + 20, y = sheet.top + 33;
        label(c, maps ? "Choose your maps app" : "Choose your media app", x, y + 20, 20, semibold, TEXT);
        label(c, maps ? "Its directions appear in this panel and turns in the island."
                : "Now playing and controls appear in this panel.", x, y + 40, 13, regular, TEXT2, w - 40);
        y += 54;
        if (apps.isEmpty()) {
            label(c, maps ? "No navigation apps found on your phone." : "No media apps found on your phone.", x, y + 30, 15, medium, TEXT);
        }
        String current = data.chosen(picking);
        float tileW = (w - 40 - 16) / 3;
        for (int i = 0; i < Math.min(9, apps.size()); i++) {
            CarData.App app = apps.get(i);
            float tx = x + (i % 3) * (tileW + 8), ty = y + (i / 3) * 72;
            r.set(tx, ty, tx + tileW, ty + 64);
            RectF visual = new RectF(r);
            int saved = wobble(c, visual);
            boolean selected = app.pkg.equals(current);
            fill(c, r, 22, selected ? ACCENT_CONTAINER : CHIP);
            if (selected) { paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1); paint.setColor(ACCENT);
                c.drawRoundRect(r, 22, 22, paint); paint.setStyle(Paint.Style.FILL); }
            bitmapRound(c, app.icon, tx + 10, ty + 10, 44, 14);
            label(c, app.label, tx + 64, ty + 30, 14, semibold, TEXT, tileW - 72);
            label(c, app.kind, tx + 64, ty + 46, 11, regular, TEXT2, tileW - 72);
            final String slot = picking, pkg = app.pkg;
            c.restoreToCount(saved);
            hit(visual, visual, () -> { data.choose(slot, pkg); closeSheetsSoon(); });
        }
        text.setTypeface(medium); text.setTextSize(13);
        float cw = text.measureText("Cancel") + 32;
        r.set(sheet.right - 20 - cw, sheet.bottom - 20 - 34, sheet.right - 20, sheet.bottom - 20);
        RectF cancel = new RectF(r);
        int saved = wobble(c, cancel);
        fill(c, r, 17, CHIP);
        label(c, "Cancel", r.left + 16, r.top + 22, 13, medium, TEXT);
        c.restoreToCount(saved);
        hit(cancel, cancel, this::closeSheetsSoon);
        label(c, "You can change this any time from the panel.", x, sheet.bottom - 32, 12, regular, TEXT2, w - cw - 60);
    }

    // maps panel when a car's connected: car app lib app if it has a car mode, else the phone app on a virtual display
    private void drawLiveMaps(Canvas c) {
        CarData.App app = session ? data.chosenApp(CarData.MAPS) : null;
        String want = mapWant(app);
        if (want == null ? mapSource != null : !want.equals(mapSource)) {
            closeMaps();
            mapSource = want;
            if (want != null && want.endsWith("#car")) { mapHost = new CarAppHost(context, app.carService, null); mapHost.start(); }
            else if (want != null) mapDisplay = new AppDisplay(context, app.pkg);
        }
        if (mapHost != null && mapHost.rejected) {
            // this app's own HostValidator said no — stop here, next frame picks the display fallback or the notif card
            calRejected.add(app.pkg);
            closeMaps();
            return;
        }
        if (app == null || want == null || mapsBox.width() < 1) return;
        int w = Math.round(mapsBox.width() * frameSx), h = Math.round(mapsBox.height() * frameSy), dpi = Math.round(160 * frameSx);
        boolean resized = w != mapW || h != mapH || dpi != mapDpi;
        mapW = w; mapH = h; mapDpi = dpi;
        if (mapHost != null) {
            if (resized) mapHost.size(w, h, dpi, new android.graphics.Rect(0, 0, w, h));
            if (carPanel == null) carPanel = new CarAppPanel(context, this);
            hit(mapsBox, this::mapClick); // bare map tap, the panel's own buttons register after and win
            carPanel.draw(c, mapsBox, mapHost, mapHost.frame());
        } else if (mapDisplay != null) {
            if (resized) mapDisplay.size(w, h, dpi);
            liveFrame(c, mapsBox, 24, mapDisplay.frame(), "Opening " + app.label + "…");
        }
    }
    private final android.graphics.Path liveClip = new android.graphics.Path();
    private void liveFrame(Canvas c, RectF box, float radius, Bitmap frame, String waiting) {
        int saved = c.save();
        liveClip.reset(); liveClip.addRoundRect(box, radius, radius, android.graphics.Path.Direction.CW); c.clipPath(liveClip);
        if (frame != null) c.drawBitmap(frame, null, box, image);
        else { paint.setColor(0xFF15171E); c.drawRect(box, paint); centered(c, waiting, box.centerX(), box.centerY(), 15, semibold, TEXT2, box.width() - 40); }
        c.restoreToCount(saved);
    }

    // floating app window, same spring as the drawer so it feels like one ui
    private void drawWindow(Canvas c) {
        long nowMs = SystemClock.elapsedRealtime();
        float dt = Math.min(.1f, (nowMs - windowAt) / 1000f); windowAt = nowMs;
        for (float left = dt; left > 0; left -= .004f) {
            float step = Math.min(.004f, left);
            windowVel += (260 * ((windowOpen ? 1 : 0) - windowAnim) - 32.2f * windowVel) * step; windowAnim += windowVel * step;
            fullVel += (260 * ((windowFull ? 1 : 0) - fullAnim) - 32.2f * fullVel) * step; fullAnim += fullVel * step;
        }
        if (!windowOpen && windowAnim < .002f) {
            windowAnim = 0; windowVel = 0;
            if (window != null) { window.close(); window = null; windowApp = null; }
            windowContent.setEmpty(); windowRect.setEmpty();
            return;
        }
        if (window == null || windowApp == null) return;
        float p = Math.max(0, Math.min(1, windowAnim)), f = Math.max(0, Math.min(1, fullAnim));
        float nw = Math.min(width - 40, width * .7f), nl = (width - nw) / 2;
        windowRect.set(lerp(nl, 6, f), lerp(58, 6, f), lerp(nl + nw, width - 6, f), lerp(H - 14, H - 6, f));
        windowContent.set(windowRect.left + 6, windowRect.top + 40, windowRect.right - 6, windowRect.bottom - 6);
        // size the display off where it's heading, not mid-animation, or it resizes 20 times
        if (windowFull) windowTarget.set(12, 46, width - 12, H - 12); else windowTarget.set(nl + 6, 98, nl + nw - 6, H - 20);
        int w = Math.round(windowTarget.width() * frameSx), h = Math.round(windowTarget.height() * frameSy), dpi = Math.round(160 * frameSx);
        if (windowOpen && (w != winW || h != winH || dpi != winDpi)) { winW = w; winH = h; winDpi = dpi; window.size(w, h, dpi); }

        int saved = p >= .99f ? c.save() : c.saveLayerAlpha(windowRect, Math.round(255 * p));
        float k = .92f + .08f * p;
        c.scale(k, k, windowRect.centerX(), windowRect.centerY());
        paint.setColor(Color.argb(Math.round(70 * p), 0, 0, 0)); r.set(windowRect); r.offset(0, 6); c.drawRoundRect(r, 24, 24, paint);
        backdrop.frost(c, windowRect, 22, width, H); fill(c, windowRect, 22, 0xE6181A21);
        bitmapRound(c, windowApp.icon, windowRect.left + 14, windowRect.top + 9, 22, 6);
        float bx = windowRect.right - 10;
        bx = windowButton(c, bx, 2, () -> windowOpen = false);
        bx = windowButton(c, bx, 1, () -> windowFull = !windowFull);
        bx = windowButton(c, bx, 0, () -> { if (window != null) window.back(); });
        label(c, windowApp.label, windowRect.left + 44, windowRect.top + 26, 15, semibold, TEXT, bx - windowRect.left - 54);
        liveFrame(c, windowContent, 16, window.frame(), "Opening " + windowApp.label + "…");
        c.restoreToCount(saved);
    }
    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }
    // 0 back 1 full screen toggle 2 close, drawn as lines so no extra drawables
    private float windowButton(Canvas c, float right, int kind, Runnable action) {
        RectF b = new RectF(right - 30, windowRect.top + 5, right, windowRect.top + 35);
        int saved = wobble(c, b);
        fill(c, b, 15, CHIP);
        paint.setColor(TEXT); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2); paint.setStrokeCap(Paint.Cap.ROUND);
        float cx = b.centerX(), cy = b.centerY();
        if (kind == 0) { c.drawLine(cx + 3, cy - 6, cx - 3, cy, paint); c.drawLine(cx - 3, cy, cx + 3, cy + 6, paint); }
        else if (kind == 1) {
            float o = windowFull ? 3 : 6;
            r2.set(cx - o, cy - o, cx + o, cy + o); c.drawRoundRect(r2, 2, 2, paint);
        } else { c.drawLine(cx - 5, cy - 5, cx + 5, cy + 5, paint); c.drawLine(cx + 5, cy - 5, cx - 5, cy + 5, paint); }
        paint.setStyle(Paint.Style.FILL); paint.setStrokeCap(Paint.Cap.BUTT);
        c.restoreToCount(saved);
        if (windowOpen) hit(b, new RectF(b), action);
        return b.left - 8;
    }

    // drawer, slides from the left, two side-scrolling rows
    private void drawDrawer(Canvas c) {
        long nowMs = SystemClock.elapsedRealtime();
        float dt = Math.min(.1f, (nowMs - drawerAt) / 1000f); drawerAt = nowMs;
        float target = drawerOpen ? 1 : 0;
        // follow the finger mid swipe if the car sends moves — never peek for a drag that started on the map panel
        if (!drawerOpen && dragging && !scrolling && picking == null && lastX > downX && !mapsBox.contains(downX, downY))
            target = Math.min(.35f, (lastX - downX) / 400f);
        // critically damped spring, old lerp+cubic did 84% of the slide in one frame and crawled the rest
        for (float left = dt; left > 0; left -= .004f) {
            float step = Math.min(.004f, left);
            drawerVel += (220 * (target - drawer) - 29.7f * drawerVel) * step;
            drawer += drawerVel * step;
        }
        if (Math.abs(target - drawer) < .002f && Math.abs(drawerVel) < .01f) { drawer = target; drawerVel = 0; }
        if (drawer <= .001f) { drawerRow.setEmpty(); return; }
        float p = Math.max(0, Math.min(1, drawer));
        paint.setColor(Color.argb(Math.round(0x73 * p), 0, 0, 0)); c.drawRect(0, 0, width, H, paint);
        if (drawerOpen) hit(new RectF(0, 0, width, H), () -> drawerOpen = false); // tap outside closes

        float x0 = 20, w = width - 40, slide = (1 - p) * (w + x0);
        RectF sheet = new RectF(x0 - slide, BAR_TOP, x0 + w - slide, BAR_BOTTOM);
        backdrop.frost(c, sheet, 28, width, H); drawerSilk.draw(c, sheet, 28, SHEET, ACCENT, true);
        if (drawerOpen) hit(sheet, () -> { }); // eat taps on the sheet
        float x = sheet.left + 24, y = sheet.top + 24;
        label(c, "Apps", x, y + 22, 22, semibold, TEXT);
        label(c, "Car-ready apps on your phone", x, y + 44, 13, regular, TEXT2, w - 48);

        List<CarData.App> apps = data.carApps();
        float rowTop = y + 66, rowsH = 2 * TILE_H + TILE_GAP;
        drawerRow.set(sheet.left + 12, rowTop, sheet.right - 12, Math.min(sheet.bottom - 16, rowTop + rowsH));
        if (apps.isEmpty()) {
            centered(c, "No car-ready apps found", sheet.centerX(), drawerRow.centerY() - 6, 17, semibold, TEXT, w - 48);
            centered(c, "Install a media or navigation app that supports Android Auto.", sheet.centerX(),
                    drawerRow.centerY() + 18, 13, regular, TEXT2, w - 48);
            drawerMaxScroll = 0;
            return;
        }
        int columns = (apps.size() + 1) / 2;
        float content = columns * (TILE_W + TILE_GAP) - TILE_GAP + 24;
        drawerMaxScroll = Math.max(0, content - drawerRow.width());
        scrollDrawer(drawerScroll);
        int saved = c.save();
        c.clipRect(drawerRow);
        String maps = data.chosen(CarData.MAPS), media = data.chosen(CarData.MEDIA);
        for (int i = 0; i < apps.size(); i++) {
            CarData.App app = apps.get(i);
            float tx = drawerRow.left + 12 + (i / 2) * (TILE_W + TILE_GAP) - drawerScroll;
            float ty = drawerRow.top + (i % 2) * (TILE_H + TILE_GAP);
            if (tx + TILE_W < drawerRow.left || tx > drawerRow.right) continue;
            r.set(tx, ty, tx + TILE_W, ty + TILE_H);
            RectF visual = new RectF(r);
            int tileSaved = wobble(c, visual);
            boolean selected = app.pkg.equals(CarData.MAPS.equals(CarData.slot(app)) ? maps : media);
            fill(c, r, 24, selected ? ACCENT_CONTAINER : CHIP);
            if (selected) { paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1); paint.setColor(ACCENT);
                c.drawRoundRect(r, 24, 24, paint); paint.setStyle(Paint.Style.FILL); }
            bitmapRound(c, app.icon, r.centerX() - 32, ty + 20, 64, 18);
            centered(c, app.label, r.centerX(), ty + 110, 15, semibold, TEXT, TILE_W - 16);
            centered(c, app.kind, r.centerX(), ty + 129, 12, regular, TEXT2, TILE_W - 16);
            c.restoreToCount(tileSaved);
            if (drawerOpen) {
                RectF tap = new RectF(visual); tap.setIntersect(tap, drawerRow);
                hit(tap, visual, () -> {
                    if (CarData.slot(app) == null) { openWindow(app); drawerOpen = false; }
                    else { data.open(app); closeSheetsSoon(); }
                });
            }
        }
        c.restoreToCount(saved);
        // edge fades so you know it scrolls
        if (drawerScroll > 1) scrollHint(c, drawerRow.left, true);
        if (drawerScroll < drawerMaxScroll - 1) scrollHint(c, drawerRow.right, false);
    }
    private void scrollHint(Canvas c, float edge, boolean left) {
        paint.setColor(TEXT2);
        float cy = drawerRow.centerY(), x = left ? edge + 6 : edge - 6, k = left ? 1 : -1;
        paint.setStrokeWidth(3); paint.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(x + 6 * k, cy - 10, x, cy, paint); c.drawLine(x, cy, x + 6 * k, cy + 10, paint);
        paint.setStrokeCap(Paint.Cap.BUTT);
    }

    // island
    private void drawIsland(Canvas c, RenderNode backdrop, int left, int top, float sx, float sy, float now) {
        RectF box = island.bounds(width);
        if (box.width() < 1) return;
        int accent = island.signal != null && island.signal.color != 0 ? island.signal.color | 0xFF000000 : ACCENT;
        glass.draw(c, backdrop, sceneVersion, left, top, sx, sy, box, island.radius(), island.sheen(now), island.neon(now), accent);
        float alpha = island.contentAlpha();
        if (alpha > 0.01f && island.signal != null) {
            int saved = alpha >= .99f ? c.save() : c.saveLayerAlpha(box, (int) (alpha * 255));
            c.clipRect(box);
            if (island.expanded) islandCard(c, box, island.signal); else islandCapsule(c, box, island.signal, now);
            c.restoreToCount(saved);
        }
        hit(box, island::tap);
    }
    private void islandCapsule(Canvas c, RectF box, CarData.Signal s, float now) {
        float x = box.left + 6, cy = box.centerY();
        Bitmap icon = s.kind == CarData.Signal.Kind.MEDIA && s.avatar != null ? s.avatar : s.appIcon;
        if (icon != null) bitmapRound(c, icon, x, cy - 9, 18, s.kind == CarData.Signal.Kind.MEDIA ? 5 : 9);
        boolean eq = s.kind == CarData.Signal.Kind.MEDIA;
        float maxW = box.right - 10 - (x + 24) - (eq ? 22 : 0);
        label(c, s.title, x + 24, cy + 4.5f, 12, bold, Color.WHITE, maxW);
        if (eq) {
            paint.setColor(ACCENT);
            for (int i = 0; i < 4; i++) {
                float k = .3f + .7f * (.5f + .5f * (float) Math.sin(now * OMEGA[i] * .5f + i * 1.7f)), bh = 12 * k;
                r2.set(box.right - 26 + i * 4.5f, cy - bh / 2, box.right - 26 + i * 4.5f + 2.5f, cy + bh / 2);
                c.drawRoundRect(r2, 1.25f, 1.25f, paint);
            }
        }
    }
    // like NotificationPresenter.bindExpanded: indicator, header, avatar + title + text
    private void islandCard(Canvas c, RectF box, CarData.Signal s) {
        r.set(box.centerX() - 14, box.top + 12, box.centerX() + 14, box.top + 15); fill(c, r, 1.5f, 0x40FFFFFF);
        float x = box.left + 14, y = box.top + 24;
        if (s.appIcon != null) bitmapRound(c, s.appIcon, x, y, 16, 4);
        String header = Island.header(s);
        label(c, header, x + 22, y + 12, 12, bold, 0xB3FFFFFF, box.width() - 50);
        float by = y + 26, tx = x;
        if (s.avatar != null) {
            boolean round = s.kind == CarData.Signal.Kind.NOTIFICATION;
            bitmapRound(c, s.avatar, x, by, 40, round ? 20 : 10);
            tx = x + 52;
        }
        float maxW = box.right - 14 - tx;
        label(c, s.title, tx, by + 15, 15, semibold, Color.WHITE, maxW);
        label(c, s.text, tx, by + 34, 13, regular, 0xE6FFFFFF, maxW);
    }

    // shared bits
    private void empty(Canvas c, RectF box, int glyph, String title, String body, String button, Runnable action) {
        boolean compact = box.height() < 220;
        float tile = compact ? 48 : 56, cy = box.centerY() - (compact ? 40 : 56);
        r.set(box.centerX() - tile / 2, cy - tile / 2, box.centerX() + tile / 2, cy + tile / 2);
        fill(c, r, compact ? 16 : 18, ACCENT_CONTAINER);
        icon(c, glyph, r.centerX() - 14, r.centerY() - 14, 28, ACCENT);
        float ty = r.bottom + (compact ? 24 : 30);
        centered(c, title, box.centerX(), ty, 18, semibold, TEXT, box.width() - 32);
        centered(c, body, box.centerX(), ty + 22, 14, regular, TEXT2, box.width() - 32);
        text.setTypeface(medium); text.setTextSize(15);
        float bw = text.measureText(button) + 44, by = ty + (compact ? 36 : 46);
        r.set(box.centerX() - bw / 2, by, box.centerX() + bw / 2, by + 44);
        RectF visual = new RectF(r);
        int saved = wobble(c, visual);
        fill(c, r, 22, ACCENT);
        centered(c, button, box.centerX(), by + 28, 15, medium, ON_ACCENT, bw);
        c.restoreToCount(saved);
        RectF target = new RectF(visual); target.inset(-8, -8); hit(target, visual, action);
    }
    // icon + name + chevron right aligned, opens the picker
    private void appChip(Canvas c, CarData.App app, float right, float bottom, Runnable action) {
        text.setTypeface(semibold); text.setTextSize(12);
        float w = 4 + 20 + 6 + text.measureText(app.label) + 4 + 14 + 10;
        r.set(right - w, bottom - 28, right, bottom);
        RectF visual = new RectF(r);
        int saved = wobble(c, visual);
        fill(c, r, 14, 0x9E101012);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1); paint.setColor(0x29FFFFFF);
        c.drawRoundRect(r, 14, 14, paint); paint.setStyle(Paint.Style.FILL);
        bitmapRound(c, app.icon, r.left + 4, r.top + 4, 20, 6);
        label(c, app.label, r.left + 30, r.top + 18.5f, 12, semibold, TEXT);
        icon(c, R.drawable.car_chevron, r.right - 24, r.top + 7, 14, TEXT2);
        c.restoreToCount(saved);
        RectF target = new RectF(visual); target.inset(-6, -8); hit(target, visual, action);
    }
    private void appChipInline(Canvas c, CarData.App app, float x, float top, Runnable action) {
        text.setTypeface(semibold); text.setTextSize(11);
        float w = 4 + 16 + 6 + text.measureText(app.label) + 4 + 14 + 8;
        r.set(x, top, x + w, top + 24);
        RectF visual = new RectF(r);
        int saved = wobble(c, visual);
        fill(c, r, 12, CHIP);
        bitmapRound(c, app.icon, x + 4, top + 4, 16, 5);
        label(c, app.label, x + 26, top + 16, 11, semibold, TEXT2);
        icon(c, R.drawable.car_chevron, r.right - 20, top + 5, 14, TEXT2);
        c.restoreToCount(saved);
        RectF target = new RectF(visual); target.inset(-6, -8); hit(target, visual, action);
    }
    void fill(Canvas c, RectF rect, float radius, int color) { paint.setColor(color); c.drawRoundRect(rect, radius, radius, paint); }
    void label(Canvas c, String s, float x, float baseline, float size, Typeface face, int color) {
        label(c, s, x, baseline, size, face, color, Float.MAX_VALUE);
    }
    void label(Canvas c, String s, float x, float baseline, float size, Typeface face, int color, float maxWidth) {
        if (s == null || s.isEmpty() || maxWidth <= 0) return;
        text.setTypeface(face); text.setTextSize(size); text.setColor(color); text.setTextAlign(Paint.Align.LEFT);
        CharSequence fit = maxWidth == Float.MAX_VALUE ? s : TextUtils.ellipsize(s, text, maxWidth, TextUtils.TruncateAt.END);
        c.drawText(fit, 0, fit.length(), x, baseline, text);
    }
    void centered(Canvas c, String s, float cx, float baseline, float size, Typeface face, int color, float maxWidth) {
        text.setTypeface(face); text.setTextSize(size);
        CharSequence fit = TextUtils.ellipsize(s, text, maxWidth, TextUtils.TruncateAt.END);
        label(c, fit.toString(), cx - text.measureText(fit, 0, fit.length()) / 2, baseline, size, face, color);
    }
    void icon(Canvas c, int res, float x, float y, float size, int color) {
        Drawable d = icons.get(res);
        if (d == null) { d = context.getDrawable(res); if (d == null) return; icons.put(res, d); }
        d.setTint(color); d.setBounds(Math.round(x), Math.round(y), Math.round(x + size), Math.round(y + size)); d.draw(c);
    }
    private void bitmapRound(Canvas c, Bitmap b, float x, float y, float size, float radius) {
        if (b == null) return;
        BitmapShader shader = shaders.get(b);
        if (shader == null) {
            if (shaders.size() > 48) shaders.clear();
            shader = new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP); shaders.put(b, shader);
        }
        float k = Math.max(size / b.getWidth(), size / b.getHeight());
        matrix.setScale(k, k);
        matrix.postTranslate(x + (size - b.getWidth() * k) / 2, y + (size - b.getHeight() * k) / 2);
        shader.setLocalMatrix(matrix);
        image.setShader(shader);
        r2.set(x, y, x + size, y + size);
        c.drawRoundRect(r2, radius, radius, image);
        image.setShader(null);
    }
    void bitmapFit(Canvas c, Bitmap b, RectF box, float inset) {
        r2.set(box); r2.inset(inset, inset);
        float k = Math.min(r2.width() / b.getWidth(), r2.height() / b.getHeight());
        float w = b.getWidth() * k, h = b.getHeight() * k;
        r2.set(box.centerX() - w / 2, box.centerY() - h / 2, box.centerX() + w / 2, box.centerY() + h / 2);
        c.drawBitmap(b, null, r2, image);
    }
    private static String time(long ms) {
        long s = ms / 1000;
        return s >= 3600 ? String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
                : String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60);
    }
}
