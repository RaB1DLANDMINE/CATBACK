package org.petal.catback.car;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

// feeds the island, maps guidance and media
public final class PetalNotificationListener extends NotificationListenerService {
    private static volatile PetalNotificationListener active;

    static void rescan() {
        PetalNotificationListener listener = active;
        if (listener == null) return;
        try {
            CarData data = CarData.get(listener);
            RankingMap ranking = listener.getCurrentRanking();
            data.resetShowing();
            // old notifs aren't news, just remember em
            for (StatusBarNotification sbn : listener.getActiveNotifications()) data.seed(sbn, importance(ranking, sbn));
        } catch (RuntimeException ignored) { }
    }

    @Override public void onListenerConnected() {
        active = this;
        CarData.get(this).listener(true);
        rescan();
    }
    @Override public void onListenerDisconnected() {
        active = null;
        CarData.get(this).listener(false);
    }
    @Override public void onNotificationPosted(StatusBarNotification sbn, RankingMap ranking) {
        // importance decides it, island only dings for DEFAULT+, carousel takes LOW+
        CarData.get(this).posted(sbn, importance(ranking, sbn));
    }
    private static int importance(RankingMap ranking, StatusBarNotification sbn) {
        Ranking r = new Ranking();
        return ranking != null && ranking.getRanking(sbn.getKey(), r) ? r.getImportance() : android.app.NotificationManager.IMPORTANCE_MIN;
    }
    @Override public void onNotificationRemoved(StatusBarNotification sbn) { CarData.get(this).removed(sbn); }
    @Override public void onNotificationRemoved(StatusBarNotification sbn, RankingMap ranking) { CarData.get(this).removed(sbn); }
}
