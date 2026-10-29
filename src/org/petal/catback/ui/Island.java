package org.petal.catback.ui;

import android.graphics.RectF;
import org.petal.catback.car.CarData;

// island state machine, IslandConstants springs/dwell (dp/ms)
final class Island {
    private static final float DROPLET = 14, CAPSULE_H = 30, CAPSULE_MIN = 64, CAPSULE_MAX = 176,
            CARD_MAX_W = 420, CARD_H = 104, CORNER_CAPSULE = 15, CORNER_CARD = 28, TOP = 9, TOP_CARD = 12;
    private static final float AUTO_EXPAND = .6f, DWELL_EXPANDED = 3f, DWELL_COLLAPSED = 2f, DWELL_OTHER = 4f;

    // damped spring, k stiffness z damping, mass 1
    private static final class Spring {
        float v, target, vel, k = 380, z = .78f;
        Spring(float v) { this.v = v; target = v; }
        void to(float t, float k, float z) { target = t; this.k = k; this.z = z; }
        // max 4ms substeps or it explodes at low fps
        void step(float dt) {
            float c = 2 * z * (float) Math.sqrt(k);
            int n = Math.max(1, (int) Math.ceil(dt / .004f));
            float h = dt / n;
            for (int i = 0; i < n; i++) {
                float a = -k * (v - target) - c * vel;
                vel += a * h; v += vel * h;
            }
        }
    }

    // real text widths from the renderer, 0 reg 1 semi 2 bold
    interface Measure { float width(String text, float size, int style); }
    Measure measure = (text, size, style) -> text.length() * size * .55f;
    // header line shared so width matches what's drawn
    static String header(CarData.Signal s) {
        return s.app + (s.kind == CarData.Signal.Kind.NOTIFICATION ? "  \u00b7  now"
                : s.kind == CarData.Signal.Kind.MEDIA ? "  \u00b7  now playing" : "  \u00b7  next turn");
    }

    private final Spring w = new Spring(0), h = new Spring(0), radius = new Spring(0), y = new Spring(TOP);
    private final RectF bounds = new RectF();
    CarData.Signal signal;
    boolean expanded;
    private boolean visible, manual;
    // 0 capsule -> 1 card -> 2 capsule -> gone
    private int phase;
    private float now, last, shownAt, changedAt = -1, sheenAt = -10, neonAt = -10, hiddenAt = -10, dwellUntil;
    private float layoutWidth = 800;

    void step(CarData data, float time) {
        float gap = last == 0 ? 0 : time - last;
        if (gap > .25f) {
            // first shader compile hitch shouldn't eat dwell time
            float lost = gap - 1 / 60f;
            shownAt += lost; changedAt += lost; sheenAt += lost; neonAt += lost; hiddenAt += lost;
            if (dwellUntil != 0) dwellUntil += lost;
        }
        float dt = Math.min(1 / 30f, gap);
        last = now = time;
        w.step(dt); h.step(dt); radius.step(dt); y.step(dt);
        if (!visible && now - hiddenAt > .35f) {
            CarData.Signal next = data.nextSignal();
            if (next != null) show(next, next.kind == CarData.Signal.Kind.NAVIGATION);
            return;
        }
        if (!visible || dwellUntil == 0 || now < dwellUntil) return;
        if (signal != null && signal.kind == CarData.Signal.Kind.NOTIFICATION && !manual) {
            if (phase == 0) { expand(); phase = 1; dwellUntil = now + DWELL_EXPANDED; }
            else if (phase == 1) { collapse(); phase = 2; dwellUntil = now + DWELL_COLLAPSED; }
            else dismiss();
        } else dismiss();
    }

    void show(CarData.Signal next, boolean open) {
        boolean fromHidden = !visible;
        signal = next; visible = true; manual = open && next.kind == CarData.Signal.Kind.NOTIFICATION;
        shownAt = now; sheenAt = now; neonAt = now;
        if (fromHidden) { // droplet first, 14dp, 300/.68
            w.v = 10; h.v = 10; radius.v = 5; w.vel = h.vel = 0;
        }
        phase = open ? 1 : 0;
        if (open) expand();
        else collapse();
        boolean notification = next.kind == CarData.Signal.Kind.NOTIFICATION;
        dwellUntil = now + (open ? (manual ? 5f : DWELL_OTHER) : notification ? AUTO_EXPAND : DWELL_OTHER);
    }
    private void expand() {
        expanded = true; changedAt = now; sheenAt = now;
        w.to(cardWidth(), 380, .78f); h.to(CARD_H, 380, .78f);
        radius.to(CORNER_CARD, 380, .78f); y.to(TOP_CARD, 380, .78f);
    }
    private void collapse() {
        expanded = false; changedAt = now;
        w.to(capsuleWidth(), 300, .68f);
        h.to(CAPSULE_H, 300, .68f); radius.to(CORNER_CAPSULE, 300, .68f); y.to(TOP, 520, .9f);
    }
    // capsule fits icon + text (+eq), max 60% width
    private float capsuleWidth() {
        if (signal == null) return CAPSULE_MIN;
        float label = measure.width(signal.title, 12, 2);
        float eq = signal.kind == CarData.Signal.Kind.MEDIA ? 22 : 0;
        return Math.max(CAPSULE_MIN, Math.min(layoutWidth * .6f, 6 + 18 + 6 + label + 10 + eq));
    }
    // card fits its widest line, max full width
    private float cardWidth() {
        if (signal == null) return CARD_MAX_W;
        float avatar = signal.avatar != null ? 52 : 0;
        float header = 14 + 22 + measure.width(header(signal), 12, 2) + 14;
        float body = 14 + avatar + Math.max(measure.width(signal.title, 15, 1), measure.width(signal.text, 13, 0)) + 14;
        return Math.max(200, Math.min(layoutWidth - 24, Math.max(header, body)));
    }
    void dismiss() {
        if (!visible) return;
        visible = false; expanded = false; hiddenAt = now; dwellUntil = 0;
        w.to(0, 520, 1f); h.to(0, 520, 1f); radius.to(0, 520, 1f); y.to(TOP, 520, 1f);
    }
    void tap() {
        if (!visible) return;
        if (expanded) dismiss();
        else { expand(); manual = true; phase = 1; dwellUntil = now + 5f; }
    }
    void navigation(CarData data) {
        CarData.Navigation nav = data.navigation;
        CarData.App app = data.chosenApp(CarData.MAPS);
        if (nav != null) show(new CarData.Signal(CarData.Signal.Kind.NAVIGATION, app == null ? "" : app.label,
                nav.instruction, nav.detail, app == null ? null : app.icon, nav.maneuver, 0, System.currentTimeMillis()), true);
    }
    void nowPlaying(CarData data) {
        CarData.Media media = data.media;
        CarData.App app = data.chosenApp(CarData.MEDIA);
        if (media != null && !media.title.isEmpty()) show(new CarData.Signal(CarData.Signal.Kind.MEDIA, app == null ? "" : app.label,
                media.title, media.artist, app == null ? null : app.icon, media.art, 0, System.currentTimeMillis()), false);
    }

    RectF bounds(float layoutWidth) {
        this.layoutWidth = layoutWidth;
        float bw = Math.max(0, w.v), bh = Math.max(0, h.v);
        bounds.set((layoutWidth - bw) / 2, y.v, (layoutWidth + bw) / 2, y.v + bh);
        return bounds;
    }
    float radius() { return Math.max(0, Math.min(radius.v, Math.min(w.v, h.v) / 2)); }
    // 700ms sheen
    float sheen(float t) { float p = (t - sheenAt) / .7f; return p >= 0 && p <= 1 ? p : -1; }
    // 1500ms neon comet
    float neon(float t) { float p = (t - neonAt) / 1.5f; return p >= 0 && p <= 1 ? p : -1; }
    // 120 out, 160 in on every resize
    float contentAlpha() {
        if (!visible) return 0;
        return Math.max(0, Math.min(1, (now - changedAt - .12f) / .16f));
    }
}
