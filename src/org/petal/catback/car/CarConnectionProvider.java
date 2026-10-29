package org.petal.catback.car;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

// androidx CarConnection asks this to see if a car's plugged in. 2 = projection, flips apps into "use the car screen" mode
public final class CarConnectionProvider extends ContentProvider {
    private static volatile boolean projecting;

    public static void set(Context context, boolean on) {
        if (projecting == on) return;
        projecting = on;
        context.sendBroadcast(new Intent("androidx.car.app.connection.action.CAR_CONNECTION_UPDATED"));
    }

    @Override public boolean onCreate() { return true; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        MatrixCursor cursor = new MatrixCursor(new String[]{"CarConnectionState"});
        cursor.addRow(new Object[]{projecting ? 2 : 0});
        return cursor;
    }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
