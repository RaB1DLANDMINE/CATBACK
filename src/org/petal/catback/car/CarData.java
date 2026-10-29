package org.petal.catback.car;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.util.Log;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;

// everything the car screen shows from the phone. main thread writes, renderer reads snapshots from wherever
public final class CarData {
    public static final String MAPS = "maps", MEDIA = "media", KIND_APP = "App";
    // 1 = play, never pause. 0 = toggle
    public static final int PLAY = 2;
    // how apps say they do AA: car descriptor or the car app lib service
    private static final String CAR_APPLICATION = "com.google.android.gms.car.application",
            CAR_APP_SERVICE = "androidx.car.app.CarAppService", CAR_NAVIGATION = "androidx.car.app.category.NAVIGATION";
    private static CarData instance;
    public static synchronized CarData get(Context context) {
        if (instance == null) instance = new CarData(context.getApplicationContext());
        return instance;
    }

    public static final class App {
        public final String pkg, label, kind;
        public final Bitmap icon;
        final ComponentName browser; // media apps
        public final ComponentName carService; // car app lib nav service, null = plain phone app
        App(String pkg, String label, String kind, Bitmap icon, ComponentName browser, ComponentName carService) {
            this.pkg = pkg; this.label = label; this.kind = kind; this.icon = icon; this.browser = browser; this.carService = carService;
        }
    }
    // one island ping: notif, new track or next turn
    public static final class Signal {
        public enum Kind { NOTIFICATION, MEDIA, NAVIGATION }
        public final Kind kind;
        public final String app, title, text;
        public final Bitmap appIcon, avatar;
        public final int color;
        public final long when;
        public Signal(Kind kind, String app, String title, String text, Bitmap appIcon, Bitmap avatar, int color, long when) {
            this.kind = kind; this.app = app; this.title = title; this.text = text;
            this.appIcon = appIcon; this.avatar = avatar; this.color = color; this.when = when;
        }
    }
    public static final class Media {
        public final String title, artist;
        public final Bitmap art;
        public final long duration, position, updated;
        public final float speed;
        public final boolean playing;
        Media(String title, String artist, Bitmap art, long duration, long position, long updated, float speed, boolean playing) {
            this.title = title; this.artist = artist; this.art = art; this.duration = duration;
            this.position = position; this.updated = updated; this.speed = speed; this.playing = playing;
        }
        public long positionNow() {
            if (!playing) return position;
            return Math.min(duration > 0 ? duration : Long.MAX_VALUE, position + (long) ((SystemClock.elapsedRealtime() - updated) * speed));
        }
    }
    public static final class Navigation {
        public final String instruction, detail, eta;
        public final Bitmap maneuver;
        Navigation(String instruction, String detail, String eta, Bitmap maneuver) {
            this.instruction = instruction; this.detail = detail; this.eta = eta; this.maneuver = maneuver;
        }
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ConcurrentLinkedQueue<Signal> signals = new ConcurrentLinkedQueue<>();
    public volatile Media media;
    public volatile Navigation navigation;
    public volatile Signal latestMessage;
    // whatever's sitting in the shade right now, newest first, feeds the messages carousel
    public volatile List<Signal> showing = Collections.emptyList();
    private final java.util.LinkedHashMap<String, Signal> live = new java.util.LinkedHashMap<>();
    public volatile boolean listenerConnected;
    private volatile List<App> mapsApps = Collections.emptyList(), mediaApps = Collections.emptyList(),
            carApps = Collections.emptyList();
    private MediaController controller;
    private MediaBrowser browser;
    private String lastTrack, lastInstruction;

    private CarData(Context context) {
        this.context = context;
        prefs = context.getSharedPreferences("car-apps", Context.MODE_PRIVATE);
        refreshAppsSoon();
    }

    // chosen apps
    public String chosen(String slot) { return prefs.getString(slot, null); }
    public App chosenApp(String slot) {
        String pkg = chosen(slot);
        if (pkg == null) return null;
        for (App app : apps(slot)) if (app.pkg.equals(pkg)) return app;
        return null;
    }
    public void choose(String slot, String pkg) {
        prefs.edit().putString(slot, pkg).apply();
        main.post(() -> {
            if (MEDIA.equals(slot)) { lastTrack = null; bindMedia(); }
            else { navigation = null; lastInstruction = null; PetalNotificationListener.rescan(); }
        });
    }
    public List<App> apps(String slot) { return MAPS.equals(slot) ? mapsApps : mediaApps; }
    // car drawer, AA-capable apps we can actually drive
    public List<App> carApps() { return carApps; }
    public static String slot(App app) { return "Navigation".equals(app.kind) ? MAPS : "Media".equals(app.kind) ? MEDIA : null; }
    // nav app -> maps slot, media app -> player + hit play
    public void open(App app) {
        String slot = slot(app);
        if (slot == null) return;
        choose(slot, app.pkg);
        if (MEDIA.equals(slot)) mediaAction(PLAY);
    }

    // icon loading for every app takes a while, never on the touch/render path
    private final java.util.concurrent.ExecutorService loader = java.util.concurrent.Executors.newSingleThreadExecutor();
    private final java.util.concurrent.atomic.AtomicBoolean refreshQueued = new java.util.concurrent.atomic.AtomicBoolean();
    public void refreshAppsSoon() {
        if (refreshQueued.compareAndSet(false, true)) loader.execute(() -> { refreshQueued.set(false); refreshApps(); });
    }

    // maps = handles geo:, media = has a browser service
    public void refreshApps() {
        PackageManager pm = context.getPackageManager();
        List<App> maps = new ArrayList<>(), media = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        // car app lib nav apps count even w/o the old descriptor, and get the real car ui in the panel
        java.util.Map<String, ComponentName> carNavigation = new java.util.HashMap<>();
        for (ResolveInfo info : pm.queryIntentServices(new Intent(CAR_APP_SERVICE), PackageManager.GET_RESOLVED_FILTER))
            if (info.filter != null && info.filter.hasCategory(CAR_NAVIGATION))
                carNavigation.put(info.serviceInfo.packageName, new ComponentName(info.serviceInfo.packageName, info.serviceInfo.name));
        for (ResolveInfo info : pm.queryIntentActivities(new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=petal")), 0)) {
            String pkg = info.activityInfo.packageName;
            if (pkg.equals(context.getPackageName()) || !seen.add("m" + pkg)) continue;
            maps.add(new App(pkg, info.loadLabel(pm).toString(), "Navigation", bitmap(info.loadIcon(pm), 96), null, carNavigation.get(pkg)));
        }
        // old school browser services + media3/androidx sessions
        String[] players = {"android.media.browse.MediaBrowserService", "androidx.media3.session.MediaSessionService",
                "androidx.media3.session.MediaLibraryService", "android.media.MediaBrowserService"};
        for (String action : players) {
            for (ResolveInfo info : pm.queryIntentServices(new Intent(action), 0)) {
                String pkg = info.serviceInfo.packageName;
                if (pkg.equals(context.getPackageName()) || !seen.add("p" + pkg)) continue;
                String label = info.serviceInfo.applicationInfo.loadLabel(pm).toString();
                boolean browsable = action.endsWith("MediaBrowserService");
                media.add(new App(pkg, label, "Media", bitmap(info.serviceInfo.applicationInfo.loadIcon(pm), 96),
                        browsable ? new ComponentName(pkg, info.serviceInfo.name) : null, null));
            }
        }
        Collections.sort(maps, (a, b) -> a.label.compareToIgnoreCase(b.label));
        Collections.sort(media, (a, b) -> a.label.compareToIgnoreCase(b.label));
        mapsApps = maps; mediaApps = media;

        // drawer: car-ready nav/media up front, then every other launcher app A-Z, those open in a window
        List<App> car = new ArrayList<>(), rest = new ArrayList<>();
        Set<String> listed = new HashSet<>();
        for (App app : maps) if ((carNavigation.containsKey(app.pkg) || carReady(pm, app.pkg)) && listed.add(app.pkg)) car.add(app);
        for (App app : media) if (carReady(pm, app.pkg) && listed.add(app.pkg)) car.add(app);
        Collections.sort(car, (a, b) -> a.label.compareToIgnoreCase(b.label));
        // floating windows are rom-only (platform signature perms), no point listing apps we can't open
        if (org.petal.catback.host.AppDisplay.available(context)) {
            Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            for (ResolveInfo info : pm.queryIntentActivities(launcher, 0)) {
                String pkg = info.activityInfo.packageName;
                if (pkg.equals(context.getPackageName()) || !listed.add(pkg)) continue;
                rest.add(new App(pkg, info.loadLabel(pm).toString(), KIND_APP, bitmap(info.loadIcon(pm), 96), null, null));
            }
            Collections.sort(rest, (a, b) -> a.label.compareToIgnoreCase(b.label));
            car.addAll(rest);
        }
        carApps = car;
    }
    private static boolean carReady(PackageManager pm, String pkg) {
        try {
            Bundle meta = pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA).metaData;
            return meta != null && meta.containsKey(CAR_APPLICATION);
        } catch (PackageManager.NameNotFoundException gone) { return false; }
    }

    // island
    public Signal nextSignal() { return signals.poll(); }

    // last thing each notif key showed so re-posts don't ding again
    private final java.util.Map<String, String> shown = new java.util.HashMap<>();
    void seed(StatusBarNotification sbn, int importance) {
        shown.put(sbn.getKey(), signature(sbn.getNotification()));
        track(sbn, importance);
        // route already running when the maps app gets picked, grab it now instead of waiting for the next repost
        if (sbn.getPackageName().equals(chosen(MAPS)) && isOngoing(sbn.getNotification())) navigation(sbn);
    }
    private static String signature(Notification n) {
        Bundle e = n.extras;
        return str(e.getCharSequence(Notification.EXTRA_TITLE)) + "\u0000" + str(e.getCharSequence(Notification.EXTRA_TEXT));
    }

    void posted(StatusBarNotification sbn, int importance) {
        boolean alerting = importance >= android.app.NotificationManager.IMPORTANCE_DEFAULT;
        track(sbn, importance);
        Notification n = sbn.getNotification();
        String pkg = sbn.getPackageName();
        if (pkg.equals(chosen(MAPS)) && isOngoing(n)) { navigation(sbn); return; }
        if (pkg.equals(context.getPackageName()) || isOngoing(n) || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        if (n.category != null && (n.category.equals(Notification.CATEGORY_TRANSPORT) || n.category.equals(Notification.CATEGORY_SERVICE))) return;
        Bundle extras = n.extras;
        CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE), text = extras.getCharSequence(Notification.EXTRA_TEXT);
        if (title == null && text == null) return;
        // progress bars, alert-once and same-content reposts are not news
        String signature = signature(n), before = shown.put(sbn.getKey(), signature);
        if (extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0) return;
        if (before != null && (before.equals(signature) || (n.flags & Notification.FLAG_ONLY_ALERT_ONCE) != 0)) return;
        if (!alerting) return;
        Signal signal = new Signal(Signal.Kind.NOTIFICATION, appLabel(pkg), str(title), str(text), appIcon(pkg),
                largeIcon(n), n.color, sbn.getPostTime());
        if (Notification.CATEGORY_MESSAGE.equals(n.category) || extras.containsKey(Notification.EXTRA_MESSAGES)) latestMessage = signal;
        signals.add(signal);
        while (signals.size() > 5) signals.poll();
    }
    void removed(StatusBarNotification sbn) {
        shown.remove(sbn.getKey());
        if (live.remove(sbn.getKey()) != null) publishShowing();
        if (sbn.getPackageName().equals(chosen(MAPS)) && isOngoing(sbn.getNotification())) navigation = null;
    }
    void listener(boolean connected) {
        listenerConnected = connected;
        if (connected) bindMedia();
        else { live.clear(); publishShowing(); }
    }
    void resetShowing() { live.clear(); }

    // same junk filter as the island minus the alerting bit, silent stuff still shows in the shade so it counts
    private void track(StatusBarNotification sbn, int importance) {
        Notification n = sbn.getNotification();
        String pkg = sbn.getPackageName();
        boolean keep = importance > android.app.NotificationManager.IMPORTANCE_MIN && !pkg.equals(context.getPackageName())
                && !isOngoing(n) && (n.flags & Notification.FLAG_GROUP_SUMMARY) == 0
                && !Notification.CATEGORY_TRANSPORT.equals(n.category) && !Notification.CATEGORY_SERVICE.equals(n.category);
        CharSequence title = n.extras.getCharSequence(Notification.EXTRA_TITLE), text = n.extras.getCharSequence(Notification.EXTRA_TEXT);
        if (keep && title == null && text == null) keep = false;
        if (!keep) { if (live.remove(sbn.getKey()) != null) publishShowing(); return; }
        live.remove(sbn.getKey());
        live.put(sbn.getKey(), new Signal(Signal.Kind.NOTIFICATION, appLabel(pkg), str(title), str(text), cachedIcon(pkg),
                largeIcon(n), n.color, sbn.getPostTime()));
        publishShowing();
    }
    private void publishShowing() {
        List<Signal> list = new ArrayList<>(live.values());
        Collections.sort(list, (a, b) -> Long.compare(b.when, a.when));
        showing = Collections.unmodifiableList(list.size() > 12 ? new ArrayList<>(list.subList(0, 12)) : list);
    }
    // every post was re-rendering the app icon, cache it
    private final java.util.HashMap<String, Bitmap> iconCache = new java.util.HashMap<>();
    private Bitmap cachedIcon(String pkg) {
        if (!iconCache.containsKey(pkg)) iconCache.put(pkg, appIcon(pkg));
        return iconCache.get(pkg);
    }

    // nav apps stick the current turn in an ongoing notif
    private void navigation(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        Bundle extras = n.extras;
        String instruction = str(extras.getCharSequence(Notification.EXTRA_TITLE));
        String detail = str(extras.getCharSequence(Notification.EXTRA_TEXT));
        String eta = str(extras.getCharSequence(Notification.EXTRA_SUB_TEXT));
        if (instruction.isEmpty() && detail.isEmpty()) return;
        Bitmap maneuver = largeIcon(n);
        if (maneuver == null && n.getSmallIcon() != null) maneuver = bitmap(n.getSmallIcon().loadDrawable(context), 96);
        navigation = new Navigation(instruction, detail, eta, maneuver);
        if (!instruction.equals(lastInstruction)) {
            lastInstruction = instruction;
            signals.add(new Signal(Signal.Kind.NAVIGATION, appLabel(sbn.getPackageName()), instruction, detail,
                    appIcon(sbn.getPackageName()), maneuver, 0, System.currentTimeMillis()));
        }
    }

    // media
    private final MediaController.Callback mediaCallback = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata metadata) { publishMedia(); }
        @Override public void onPlaybackStateChanged(PlaybackState state) { publishMedia(); }
        @Override public void onSessionDestroyed() { setController(null); }
    };
    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged = list -> bindMedia();
    private boolean watchingSessions;

    private void bindMedia() {
        String pkg = chosen(MEDIA);
        MediaSessionManager sessions = context.getSystemService(MediaSessionManager.class);
        ComponentName listener = new ComponentName(context, PetalNotificationListener.class);
        MediaController found = null;
        try {
            if (!watchingSessions) { sessions.addOnActiveSessionsChangedListener(sessionsChanged, listener, main); watchingSessions = true; }
            if (pkg != null) for (MediaController c : sessions.getActiveSessions(listener))
                if (pkg.equals(c.getPackageName())) { found = c; break; }
        } catch (SecurityException noAccess) { /* no notif access yet */ }
        // paused sessions fall off the active list for a sec, don't bin a perfectly good controller over it
        if (found == null && controller != null && controller.getPackageName().equals(pkg)) found = controller;
        setController(found);
    }
    private void setController(MediaController next) {
        // same session = keep the old object. callbacks hang off it by a weak ref, swap it and gc eats them
        // which is why track info and the play button froze
        if (controller != null && next != null && controller.getSessionToken().equals(next.getSessionToken())) { publishMedia(); return; }
        if (controller != null) controller.unregisterCallback(mediaCallback);
        if (next != null) next.registerCallback(mediaCallback, main);
        controller = next;
        publishMedia();
    }
    private void publishMedia() {
        MediaController c = controller;
        if (c == null) { media = null; return; }
        MediaMetadata meta = c.getMetadata();
        PlaybackState state = c.getPlaybackState();
        String title = meta == null ? "" : str(meta.getText(MediaMetadata.METADATA_KEY_TITLE));
        String artist = meta == null ? "" : str(firstNonNull(meta.getText(MediaMetadata.METADATA_KEY_ARTIST),
                meta.getText(MediaMetadata.METADATA_KEY_ALBUM_ARTIST), meta.getText(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)));
        Bitmap art = null;
        if (meta != null) {
            art = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) art = meta.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (art == null) art = meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
            if (art != null) art = scaled(art, 256);
        }
        long duration = meta == null ? 0 : Math.max(0, meta.getLong(MediaMetadata.METADATA_KEY_DURATION));
        boolean playing = state != null && state.getState() == PlaybackState.STATE_PLAYING;
        media = new Media(title, artist, art, duration, state == null ? 0 : Math.max(0, state.getPosition()),
                state == null ? SystemClock.elapsedRealtime() : state.getLastPositionUpdateTime(),
                state == null ? 1f : state.getPlaybackSpeed(), playing);
        String track = title + "\u0000" + artist;
        if (playing && !title.isEmpty() && !track.equals(lastTrack)) {
            lastTrack = track;
            App app = chosenApp(MEDIA);
            signals.add(new Signal(Signal.Kind.MEDIA, app == null ? "" : app.label, title, artist,
                    app == null ? null : app.icon, art, 0, System.currentTimeMillis()));
        }
    }

    // car buttons, no live session -> poke the app awake via its browser service
    public void mediaAction(int action) {
        main.post(() -> {
            MediaController c = controller;
            if (c != null) {
                MediaController.TransportControls t = c.getTransportControls();
                PlaybackState state = c.getPlaybackState();
                boolean playing = state != null && state.getState() == PlaybackState.STATE_PLAYING;
                if (action == PLAY) { if (!playing) t.play(); }
                else if (action == 0) { if (playing) t.pause(); else t.play(); }
                else if (action == 1) t.skipToNext();
                else if (action == -1) t.skipToPrevious();
                return;
            }
            App app = chosenApp(MEDIA);
            if (app == null || (action != 0 && action != PLAY)) return;
            if (app.browser == null) { wakeWithMediaKey(app.pkg); return; }
            if (browser != null) browser.disconnect();
            browser = new MediaBrowser(context, app.browser, new MediaBrowser.ConnectionCallback() {
                @Override public void onConnected() {
                    MediaController woke = new MediaController(context, browser.getSessionToken());
                    woke.getTransportControls().play();
                    setController(woke);
                }
                @Override public void onConnectionFailed() { Log.w("Catback", "Media app refused browser connection"); }
            }, null);
            browser.connect();
        });
    }

    // media3 stuff w/o a browser service, fake a headset button. only works once the app has seen one before
    private void wakeWithMediaKey(String pkg) {
        for (int action : new int[]{android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP}) {
            Intent key = new Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(pkg)
                    .putExtra(Intent.EXTRA_KEY_EVENT, new android.view.KeyEvent(action, android.view.KeyEvent.KEYCODE_MEDIA_PLAY));
            try { context.sendBroadcast(key); } catch (RuntimeException ignored) { }
        }
        // system media button routing too, might wake the last player
        android.media.AudioManager audio = context.getSystemService(android.media.AudioManager.class);
        audio.dispatchMediaKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PLAY));
        audio.dispatchMediaKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PLAY));
    }

    // helpers
    private static boolean isOngoing(Notification n) { return (n.flags & Notification.FLAG_ONGOING_EVENT) != 0; }
    private static String str(CharSequence text) { return text == null ? "" : text.toString(); }
    @SafeVarargs private static <T> T firstNonNull(T... values) { for (T v : values) if (v != null) return v; return null; }
    private String appLabel(String pkg) {
        try { PackageManager pm = context.getPackageManager(); return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); }
        catch (PackageManager.NameNotFoundException e) { return pkg; }
    }
    private Bitmap appIcon(String pkg) {
        try { return bitmap(context.getPackageManager().getApplicationIcon(pkg), 96); }
        catch (PackageManager.NameNotFoundException e) { return null; }
    }
    private Bitmap largeIcon(Notification n) {
        Icon icon = n.getLargeIcon();
        if (icon == null) return null;
        try { return bitmap(icon.loadDrawable(context), 128); } catch (RuntimeException e) { return null; }
    }
    static Bitmap bitmap(Drawable drawable, int size) {
        if (drawable == null) return null;
        if (drawable instanceof BitmapDrawable && ((BitmapDrawable) drawable).getBitmap() != null)
            return scaled(((BitmapDrawable) drawable).getBitmap(), size);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, size, size); drawable.draw(canvas);
        return bitmap;
    }
    private static Bitmap scaled(Bitmap source, int max) {
        int w = source.getWidth(), h = source.getHeight();
        if (w <= max && h <= max) return source.copy(Bitmap.Config.ARGB_8888, false);
        float k = (float) max / Math.max(w, h);
        return Bitmap.createScaledBitmap(source, Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)), true);
    }
    static String lower(String s) { return s.toLowerCase(Locale.ROOT); }
}
