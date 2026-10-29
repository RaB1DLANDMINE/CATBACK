package org.petal.catback;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.companion.CompanionDeviceManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.petal.catback.audio.AudioBridge;
import org.petal.catback.protocol.WifiBootstrap;
import org.petal.catback.ui.CarUi;

// home screen, nerd stuff hides behind long-pressing the title
public final class MainActivity extends Activity {
    private static final int BACKGROUND = 0xff101c18, CARD = 0xff1d2e28, ACCENT = 0xff9decbf, MUTED = 0xffa9bdb4;
    private static final int SETUP_PERMISSIONS = 2, ASSOCIATE = 3, CAPTURE = 4, AUDIO_PERMISSION = 5, PICK_APK = 6;
    private static final String WALLPAPER_PERMISSION = "android.permission.READ_WALLPAPER_INTERNAL";
    private enum Screen { HOME, SETTINGS, DEVELOPER, PREVIEW }
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Screen screen = Screen.HOME;
    private TextView headline, detail, devStatus, logs;
    private Button primary;
    private TextView changeLink, forgetLink, installedLink;
    private volatile boolean importing;
    private Button access, wallpaper;
    private BluetoothDevice pending;
    private final Runnable update = new Runnable() {
        @Override public void run() {
            if (screen == Screen.HOME && headline != null) refreshHome();
            else if (screen == Screen.SETTINGS) refreshAudioNote();
            else if (screen == Screen.DEVELOPER && devStatus != null) {
                devStatus.setText(ProjectionService.state);
                logs.setText(ProjectionService.logText());
            }
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(true);
        getWindow().setStatusBarColor(BACKGROUND); getWindow().setNavigationBarColor(BACKGROUND);
        showHome();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        processIntent(getIntent());
        handler.post(update);
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); processIntent(intent); }
    private void processIntent(Intent intent) {
        if ("preview".equals(intent.getStringExtra("screen"))) { showPreview(); return; }
        if (UsbManager.ACTION_USB_ACCESSORY_ATTACHED.equals(intent.getAction())) start(ProjectionService.USB);
        else if ("tcp".equals(intent.getStringExtra("transport"))) start(ProjectionService.TCP);
        else if ("wireless".equals(intent.getStringExtra("transport"))) {
            // adb bench: --es transport wireless --es host <ip> [--ei port 5288] or --es device <bt mac>
            Intent wireless = new Intent(this, ProjectionService.class).setAction(ProjectionService.WIRELESS);
            if (intent.hasExtra("host")) wireless.putExtra(ProjectionService.EXTRA_HOST, intent.getStringExtra("host"))
                    .putExtra(ProjectionService.EXTRA_PORT, intent.getIntExtra("port", 5288));
            if (intent.hasExtra("device")) wireless.putExtra(ProjectionService.EXTRA_DEVICE, intent.getStringExtra("device"));
            startForegroundService(wireless);
        }
    }
    private void start(String action) { startForegroundService(new Intent(this, ProjectionService.class).setAction(action)); }

    // home
    private void showHome() {
        screen = Screen.HOME;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout layout = column(56);
        TextView title = text("catback", 34, ACCENT); title.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        title.setOnLongClickListener(v -> { showDeveloper(); return true; });
        layout.addView(title);

        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(24), dp(28), dp(24), dp(28)); card.setBackground(rounded(CARD, 28));
        headline = text("", 26, Color.WHITE); card.addView(headline);
        detail = text("", 16, MUTED); detail.setPadding(0, dp(10), 0, 0); card.addView(detail);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.topMargin = dp(40); layout.addView(card, cardParams);

        primary = new Button(this); primary.setAllCaps(false); primary.setTextSize(17); primary.setTextColor(0xff0b1612);
        primary.setBackground(rounded(ACCENT, 28)); primary.setPadding(0, dp(16), 0, dp(16));
        LinearLayout.LayoutParams primaryParams = new LinearLayout.LayoutParams(-1, -2);
        primaryParams.topMargin = dp(24); layout.addView(primary, primaryParams);
        // island, now playing and turn-by-turn all ride on notif access
        access = new Button(this); access.setAllCaps(false); access.setTextSize(15); access.setTextColor(Color.WHITE);
        access.setBackground(rounded(CARD, 28)); access.setPadding(0, dp(14), 0, dp(14));
        access.setText("Allow notifications on the car display");
        access.setOnClickListener(v -> openNotificationAccess());
        LinearLayout.LayoutParams accessParams = new LinearLayout.LayoutParams(-1, -2);
        accessParams.topMargin = dp(12); layout.addView(access, accessParams);
        // 14+ wants all-files for the wallpaper unless we're the rom build
        wallpaper = new Button(this); wallpaper.setAllCaps(false); wallpaper.setTextSize(15); wallpaper.setTextColor(Color.WHITE);
        wallpaper.setBackground(rounded(CARD, 28)); wallpaper.setPadding(0, dp(14), 0, dp(14));
        wallpaper.setText("Show my wallpaper in the car");
        wallpaper.setOnClickListener(v -> openWallpaperAccess());
        LinearLayout.LayoutParams wallpaperParams = new LinearLayout.LayoutParams(-1, -2);
        wallpaperParams.topMargin = dp(12); layout.addView(wallpaper, wallpaperParams);

        View spacer = new View(this); layout.addView(spacer, new LinearLayout.LayoutParams(-1, 0, 1));
        changeLink = link("Change car", this::chooseCar); forgetLink = link("Forget car", this::confirmForget);
        installedLink = link("Use installed Android Auto", this::importInstalled);
        LinearLayout footer = new LinearLayout(this); footer.setGravity(Gravity.CENTER);
        footer.addView(installedLink); footer.addView(changeLink); footer.addView(forgetLink); footer.addView(link("Settings", this::showSettings)); footer.addView(link("Developer options", this::showDeveloper));
        layout.addView(footer);
        setContentView(layout);
        refreshHome();
    }
    private void refreshHome() {
        String car = CarLink.address(this);
        boolean linked = car != null;
        String status = ProjectionService.headline;
        if (!Identity.carReady(this)) { refreshIdentitySetup(); return; }
        installedLink.setVisibility(View.GONE);
        if (!linked) {
            headline.setText("Set up your car");
            detail.setText("Pair your car with this phone in Bluetooth settings first, then link it here. "
                    + "After that CATBACK starts by itself whenever you get in.");
            primary.setText("Link a car"); primary.setOnClickListener(v -> chooseCar());
        } else {
            headline.setText(CarLink.name(this));
            detail.setText(status.isEmpty() ? "Ready. CATBACK will start when your car connects." : status);
            boolean active = status.startsWith("Connected") || status.startsWith("Connecting") || status.startsWith("Retrying");
            primary.setText(active ? "Disconnect" : "Connect now");
            primary.setOnClickListener(v -> {
                if (active) stopService(new Intent(this, ProjectionService.class));
                else if (bluetoothReady()) CarLink.connect(this);
            });
        }
        access.setVisibility(notificationAccess() ? View.GONE : View.VISIBLE);
        wallpaper.setVisibility(Build.VERSION.SDK_INT >= 30 && !android.os.Environment.isExternalStorageManager()
                && checkSelfPermission(WALLPAPER_PERMISSION) != PackageManager.PERMISSION_GRANTED ? View.VISIBLE : View.GONE);
        changeLink.setVisibility(linked ? View.VISIBLE : View.GONE);
        forgetLink.setVisibility(linked ? View.VISIBLE : View.GONE);
    }
    // no android auto identity = cars hang up on us, so this blocks everything else
    private void refreshIdentitySetup() {
        Date expired = Identity.carExpiry(this);
        headline.setText(expired != null ? "Car identity expired" : "One more step");
        detail.setText(expired != null
                ? "The Android Auto identity CATBACK uses ran out on " + DateFormat.getDateInstance().format(expired)
                    + ". Pick a newer Android Auto APK to keep connecting."
                : "Cars only talk to phones carrying Android Auto's identity. Pick an Android Auto APK "
                    + "(or an .apks / .xapk bundle) and CATBACK copies it out. Android Auto never gets installed or run.");
        primary.setText(importing ? "Reading Android Auto…" : "Choose Android Auto APK");
        primary.setEnabled(!importing);
        primary.setOnClickListener(v -> pickApk());
        installedLink.setVisibility(!importing && gearheadInstalled() ? View.VISIBLE : View.GONE);
        access.setVisibility(View.GONE); wallpaper.setVisibility(View.GONE);
        changeLink.setVisibility(View.GONE); forgetLink.setVisibility(View.GONE);
    }
    private boolean gearheadInstalled() {
        try { getPackageManager().getApplicationInfo(Identity.GEARHEAD, 0); return true; }
        catch (PackageManager.NameNotFoundException e) { return false; }
    }
    private void pickApk() {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        try { startActivityForResult(pick, PICK_APK); }
        catch (android.content.ActivityNotFoundException e) { toast("No file picker on this phone??"); }
    }
    private void importInstalled() {
        String apk;
        try { apk = getPackageManager().getApplicationInfo(Identity.GEARHEAD, 0).sourceDir; }
        catch (PackageManager.NameNotFoundException e) { toast("Android Auto isn't installed"); return; }
        importIdentity(() -> new FileInputStream(apk));
    }
    private interface Opener { InputStream open() throws Exception; }
    private void importIdentity(Opener opener) {
        if (importing) return;
        importing = true;
        if (screen == Screen.HOME) refreshHome(); else if (screen == Screen.SETTINGS) showSettings();
        new Thread(() -> {
            String message;
            try (InputStream in = new BufferedInputStream(opener.open(), 1 << 16)) {
                Date until = Identity.importFrom(getApplicationContext(), in);
                message = "Car identity imported, good until " + DateFormat.getDateInstance().format(until)
                        + ". You can uninstall Android Auto now.";
            } catch (Exception e) {
                message = "Couldn't use that: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            }
            String done = message;
            runOnUiThread(() -> {
                importing = false; toast(done);
                if (screen == Screen.HOME) refreshHome(); else if (screen == Screen.SETTINGS) showSettings();
            });
        }, "Catback-IdentityImport").start();
    }
    private boolean notificationAccess() {
        return getSystemService(android.app.NotificationManager.class).isNotificationListenerAccessGranted(
                new android.content.ComponentName(this, org.petal.catback.car.PetalNotificationListener.class));
    }
    private void openNotificationAccess() {
        Intent intent = Build.VERSION.SDK_INT >= 30
                ? new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                            new android.content.ComponentName(this, org.petal.catback.car.PetalNotificationListener.class).flattenToString())
                : new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        try { startActivity(intent); }
        catch (android.content.ActivityNotFoundException e) { startActivity(new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); }
        toast("If the switch is greyed out: App info → ⋮ → Allow restricted settings, then try again.");
    }
    private void openWallpaperAccess() {
        if (Build.VERSION.SDK_INT < 30) return;
        toast("Android only shares the wallpaper with apps that have all-files access. CATBACK only reads the wallpaper.");
        try {
            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    android.net.Uri.parse("package:" + getPackageName())));
        } catch (android.content.ActivityNotFoundException e) {
            startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }
    private void confirmForget() {
        new AlertDialog.Builder(this).setMessage("Forget " + CarLink.name(this) + "? CATBACK will stop starting automatically.")
                .setPositiveButton("Forget", (d, w) -> { CarLink.forget(this); refreshHome(); })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    // linking
    private boolean bluetoothReady() {
        List<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) missing.add(Manifest.permission.BLUETOOTH_CONNECT);
        if (Build.VERSION.SDK_INT >= 33) missing.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        else { missing.add(Manifest.permission.ACCESS_FINE_LOCATION); missing.add(Manifest.permission.ACCESS_COARSE_LOCATION); }
        missing.removeIf(permission -> checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED);
        if (!missing.isEmpty()) { requestPermissions(missing.toArray(new String[0]), SETUP_PERMISSIONS); return false; }
        return true;
    }
    @SuppressLint("MissingPermission") // bluetoothReady checked it
    private void chooseCar() {
        if (!bluetoothReady()) return;
        BluetoothAdapter adapter = getSystemService(BluetoothManager.class).getAdapter();
        if (adapter == null || !adapter.isEnabled()) { toast("Turn on Bluetooth, then pair your car"); return; }
        // wireless-capable cars float to the top
        List<BluetoothDevice> devices = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (BluetoothDevice device : adapter.getBondedDevices()) {
            boolean projection = advertisesWireless(device);
            String label = (device.getName() != null ? device.getName() : device.getAddress())
                    + (projection ? "  • wireless projection" : "");
            if (projection) { devices.add(0, device); labels.add(0, label); }
            else { devices.add(device); labels.add(label); }
        }
        if (devices.isEmpty()) { toast("Pair your car in Bluetooth settings first"); return; }
        new AlertDialog.Builder(this).setTitle("Which is your car?")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> link(devices.get(which)))
                .setNegativeButton(android.R.string.cancel, null).show();
    }
    @SuppressLint("MissingPermission")
    private static boolean advertisesWireless(BluetoothDevice device) {
        ParcelUuid[] uuids = device.getUuids();
        if (uuids != null) for (ParcelUuid uuid : uuids)
            if (WifiBootstrap.SERVICE.equals(uuid.getUuid()) || WifiBootstrap.REVERSED_SERVICE.equals(uuid.getUuid())) return true;
        return false;
    }
    @SuppressWarnings("deprecation")
    private void link(BluetoothDevice device) {
        CompanionDeviceManager cdm = CarLink.manager(this);
        if (cdm == null) { linked(device, false); return; }
        if (CarLink.associated(this, device.getAddress())) { linked(device, true); return; }
        pending = device;
        // cdm prompt, this grant is what lets us start in the background later
        cdm.associate(CarLink.request(device.getAddress()), new CompanionDeviceManager.Callback() {
            @Override public void onDeviceFound(IntentSender chooser) {
                try { startIntentSenderForResult(chooser, ASSOCIATE, null, 0, 0, 0); }
                catch (IntentSender.SendIntentException e) { toast("Couldn't open the car link prompt"); }
            }
            @Override public void onFailure(CharSequence error) {
                toast("Couldn't link the car" + (error != null ? ": " + error : ""));
            }
        }, handler);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_APK) {
            if (result == RESULT_OK && data != null && data.getData() != null) {
                android.net.Uri uri = data.getData();
                importIdentity(() -> getContentResolver().openInputStream(uri));
            }
            return;
        }
        if (request == CAPTURE) {
            if (result == RESULT_OK && data != null) startForegroundService(new Intent(this, ProjectionService.class)
                    .setAction(ProjectionService.AUDIO_PROJECTION)
                    .putExtra(ProjectionService.EXTRA_RESULT_CODE, result).putExtra(ProjectionService.EXTRA_RESULT_DATA, data));
            else toast("Capture not allowed; audio stays on the phone");
            return;
        }
        if (request != ASSOCIATE) return;
        BluetoothDevice device = pending; pending = null;
        if (result == RESULT_OK && device != null) linked(device, true);
        else toast("Car not linked");
    }
    @SuppressLint("MissingPermission")
    private void linked(BluetoothDevice device, boolean companion) {
        String name = device.getName() != null ? device.getName() : device.getAddress();
        CarLink.save(this, device.getAddress(), name);
        boolean automatic = companion && CarLink.observe(this, device.getAddress());
        if (!companion) toast("Linked. This phone can't start CATBACK in the background; open the app to connect.");
        else if (!automatic && Build.VERSION.SDK_INT >= 33) toast("Linked, but automatic start couldn't be enabled");
        refreshHome();
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        if (request == AUDIO_PERMISSION) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) requestCapture();
            else toast("Audio capture needs the microphone permission slot; audio stays on the phone");
            return;
        }
        if (request != SETUP_PERMISSIONS) return;
        for (int result : results) if (result != PackageManager.PERMISSION_GRANTED) {
            toast("CATBACK needs Bluetooth and Wi-Fi access to reach your car"); return;
        }
        if (screen == Screen.HOME && CarLink.address(this) == null) chooseCar();
    }

    // settings
    private TextView audioNote;
    private void showSettings() {
        screen = Screen.SETTINGS;
        LinearLayout layout = column(56);
        layout.addView(text("Settings", 30, ACCENT));
        TextView section = text("Audio output", 14, MUTED); section.setPadding(0, dp(28), 0, dp(8)); layout.addView(section);
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(CARD, 28)); card.setPadding(dp(8), dp(8), dp(8), dp(8));
        String current = Settings.audioOutput(this);
        if (AudioBridge.systemAvailable(this)) {
            card.addView(audioRow("Car speakers", "All phone media plays on the car's audio system.", !Settings.PHONE.equals(current), Settings.CAR));
        } else {
            card.addView(audioRow("Car speakers (through CATBACK)", "Phone media plays on the car's audio system.", Settings.CAR.equals(current), Settings.CAR));
            card.addView(audioRow("Car speakers: all phone audio (root)", "Every app's sound, no per-app limits. Needs Magisk or KernelSU.", Settings.CAR_ROOT.equals(current), Settings.CAR_ROOT));
        }
        card.addView(audioRow("Phone", "Sound stays on the phone or its own Bluetooth connection.", Settings.PHONE.equals(current), Settings.PHONE));
        layout.addView(card);
        audioNote = text("", 13, MUTED); audioNote.setPadding(dp(4), dp(12), dp(4), 0); layout.addView(audioNote);

        TextView idSection = text("Car identity", 14, MUTED); idSection.setPadding(0, dp(28), 0, dp(8)); layout.addView(idSection);
        Date expiry = Identity.carExpiry(this);
        layout.addView(text(expiry == null ? "Not imported. Stock cars will refuse to connect."
                : (Identity.carReady(this) ? "From Android Auto, good until " : "Expired on ")
                    + DateFormat.getDateInstance().format(expiry), 15, Color.WHITE));
        button(layout, importing ? "Reading Android Auto…" : "Import from an Android Auto APK", this::pickApk);
        if (gearheadInstalled()) button(layout, "Import from installed Android Auto", this::importInstalled);
        setContentView(layout);
        refreshAudioNote();
    }
    private View audioRow(String title, String detail, boolean selected, String value) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        row.setBackground(selected ? rounded(0xFF3B3156, 22) : null);
        row.addView(text(title, 16, Color.WHITE)); row.addView(text(detail, 13, MUTED));
        row.setOnClickListener(v -> chooseAudio(value));
        return row;
    }
    private void chooseAudio(String value) {
        if (Settings.CAR_ROOT.equals(value)) {
            // su pops the magisk/ksu prompt, keep it off the ui thread
            toast("Approve root access for CATBACK if prompted");
            new Thread(() -> {
                boolean ok = org.petal.catback.audio.AudioBridge.rootAvailable();
                runOnUiThread(() -> {
                    if (ok) { Settings.setAudioOutput(this, value); showSettings(); }
                    else toast("Root access wasn't granted, so audio output is unchanged");
                });
            }, "Catback-RootCheck").start();
            return;
        }
        Settings.setAudioOutput(this, value);
        if (Settings.CAR.equals(value) && !AudioBridge.systemAvailable(this)) requestCapture();
        else stopService(new Intent(this, ProjectionService.class).setAction(ProjectionService.STOP));
        showSettings();
    }
    private void requestCapture() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION); return;
        }
        startActivityForResult(getSystemService(android.media.projection.MediaProjectionManager.class).createScreenCaptureIntent(), CAPTURE);
    }
    private void refreshAudioNote() {
        if (audioNote == null) return;
        String output = Settings.audioOutput(this);
        audioNote.setText(Settings.CAR_ROOT.equals(output) || (AudioBridge.systemAvailable(this) && Settings.CAR.equals(output))
                ? ProjectionService.audioStatus
                : Settings.CAR.equals(output)
                ? ProjectionService.audioStatus + "\nAndroid shows a screen-capture prompt for this; CATBACK records audio only, never the screen."
                : "Phone audio stays on the phone.");
    }

    // dev stuff
    private void showDeveloper() {
        screen = Screen.DEVELOPER;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout layout = column(32);
        layout.addView(text("Developer options", 24, ACCENT));
        devStatus = text(ProjectionService.state, 17, Color.WHITE); devStatus.setPadding(0, dp(12), 0, dp(12)); layout.addView(devStatus);
        button(layout, "Start desktop connection", () -> start(ProjectionService.TCP));
        button(layout, "Connect USB head unit", () -> start(ProjectionService.USB));
        button(layout, "Car display preview", this::showPreview);
        button(layout, "Stop projection", () -> stopService(new Intent(this, ProjectionService.class)));
        button(layout, "Copy diagnostics", () -> getSystemService(ClipboardManager.class)
                .setPrimaryClip(ClipData.newPlainText("CATBACK diagnostics", ProjectionService.logText())));
        TextView help = text("Desktop: adb forward tcp:5277 tcp:5277, then start the Desktop Head Unit. "
                + "USB: select CATBACK for the accessory. Wireless bench: tools/wireless_bench_hu.py.", 13, MUTED);
        help.setPadding(0, dp(8), 0, dp(8)); layout.addView(help);
        ScrollView scroll = new ScrollView(this); logs = text(ProjectionService.logText(), 11, Color.WHITE);
        logs.setTypeface(Typeface.MONOSPACE); logs.setTextIsSelectable(true); scroll.addView(logs);
        layout.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(layout);
    }
    private void showPreview() {
        screen = Screen.PREVIEW;
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        setContentView(new Preview());
        if (Build.VERSION.SDK_INT >= 30 && getWindow().getInsetsController() != null)
            getWindow().getInsetsController().hide(android.view.WindowInsets.Type.systemBars());
    }
    @Override public void onBackPressed() {
        if (Build.VERSION.SDK_INT >= 30 && getWindow().getInsetsController() != null)
            getWindow().getInsetsController().show(android.view.WindowInsets.Type.systemBars());
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        if (screen == Screen.SETTINGS) showHome();
        else if (screen == Screen.PREVIEW) showDeveloper();
        else if (screen == Screen.DEVELOPER) showHome();
        else super.onBackPressed();
    }
    @Override public void onDestroy() { handler.removeCallbacks(update); super.onDestroy(); }

    // view junk
    private LinearLayout column(int top) {
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(24), dp(top), dp(24), dp(24)); layout.setBackgroundColor(BACKGROUND);
        return layout;
    }
    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(size); view.setTextColor(color); return view;
    }
    private TextView link(String label, Runnable action) {
        TextView view = text(label, 14, MUTED); view.setPadding(dp(12), dp(14), dp(12), dp(14));
        view.setOnClickListener(v -> action.run()); return view;
    }
    private void button(LinearLayout layout, String label, Runnable action) {
        Button button = new Button(this); button.setText(label); button.setAllCaps(false);
        button.setOnClickListener(v -> action.run()); layout.addView(button);
    }
    private GradientDrawable rounded(int color, int radius) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(radius)); return shape;
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }

    // actual car ui, same draw path as the encoder
    private final class Preview extends View {
        private float layout = 800;
        Preview() { super(MainActivity.this); setContentDescription("Car display preview"); }
        @Override protected void onDraw(Canvas canvas) {
            int w = getWidth(), h = getHeight();
            // letterbox the car frame
            int frameH = Math.min(h, Math.round(w * 9f / 16f)), top = (h - frameH) / 2;
            layout = Math.max(560, Math.min(1600, 480f * w / frameH));
            CarUi.get(MainActivity.this).draw(canvas, 0, top, w, frameH, layout);
            postInvalidateOnAnimation();
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            int h = getHeight(), frameH = Math.min(h, Math.round(getWidth() * 9f / 16f)), top = (h - frameH) / 2;
            float x = event.getX() / getWidth() * layout, y = (event.getY() - top) / frameH * 480f;
            int masked = event.getActionMasked();
            int action = masked == MotionEvent.ACTION_UP ? 1 : masked == MotionEvent.ACTION_MOVE ? 2 : masked == MotionEvent.ACTION_DOWN ? 0 : -1;
            if (action >= 0 && (masked != MotionEvent.ACTION_DOWN || (y >= 0 && y < 480)))
                CarUi.get(MainActivity.this).touch(x, Math.max(0, Math.min(479, y)), action);
            if (event.getActionMasked() == MotionEvent.ACTION_UP) performClick();
            return true;
        }
        @Override public boolean performClick() { super.performClick(); return true; }
    }
}
