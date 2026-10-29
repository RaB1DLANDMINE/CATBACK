package org.petal.catback;

import android.content.Context;
import android.content.SharedPreferences;

// user prefs, mostly where audio goes
public final class Settings {
    public static final String AUDIO_OUTPUT = "audio_output", CAR = "car", CAR_ROOT = "car_root", PHONE = "phone";
    private Settings() { }
    private static SharedPreferences prefs(Context c) { return c.getApplicationContext().getSharedPreferences("settings", Context.MODE_PRIVATE); }
    // car_root = su helper grabs everything, car = through us, phone = stays put
    public static String audioOutput(Context c) { return prefs(c).getString(AUDIO_OUTPUT, PHONE); }
    public static void setAudioOutput(Context c, String value) { prefs(c).edit().putString(AUDIO_OUTPUT, value).apply(); }
}
