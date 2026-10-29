package org.petal.catback.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import androidx.car.app.OnDoneCallback;
import androidx.car.app.model.Action;
import androidx.car.app.model.ActionStrip;
import androidx.car.app.model.CarColor;
import androidx.car.app.model.CarIcon;
import androidx.car.app.model.CarText;
import androidx.car.app.model.Distance;
import androidx.car.app.model.GridItem;
import androidx.car.app.model.GridTemplate;
import androidx.car.app.model.Header;
import androidx.car.app.model.Item;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.ListTemplate;
import androidx.car.app.model.LongMessageTemplate;
import androidx.car.app.model.MessageTemplate;
import androidx.car.app.model.Pane;
import androidx.car.app.model.PaneTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.SearchTemplate;
import androidx.car.app.model.SectionedItemList;
import androidx.car.app.model.Template;
import androidx.car.app.model.Toggle;
import androidx.car.app.navigation.model.MapController;
import androidx.car.app.navigation.model.MapWithContentTemplate;
import androidx.car.app.navigation.model.MessageInfo;
import androidx.car.app.navigation.model.NavigationTemplate;
import androidx.car.app.navigation.model.PlaceListNavigationTemplate;
import androidx.car.app.navigation.model.RoutePreviewNavigationTemplate;
import androidx.car.app.navigation.model.RoutingInfo;
import androidx.car.app.navigation.model.Step;
import androidx.car.app.navigation.model.TravelEstimate;
import androidx.car.app.navigation.model.Trip;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import org.petal.catback.R;
import org.petal.catback.host.CarAppHost;

// draws a car app lib app in the maps panel: its map surface + our take on its templates. compact AA basically
final class CarAppPanel {
    private static final int CARD = 0xE61A1C24, BUTTON = 0xCC22242E;
    private final Context context;
    private final CarUi ui;
    private final Paint image = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG), paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF r = new RectF();
    // icons decoded per template, binned when the template changes so other apps' resources don't pile up
    private final IdentityHashMap<CarIcon, Bitmap> icons = new IdentityHashMap<>();
    private Template iconsFor;
    private Template scrolledFor;
    float listScroll, listMaxScroll;
    final RectF listBox = new RectF();
    // where the overlays sit, the map's visible area is whatever's left
    final RectF cardArea = new RectF();

    CarAppPanel(Context context, CarUi ui) { this.context = context; this.ui = ui; }

    void draw(Canvas c, RectF box, CarAppHost host, Bitmap frame) {
        canvas = c;
        Template t = host.template;
        if (t != iconsFor) { icons.clear(); iconsFor = t; }
        if (t != scrolledFor) { listScroll = 0; scrolledFor = t; }
        listBox.setEmpty(); cardArea.setEmpty();
        int saved = c.save();
        clip.reset(); clip.addRoundRect(box, 24, 24, Path.Direction.CW); c.clipPath(clip);
        if (frame != null) c.drawBitmap(frame, null, box, image);
        else { paint.setColor(0xFF15171E); c.drawRect(box, paint); }
        if (t == null) {
            ui.centered(c, host.status, box.centerX(), box.centerY(), 15, ui.semibold, CarUi.TEXT2, box.width() - 40);
        } else if (t instanceof NavigationTemplate) navigation(c, box, (NavigationTemplate) t, host);
        else if (t instanceof MapWithContentTemplate) {
            MapWithContentTemplate m = (MapWithContentTemplate) t;
            MapController mc = m.getMapController();
            if (mc != null) mapStrip(c, box, mc.getMapActionStrip(), host);
            strip(c, box, m.getActionStrip(), host);
            if (m.getContentTemplate() != null) content(c, sideCard(box), m.getContentTemplate(), host);
        } else if (t instanceof PlaceListNavigationTemplate) {
            PlaceListNavigationTemplate p = (PlaceListNavigationTemplate) t;
            mapStrip(c, box, p.getMapActionStrip(), host); strip(c, box, p.getActionStrip(), host);
            list(c, sideCard(box), header(p.getHeader(), p.getTitle(), p.getHeaderAction()), p.getItemList(), null, p.isLoading(), host);
        } else if (t instanceof RoutePreviewNavigationTemplate) {
            RoutePreviewNavigationTemplate p = (RoutePreviewNavigationTemplate) t;
            mapStrip(c, box, p.getMapActionStrip(), host); strip(c, box, p.getActionStrip(), host);
            List<Action> go = new ArrayList<>();
            if (p.getNavigateAction() != null) go.add(p.getNavigateAction());
            list(c, sideCard(box), header(p.getHeader(), p.getTitle(), p.getHeaderAction()), p.getItemList(), go, p.isLoading(), host);
        } else {
            // not a map screen (settings, lists, search...) so cover the map
            r.set(box); r.inset(8, 8);
            content(c, new RectF(r), t, host);
        }
        toast(c, box, host);
        c.restoreToCount(saved);
    }

    // ---- navigation
    private void navigation(Canvas c, RectF box, NavigationTemplate t, CarAppHost host) {
        mapStrip(c, box, t.getMapActionStrip(), host);
        strip(c, box, t.getActionStrip(), host);
        Object info = t.getNavigationInfo();
        Step step = null, next = null; Distance distance = null; boolean loading = false;
        if (info instanceof RoutingInfo) {
            RoutingInfo ri = (RoutingInfo) info;
            step = ri.getCurrentStep(); next = ri.getNextStep(); distance = ri.getCurrentDistance(); loading = ri.isLoading();
        } else if (info == null) {
            // some apps only push the trip
            Trip trip = host.trip;
            if (trip != null && !trip.getSteps().isEmpty()) {
                step = trip.getSteps().get(0);
                if (!trip.getStepTravelEstimates().isEmpty()) distance = trip.getStepTravelEstimates().get(0).getRemainingDistance();
            }
        }
        if (info instanceof MessageInfo) {
            MessageInfo m = (MessageInfo) info;
            RectF card = turnCard(box, 64);
            float tx = card.left + 14;
            Bitmap img = bitmap(m.getImage(), 72);
            if (img != null) { r.set(card.left + 10, card.top + 10, card.left + 54, card.top + 54); ui.bitmapFit(c, img, r, 0); tx = card.left + 64; }
            ui.label(c, text(m.getTitle()), tx, card.top + 28, 16, ui.semibold, CarUi.TEXT, card.right - 12 - tx);
            ui.label(c, text(m.getText()), tx, card.top + 48, 13, ui.regular, CarUi.TEXT2, card.right - 12 - tx);
        } else if (loading) {
            RectF card = turnCard(box, 52);
            ui.label(c, "Working out the route…", card.left + 16, card.top + 32, 15, ui.semibold, CarUi.TEXT, card.width() - 32);
        } else if (step != null) {
            RectF card = turnCard(box, next != null ? 104 : 76);
            float tile = 52;
            r.set(card.left + 12, card.top + 12, card.left + 12 + tile, card.top + 12 + tile);
            Bitmap turn = step.getManeuver() != null ? bitmap(step.getManeuver().getIcon(), 104) : null;
            if (turn != null) ui.bitmapFit(c, turn, r, 2); else ui.icon(c, R.drawable.car_turn, r.left + 8, r.top + 8, 36, CarUi.ACCENT);
            float tx = r.right + 12, maxW = card.right - 12 - tx;
            ui.label(c, distance(distance), tx, card.top + 34, 22, ui.bold, CarUi.TEXT, maxW);
            ui.label(c, text(step.getCue()), tx, card.top + 56, 14, ui.medium, CarUi.TEXT2, maxW);
            if (next != null) {
                paint.setColor(0x1FFFFFFF); c.drawRect(card.left + 12, card.top + 74, card.right - 12, card.top + 75, paint);
                Bitmap then = next.getManeuver() != null ? bitmap(next.getManeuver().getIcon(), 48) : null;
                float nx = card.left + 14;
                ui.label(c, "Then", nx, card.top + 94, 12, ui.semibold, CarUi.TEXT2);
                nx += 38;
                if (then != null) { r.set(nx, card.top + 80, nx + 18, card.top + 98); ui.bitmapFit(c, then, r, 0); nx += 24; }
                ui.label(c, text(next.getCue()), nx, card.top + 94, 12, ui.regular, CarUi.TEXT2, card.right - 12 - nx);
            }
        }
        TravelEstimate eta = t.getDestinationTravelEstimate();
        if (eta == null && host.trip != null && !host.trip.getDestinationTravelEstimates().isEmpty())
            eta = host.trip.getDestinationTravelEstimates().get(0);
        if (eta != null) etaChip(c, box, eta);
    }
    private RectF turnCard(RectF box, float h) {
        RectF card = new RectF(box.left + 10, box.top + 10, box.left + 10 + Math.min(300, box.width() * .62f), box.top + 10 + h);
        ui.fill(c(), card, 18, CARD);
        cardArea.union(card);
        return card;
    }
    private Canvas canvas;
    private Canvas c() { return canvas; }

    private void etaChip(Canvas c, RectF box, TravelEstimate eta) {
        List<String> parts = new ArrayList<>();
        if (eta.getArrivalTimeAtDestination() != null)
            parts.add(DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(eta.getArrivalTimeAtDestination().getTimeSinceEpochMillis())));
        long seconds = eta.getRemainingTimeSeconds();
        if (seconds >= 0) parts.add(seconds >= 3600 ? (seconds / 3600) + " h " + (seconds % 3600) / 60 + " min" : Math.max(1, seconds / 60) + " min");
        if (eta.getRemainingDistance() != null) parts.add(distance(eta.getRemainingDistance()));
        String s = String.join("  ·  ", parts);
        ui.measure.setTypeface(ui.semibold); ui.measure.setTextSize(14);
        float w = ui.measure.measureText(s) + 28;
        RectF chip = new RectF(box.left + 10, box.bottom - 44, box.left + 10 + w, box.bottom - 10);
        ui.fill(c, chip, 17, CARD);
        cardArea.union(chip);
        ui.label(c, s, chip.left + 14, chip.top + 22, 14, ui.semibold, CarUi.TEXT);
    }

    // ---- action strips: app buttons top right, map controls down the right edge
    private void strip(Canvas c, RectF box, ActionStrip strip, CarAppHost host) {
        if (strip == null) return;
        float x = box.right - 10, y = box.top + 10;
        List<Action> actions = strip.getActions();
        for (int i = actions.size() - 1; i >= 0; i--) {
            Action a = actions.get(i);
            if (a.getType() == Action.TYPE_APP_ICON) continue;
            float w = buttonWidth(a);
            RectF b = new RectF(x - w, y, x, y + 40);
            button(c, b, a, host);
            x -= w + 8;
        }
    }
    private void mapStrip(Canvas c, RectF box, ActionStrip strip, CarAppHost host) {
        if (strip == null) return;
        List<Action> actions = strip.getActions();
        float y = box.bottom - 10 - actions.size() * 48 + 8;
        for (Action a : actions) {
            if (a.getType() == Action.TYPE_APP_ICON) continue;
            RectF b = new RectF(box.right - 50, y, box.right - 10, y + 40);
            button(c, b, a, host);
            y += 48;
        }
    }
    private float buttonWidth(Action a) {
        if (a.getIcon() != null || a.getType() == Action.TYPE_BACK || a.getType() == Action.TYPE_PAN) return 40;
        ui.measure.setTypeface(ui.semibold); ui.measure.setTextSize(13);
        return Math.min(160, ui.measure.measureText(text(a.getTitle())) + 28);
    }
    private void button(Canvas c, RectF b, Action a, CarAppHost host) {
        int bg = a.getBackgroundColor() != null ? color(a.getBackgroundColor(), BUTTON) : BUTTON;
        ui.fill(c, b, b.height() / 2, bg);
        if (a.getType() == Action.TYPE_BACK) backArrow(c, b);
        else {
            Bitmap icon = bitmap(a.getIcon(), 64);
            if (icon != null && b.width() <= 44) { r.set(b); r.inset(10, 10); ui.bitmapFit(c, icon, r, 0); }
            else ui.centered(c, text(a.getTitle()), b.centerX(), b.centerY() + 5, 13, ui.semibold,
                    a.isEnabled() ? CarUi.TEXT : CarUi.TEXT2, b.width() - 16);
        }
        cardArea.union(b);
        if (a.isEnabled()) ui.hit(b, new RectF(b), () -> host.press(a));
    }
    private void backArrow(Canvas c, RectF b) {
        paint.setColor(CarUi.TEXT); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2.5f); paint.setStrokeCap(Paint.Cap.ROUND);
        c.drawLine(b.centerX() + 4, b.centerY() - 8, b.centerX() - 4, b.centerY(), paint);
        c.drawLine(b.centerX() - 4, b.centerY(), b.centerX() + 4, b.centerY() + 8, paint);
        paint.setStyle(Paint.Style.FILL); paint.setStrokeCap(Paint.Cap.BUTT);
    }

    // ---- content templates (lists, panes, messages, grids, search)
    private RectF sideCard(RectF box) {
        RectF card = new RectF(box.left + 10, box.top + 10, box.left + 10 + Math.min(320, box.width() * .58f), box.bottom - 10);
        cardArea.union(card);
        return card;
    }
    private static final class Head { final String title; final Action start; final List<Action> end;
        Head(String title, Action start, List<Action> end) { this.title = title; this.start = start; this.end = end; } }
    private static Head header(Header h, CarText title, Action headerAction) {
        if (h != null) return new Head(text(h.getTitle()), h.getStartHeaderAction(), h.getEndHeaderActions());
        return new Head(text(title), headerAction, new ArrayList<>());
    }
    private void content(Canvas c, RectF card, Template t, CarAppHost host) {
        if (t instanceof ListTemplate) {
            ListTemplate l = (ListTemplate) t;
            Head head = header(l.getHeader(), l.getTitle(), l.getHeaderAction());
            if (l.getSingleList() != null) list(c, card, head, l.getSingleList(), l.getActions(), l.isLoading(), host);
            else {
                // sections squashed into one list w/ the section names as dividers, good enough on a panel this size
                List<Object> flat = new ArrayList<>();
                for (SectionedItemList s : l.getSectionedLists()) { flat.add(text(s.getHeader())); flat.addAll(s.getItemList().getItems()); }
                rows(c, card, head, flat, null, l.getActions(), l.isLoading(), host);
            }
        } else if (t instanceof PaneTemplate) {
            PaneTemplate p = (PaneTemplate) t; Pane pane = p.getPane();
            rows(c, card, header(p.getHeader(), p.getTitle(), p.getHeaderAction()), new ArrayList<>(pane.getRows()), null,
                    pane.getActions(), pane.isLoading(), host);
        } else if (t instanceof GridTemplate) {
            GridTemplate g = (GridTemplate) t;
            list(c, card, header(g.getHeader(), g.getTitle(), g.getHeaderAction()), g.getSingleList(), g.getActions(), g.isLoading(), host);
        } else if (t instanceof MessageTemplate) {
            MessageTemplate m = (MessageTemplate) t;
            message(c, card, header(m.getHeader(), m.getTitle(), m.getHeaderAction()), text(m.getMessage()), m.getActions(), host);
        } else if (t instanceof LongMessageTemplate) {
            LongMessageTemplate m = (LongMessageTemplate) t;
            message(c, card, header(null, m.getTitle(), m.getHeaderAction()), text(m.getMessage()), m.getActions(), host);
        } else if (t instanceof SearchTemplate) {
            SearchTemplate s = (SearchTemplate) t;
            String hint = s.getSearchHint() != null ? s.getSearchHint() : "Search";
            list(c, card, new Head(hint + " (type on your phone)", s.getHeaderAction(), new ArrayList<>()), s.getItemList(), null, s.isLoading(), host);
        } else {
            ui.fill(c, card, 20, CARD);
            ui.centered(c, "This screen isn't supported yet", card.centerX(), card.centerY(), 14, ui.medium, CarUi.TEXT2, card.width() - 24);
        }
    }
    private void list(Canvas c, RectF card, Head head, ItemList items, List<Action> actions, boolean loading, CarAppHost host) {
        rows(c, card, head, items == null ? new ArrayList<>() : new ArrayList<>(items.getItems()), items, actions, loading, host);
    }
    private float headerBar(Canvas c, RectF card, Head head, CarAppHost host) {
        ui.fill(c, card, 20, CARD);
        float x = card.left + 14;
        if (head.start != null && head.start.getType() != Action.TYPE_APP_ICON) {
            RectF b = new RectF(card.left + 8, card.top + 8, card.left + 44, card.top + 44);
            button(c, b, head.start, host); x = b.right + 8;
        }
        float right = card.right - 8;
        if (head.end != null) for (int i = head.end.size() - 1; i >= 0; i--) {
            Action a = head.end.get(i); float w = buttonWidth(a) - 4;
            RectF b = new RectF(right - w, card.top + 8, right, card.top + 44); button(c, b, a, host); right -= w + 6;
        }
        ui.label(c, head.title, x, card.top + 32, 17, ui.semibold, CarUi.TEXT, right - x - 6);
        return card.top + 52;
    }
    private void rows(Canvas c, RectF card, Head head, List<Object> items, ItemList list, List<Action> actions, boolean loading, CarAppHost host) {
        float top = headerBar(c, card, head, host);
        float bottom = card.bottom - (actions != null && !actions.isEmpty() ? 56 : 8);
        if (loading) { ui.centered(c, "Loading…", card.centerX(), (top + bottom) / 2, 14, ui.medium, CarUi.TEXT2, card.width() - 24); }
        else if (items.isEmpty()) {
            String none = list != null && list.getNoItemsMessage() != null ? text(list.getNoItemsMessage()) : "Nothing here";
            ui.centered(c, none, card.centerX(), (top + bottom) / 2, 14, ui.medium, CarUi.TEXT2, card.width() - 24);
        } else {
            listBox.set(card.left, top, card.right, bottom);
            float rowH = 56, content = 0;
            for (Object o : items) content += o instanceof String ? 30 : rowH;
            listMaxScroll = Math.max(0, content - listBox.height());
            listScroll = Math.max(0, Math.min(listMaxScroll, listScroll));
            int saved = c.save(); c.clipRect(listBox);
            float y = top - listScroll; int index = 0;
            for (Object o : items) {
                if (o instanceof String) {
                    if (y + 30 > top && y < bottom) ui.label(c, (String) o, card.left + 16, y + 22, 12, ui.semibold, CarUi.ACCENT, card.width() - 32);
                    y += 30; continue;
                }
                final int position = index++;
                if (y + rowH > top && y < bottom) row(c, new RectF(card.left + 6, y + 2, card.right - 6, y + rowH - 2), (Item) o, list, position, host);
                y += rowH;
            }
            c.restoreToCount(saved);
        }
        if (actions != null && !actions.isEmpty()) actionsRow(c, card, actions, host);
    }
    private void row(Canvas c, RectF b, Item item, ItemList list, int position, CarAppHost host) {
        String title = "", sub = ""; CarIcon image = null; Runnable tap = null; Toggle toggle = null;
        if (item instanceof Row) {
            Row row = (Row) item;
            title = text(row.getTitle()); image = row.getImage(); toggle = row.getToggle();
            List<CarText> texts = row.getTexts(); if (!texts.isEmpty()) sub = text(texts.get(0));
            if (row.getOnClickDelegate() != null) tap = () -> row.getOnClickDelegate().sendClick(new OnDoneCallback() { });
        } else if (item instanceof GridItem) {
            GridItem g = (GridItem) item;
            title = text(g.getTitle()); sub = text(g.getText()); image = g.getImage();
            if (g.getOnClickDelegate() != null) tap = () -> g.getOnClickDelegate().sendClick(new OnDoneCallback() { });
        }
        if (tap == null && list != null && list.getOnSelectedDelegate() != null)
            tap = () -> list.getOnSelectedDelegate().sendSelected(position, new OnDoneCallback() { });
        final Toggle tg = toggle;
        if (tap == null && tg != null && tg.getOnCheckedChangeDelegate() != null)
            tap = () -> tg.getOnCheckedChangeDelegate().sendCheckedChange(!tg.isChecked(), new OnDoneCallback() { });
        boolean selected = list != null && list.getOnSelectedDelegate() != null && list.getSelectedIndex() == position;
        if (selected) ui.fill(c, b, 14, CarUi.ACCENT_CONTAINER);
        float x = b.left + 10;
        Bitmap icon = bitmap(image, 72);
        if (icon != null) { r.set(x, b.centerY() - 18, x + 36, b.centerY() + 18); ui.bitmapFit(c, icon, r, 0); x += 46; }
        float maxW = b.right - 10 - x - (tg != null ? 46 : 0);
        ui.label(c, title, x, sub.isEmpty() ? b.centerY() + 5 : b.centerY() - 3, 15, ui.semibold, CarUi.TEXT, maxW);
        if (!sub.isEmpty()) ui.label(c, sub, x, b.centerY() + 15, 12, ui.regular, CarUi.TEXT2, maxW);
        if (tg != null) {
            r.set(b.right - 46, b.centerY() - 11, b.right - 8, b.centerY() + 11);
            ui.fill(c, r, 11, tg.isChecked() ? CarUi.ACCENT : 0x33FFFFFF);
            paint.setColor(tg.isChecked() ? CarUi.ON_ACCENT : CarUi.TEXT);
            c.drawCircle(tg.isChecked() ? r.right - 11 : r.left + 11, r.centerY(), 8, paint);
        }
        RectF tapBox = new RectF(b); tapBox.setIntersect(tapBox, listBox);
        if (tap != null && !tapBox.isEmpty()) ui.hit(tapBox, new RectF(b), tap);
    }
    private void message(Canvas c, RectF card, Head head, String body, List<Action> actions, CarAppHost host) {
        float top = headerBar(c, card, head, host);
        // dumb word wrap, messages are short
        ui.measure.setTypeface(ui.regular); ui.measure.setTextSize(15);
        float y = top + 20, maxW = card.width() - 32;
        StringBuilder line = new StringBuilder();
        for (String word : body.split("\\s+")) {
            String tryLine = line.length() == 0 ? word : line + " " + word;
            if (ui.measure.measureText(tryLine) > maxW && line.length() > 0) {
                ui.label(c, line.toString(), card.left + 16, y, 15, ui.regular, CarUi.TEXT, maxW); y += 22; line.setLength(0); line.append(word);
                if (y > card.bottom - 70) break;
            } else { line.setLength(0); line.append(tryLine); }
        }
        if (line.length() > 0 && y <= card.bottom - 70) ui.label(c, line.toString(), card.left + 16, y, 15, ui.regular, CarUi.TEXT, maxW);
        if (actions != null && !actions.isEmpty()) actionsRow(c, card, actions, host);
    }
    private void actionsRow(Canvas c, RectF card, List<Action> actions, CarAppHost host) {
        float x = card.left + 12, y = card.bottom - 48;
        for (Action a : actions) {
            float w = Math.max(80, buttonWidth(a));
            if (x + w > card.right - 12) break;
            RectF b = new RectF(x, y, x + w, y + 38);
            if (a.getBackgroundColor() == null) ui.fill(c, b, 19, CarUi.ACCENT_CONTAINER);
            button(c, b, a, host);
            x += w + 8;
        }
    }

    private void toast(Canvas c, RectF box, CarAppHost host) {
        String s = host.toast;
        if (s == null || SystemClock.elapsedRealtime() - host.toastAt > 3000) return;
        ui.measure.setTypeface(ui.medium); ui.measure.setTextSize(13);
        float w = Math.min(box.width() - 40, ui.measure.measureText(s) + 32);
        RectF t = new RectF(box.centerX() - w / 2, box.bottom - 54, box.centerX() + w / 2, box.bottom - 22);
        ui.fill(c, t, 16, CARD);
        ui.centered(c, s, t.centerX(), t.centerY() + 5, 13, ui.medium, CarUi.TEXT, w - 24);
    }

    // ---- bits
    private Bitmap bitmap(CarIcon icon, int size) {
        if (icon == null) return null;
        if (icons.containsKey(icon)) return icons.get(icon);
        Bitmap out = null;
        try {
            if (icon.getIcon() != null) {
                Drawable d = icon.getIcon().loadDrawable(context);
                if (d != null) {
                    if (icon.getTint() != null) d.setTint(color(icon.getTint(), CarUi.TEXT));
                    int w = d.getIntrinsicWidth() > 0 ? d.getIntrinsicWidth() : size, h = d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : size;
                    float k = (float) size / Math.max(w, h);
                    out = Bitmap.createBitmap(Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)), Bitmap.Config.ARGB_8888);
                    d.setBounds(0, 0, out.getWidth(), out.getHeight()); d.draw(new Canvas(out));
                }
            }
        } catch (RuntimeException broken) { out = null; } // other app's resource we can't read, whatever
        icons.put(icon, out);
        return out;
    }
    private static int color(CarColor color, int fallback) {
        switch (color.getType()) {
            case CarColor.TYPE_CUSTOM: return color.getColorDark() != 0 ? color.getColorDark() : color.getColor();
            case CarColor.TYPE_PRIMARY: return CarUi.ACCENT;
            case CarColor.TYPE_SECONDARY: return CarUi.TEXT2;
            case CarColor.TYPE_RED: return 0xFFE5534B;
            case CarColor.TYPE_GREEN: return 0xFF57C478;
            case CarColor.TYPE_BLUE: return 0xFF4F8EF7;
            case CarColor.TYPE_YELLOW: return 0xFFF2C94C;
            default: return fallback;
        }
    }
    static String text(CarText t) { if (t == null) return ""; CharSequence s = t.toCharSequence(); return s == null ? "" : s.toString(); }
    private static String distance(Distance d) {
        if (d == null) return "";
        double v = d.getDisplayDistance();
        String n = v >= 10 || v == Math.floor(v) ? String.valueOf(Math.round(v)) : String.format(Locale.getDefault(), "%.1f", v);
        switch (d.getDisplayUnit()) {
            case Distance.UNIT_METERS: return n + " m";
            case Distance.UNIT_KILOMETERS: case Distance.UNIT_KILOMETERS_P1: return n + " km";
            case Distance.UNIT_MILES: case Distance.UNIT_MILES_P1: return n + " mi";
            case Distance.UNIT_FEET: return n + " ft";
            case Distance.UNIT_YARDS: return n + " yd";
            default: return n;
        }
    }
}
