package org.petal.catback;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.IBinder;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.util.Log;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import org.petal.catback.protocol.AapConnection;
import org.petal.catback.protocol.ProjectionSession;
import org.petal.catback.protocol.WifiBootstrap;
import org.petal.catback.audio.AudioBridge;
import org.petal.catback.car.CarConnectionProvider;
import org.petal.catback.car.CarData;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import org.petal.catback.ui.CarUi;

public final class ProjectionService extends Service implements ProjectionSession.Host {
    public static final String TCP = "org.petal.catback.TCP", USB = "org.petal.catback.USB",
            WIRELESS = "org.petal.catback.WIRELESS", CAR_GONE = "org.petal.catback.CAR_GONE",
            STOP = "org.petal.catback.STOP";
    // device = bonded car, host/port = bench on current wifi, auto = bt kicked us off so retry and bail when it leaves
    public static final String EXTRA_DEVICE = "device", EXTRA_HOST = "host", EXTRA_PORT = "port", EXTRA_AUTO = "auto";
    private static final int AUTO_ATTEMPTS = 3;
    // engine crank drops bt for a sec, don't die over it
    private static final long GONE_GRACE_MS = 3000;
    private final Runnable leaveIfGone = this::stopIfCarGone;
    private void stopIfCarGone() { if (!carPresent && !busy()) { log("Car still gone; stopping"); stopSelf(); } }
    // human text for home + notif, state is the nerd one
    public static volatile String headline = "";
    private static final String PERMISSION = "org.petal.catback.USB_PERMISSION";
    private static final StringBuilder LOG = new StringBuilder();
    public static volatile String state = "Stopped";
    private volatile ServerSocket server;
    private volatile ProjectionSession session;
    private volatile Closeable transport;
    private volatile boolean stopping;
    private volatile Thread worker;
    private volatile WirelessController wireless;
    private volatile String standbyDevice;
    private boolean bluetoothRegistered;
    private volatile boolean auto, foreground, carPresent;
    private volatile int autoFailures;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger generation = new AtomicInteger();
    private VideoEncoder encoder;
    private AudioBridge bridge;
    private MediaProjection projection;
    public static final String AUDIO_PROJECTION = "org.petal.catback.AUDIO_PROJECTION";
    public static final String EXTRA_RESULT_CODE = "resultCode", EXTRA_RESULT_DATA = "resultData";
    public static volatile String audioStatus = "Phone speakers / Bluetooth";
    private PowerManager.WakeLock wakeLock;

    public static synchronized String logText() { return LOG.toString(); }
    @Override public void evidence(String name, byte[] data) {
        try (FileOutputStream out = new FileOutputStream(new java.io.File(getFilesDir(), name))) { out.write(data); }
        catch (IOException e) { log("Could not store protocol evidence: " + e.getMessage()); }
    }
    @Override public void log(String message) {
        String line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(new Date()) + " " + message + "\n";
        synchronized (ProjectionService.class) {
            LOG.append(line);
            if (LOG.length() > 80000) LOG.delete(0, LOG.indexOf("\n", LOG.length() - 60000) + 1);
        }
        Log.i("Catback", message);
        try (FileOutputStream out = new FileOutputStream(new java.io.File(getFilesDir(), "session.log"), true)) {
            out.write(line.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) { Log.w("Catback", "Diagnostic log write failed", e); }
    }

    private final BroadcastReceiver usbEvents = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (PERMISSION.equals(intent.getAction())) {
                UsbAccessory accessory = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
                if (accessory != null && intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) openUsb(accessory);
                else log("USB accessory permission denied");
            } else if (UsbManager.ACTION_USB_ACCESSORY_DETACHED.equals(intent.getAction())) {
                log("USB accessory detached"); stopTransport();
            }
        }
    };

    private final BroadcastReceiver bluetoothEvents = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            String wanted = standbyDevice;
            if (device == null || wanted == null || !wanted.equalsIgnoreCase(device.getAddress())) return;
            if (BluetoothDevice.ACTION_ACL_CONNECTED.equals(intent.getAction())) {
                log("Head unit Bluetooth link up");
                // car's rfcomm server lags behind HFP, chill 3s
                main.postDelayed(() -> { if (wanted.equals(standbyDevice) && !busy()) startWireless(wanted); }, 3000);
            } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(intent.getAction()) && session == null) {
                log("Head unit Bluetooth link down");
            }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("projection", "Projection connection", NotificationManager.IMPORTANCE_LOW));
        IntentFilter filter = new IntentFilter(PERMISSION); filter.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
        int receiverFlags = Build.VERSION.SDK_INT >= 33 ? Context.RECEIVER_NOT_EXPORTED : 0;
        // pre-13 has no NOT_EXPORTED so lock it w/ our sig perm instead
        registerReceiver(usbEvents, filter, getPackageName() + ".INTERNAL", null, receiverFlags);
        wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Catback:Session");
        CarData.get(this); // warm up apps/media before the car shows up
        log("CATBACK " + (AudioBridge.systemAvailable(this) ? "system build" : "app build") + "; no Google runtime dependencies");
        if (!Identity.carReady(this)) log("No Android Auto identity imported yet, stock cars will refuse the handshake");
    }
    private Notification notification(String text) {
        PendingIntent launch = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 2, new Intent(this, ProjectionService.class).setAction(STOP),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "projection").setContentTitle("CATBACK").setContentText(text)
                .setSmallIcon(R.drawable.ic_petal_auto).setContentIntent(launch).setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build()).build();
    }
    private void phase(String text) {
        headline = text;
        if (foreground) getSystemService(NotificationManager.class).notify(1, notification(text));
    }
    private boolean busy() {
        WirelessController controller = wireless;
        return session != null || (controller != null && !controller.isClosed());
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (AUDIO_PROJECTION.equals(action) && intent.hasExtra(EXTRA_RESULT_DATA)) {
            // has to be a mediaProjection fgs before touching the token or android throws a fit
            startForeground(1, notification(headline.isEmpty() ? "Audio ready" : headline),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            foreground = true;
            try {
                MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
                projection = manager.getMediaProjection(intent.getIntExtra(EXTRA_RESULT_CODE, 0), intent.getParcelableExtra(EXTRA_RESULT_DATA));
                if (projection != null) projection.registerCallback(new MediaProjection.Callback() {
                    @Override public void onStop() { projection = null; audioStatus = "Screen-audio capture ended; allow it again in Settings"; }
                }, main);
                audioStatus = projection != null ? "Ready: phone audio can play on the car" : "Capture was not granted";
            } catch (RuntimeException e) { audioStatus = "Capture failed: " + e.getMessage(); }
            return START_NOT_STICKY;
        }
        if (STOP.equals(action)) { log("Stopped from notification"); stopSelf(); return START_NOT_STICKY; }
        if (CAR_GONE.equals(action)) {
            // some random headphones leaving isn't our problem
            String gone = intent.getStringExtra(EXTRA_DEVICE), serving = standbyDevice;
            if (gone != null && serving != null && !gone.equalsIgnoreCase(serving)) return START_NOT_STICKY;
            // loads of cars drop bt once wifi is up, so the tcp link decides when we're done, not bt
            carPresent = false;
            if (busy()) log("Car Bluetooth disconnected; continuing over Wi-Fi");
            else {
                log("Car disconnected; stopping in " + GONE_GRACE_MS / 1000 + " s unless it reconnects");
                main.removeCallbacks(leaveIfGone);
                main.postDelayed(leaveIfGone, GONE_GRACE_MS);
            }
            return START_NOT_STICKY;
        }
        startForeground(1, notification(headline.isEmpty() ? "Starting" : headline),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        foreground = true;
        if (WIRELESS.equals(action)) {
            String host = intent.getStringExtra(EXTRA_HOST), device = intent.getStringExtra(EXTRA_DEVICE);
            boolean automatic = intent.getBooleanExtra(EXTRA_AUTO, false);
            if (host != null) { auto = false; startWirelessBench(host, intent.getIntExtra(EXTRA_PORT, 5288)); }
            else if (device == null) log("Wireless start needs a head-unit address");
            else {
                if (automatic) { carPresent = true; main.removeCallbacks(leaveIfGone); }
                if (automatic && device.equalsIgnoreCase(standbyDevice) && busy()) log("Already connected to the linked car");
                else { auto = automatic; autoFailures = 0; startWireless(device); }
            }
        }
        else if (TCP.equals(action)) { standbyDevice = null; auto = false; startTcp(); }
        else if (USB.equals(action)) { standbyDevice = null; auto = false; connectUsb(); }
        return START_NOT_STICKY;
    }

    private void startTcp() {
        stopTransport(); stopping = false;
        final int token = generation.get();
        Thread thread = new Thread(() -> {
            try (ServerSocket listener = new ServerSocket()) {
                if (generation.get() != token) return;
                server = listener;
                listener.setReuseAddress(true);
                listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 5277));
                state = "Desktop: 127.0.0.1:5277";
                log("Listening on loopback port 5277; use adb forward tcp:5277 tcp:5277");
                while (!stopping && generation.get() == token && !Thread.currentThread().isInterrupted()) {
                    Socket socket = listener.accept();
                    socket.setTcpNoDelay(true); socket.setSoTimeout(30000);
                    transport = socket;
                    log("Desktop receiver connected");
                    runSession(new BufferedInputStream(socket.getInputStream(), 65536),
                            new BufferedOutputStream(socket.getOutputStream(), 65536), socket, token);
                    if (!stopping && generation.get() == token) { state = "Desktop: waiting for receiver"; log(state); }
                }
            } catch (Exception e) {
                if (!stopping && generation.get() == token) { state = "Connection error: " + e.getMessage(); log(state); }
            }
        }, "Catback-TCP");
        worker = thread; thread.start();
    }
    private void startWireless(String address) {
        BluetoothDevice device;
        try { device = getSystemService(BluetoothManager.class).getAdapter().getRemoteDevice(address); }
        catch (RuntimeException e) { log("Wireless: invalid head-unit address " + address); return; }
        WirelessController controller = beginWireless();
        if (controller == null) return;
        standbyDevice = address;
        listenForHeadUnit();
        phase("Connecting to " + carName(address));
        log("Wireless: connecting to bonded head unit " + address);
        controller.start(device);
    }
    private void startWirelessBench(String host, int port) {
        WifiBootstrap.Endpoint endpoint;
        try { endpoint = new WifiBootstrap.Endpoint(host, port); }
        catch (IOException e) { log("Wireless bench: " + e.getMessage()); return; }
        WirelessController controller = beginWireless();
        if (controller != null) controller.startOnCurrentWifi(endpoint);
    }
    private WirelessController beginWireless() {
        stopTransport(); stopping = false;
        final int token = generation.get();
        WirelessController controller = new WirelessController(this, new WirelessController.Listener() {
            @Override public void log(String text) { ProjectionService.this.log(text); }
            @Override public void state(String text) { if (generation.get() == token) state = text; }
            @Override public void closed(boolean projected) {
                String car = standbyDevice;
                if (stopping || generation.get() != token || car == null) return;
                if (projected) return; // projection() already handled it
                if (auto && !carPresent) {
                    ProjectionService.this.log("Setup ended and the car is gone; stopping");
                    main.post(ProjectionService.this::stopSelf);
                } else if (auto && ++autoFailures < AUTO_ATTEMPTS) {
                    phase("Retrying connection to " + carName(car));
                    main.postDelayed(() -> { if (generation.get() == token && !busy()) startWireless(car); }, 5000);
                } else phase("Couldn't connect to " + carName(car) + ". Will try again when it reconnects.");
            }
            @Override public void projection(Socket socket) {
                // blocks on the controller's thread till the session dies
                try {
                    runSession(new BufferedInputStream(socket.getInputStream(), 65536),
                            new BufferedOutputStream(socket.getOutputStream(), 65536), socket, token);
                } catch (IOException e) { ProjectionService.this.log("Wireless socket: " + e.getMessage()); }
                if (!stopping && generation.get() == token) {
                    state = standbyDevice != null ? "Wireless: waiting for head unit" : "Stopped";
                    ProjectionService.this.log(state);
                    autoFailures = 0;
                    if (auto && !carPresent) {
                        ProjectionService.this.log("Projection ended and the car is gone; stopping");
                        main.post(ProjectionService.this::stopSelf);
                    } else if (standbyDevice != null) phase("Ready. Waiting for " + carName(standbyDevice));
                }
            }
        });
        if (generation.get() != token) { controller.close(); return null; }
        wireless = controller;
        return controller;
    }
    private void listenForHeadUnit() {
        if (bluetoothRegistered) return;
        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        try { registerReceiver(bluetoothEvents, filter); bluetoothRegistered = true; }
        catch (SecurityException e) { log("Wireless standby unavailable: " + e.getMessage()); }
    }
    private void connectUsb() {
        UsbManager manager = getSystemService(UsbManager.class);
        UsbAccessory[] accessories = manager.getAccessoryList();
        if (accessories == null || accessories.length == 0) { log("No USB accessory found; connect to an Android Auto head unit first"); return; }
        UsbAccessory accessory = accessories[0];
        // rom build has MANAGE_USB so this is always true, no stupid dialog
        if (manager.hasPermission(accessory)) openUsb(accessory);
        else {
            state = "Waiting for USB permission";
            PendingIntent permission = PendingIntent.getBroadcast(this, 1,
                    new Intent(PERMISSION).setPackage(getPackageName()), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            manager.requestPermission(accessory, permission);
        }
    }
    private void openUsb(UsbAccessory accessory) {
        stopTransport(); stopping = false;
        final int token = generation.get();
        Thread thread = new Thread(() -> {
            try {
                ParcelFileDescriptor fd = getSystemService(UsbManager.class).openAccessory(accessory);
                if (fd == null) throw new IOException("USB accessory could not be opened");
                transport = fd;
                state = "USB: " + accessory.getModel(); log(state);
                // buffered or bust, single byte reads off the usb fd eat transfers
                runSession(new BufferedInputStream(new FileInputStream(fd.getFileDescriptor()), 65536),
                        new BufferedOutputStream(new FileOutputStream(fd.getFileDescriptor()), 65536), fd, token);
            } catch (Exception e) { if (!stopping && generation.get() == token) { state = "USB error: " + e.getMessage(); log(state); } }
        }, "Catback-USB");
        worker = thread; thread.start();
    }
    private void runSession(java.io.InputStream in, java.io.OutputStream out, Closeable owner, int token) {
        ProjectionSession active = null;
        try {
            AapConnection connection = new AapConnection(in, out, owner, Identity.engine(this));
            log(Identity.status);
            if (generation.get() != token) { connection.close(); return; }
            active = new ProjectionSession(connection, this); session = active;
            wakeLock.acquire(60 * 60 * 1000L);
            state = "Negotiating projection";
            active.run();
        } catch (Exception e) {
            if (!stopping && generation.get() == token) { log("Connection ended: " + e.getClass().getSimpleName() + ": " + e.getMessage()); Log.d("Catback", "Session details", e); }
        } finally {
            if (active != null) try { active.close(); } catch (IOException ignored) { }
            try { owner.close(); } catch (IOException ignored) { }
            if (session == active) session = null;
            if (generation.get() == token && wakeLock.isHeld()) wakeLock.release();
        }
    }
    private void stopTransport() {
        generation.incrementAndGet();
        stopping = true;
        Thread current = worker;
        if (current != null) current.interrupt();
        ProjectionSession active = session;
        if (active != null) try { active.close(); } catch (IOException ignored) { }
        WirelessController controller = wireless; wireless = null;
        if (controller != null) controller.close();
        Closeable descriptor = transport;
        if (descriptor != null) try { descriptor.close(); } catch (IOException ignored) { }
        ServerSocket listener = server;
        if (listener != null) try { listener.close(); } catch (IOException ignored) { }
        server = null; transport = null; session = null;
        stopVideo(); audio(false, null, active);
        CarUi.get(this).session(false);
        CarConnectionProvider.set(this, false);
        state = "Stopped";
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }
    @Override public synchronized void startVideo(ProjectionSession.VideoConfig config, ProjectionSession active) {
        stopVideo(); encoder = new VideoEncoder(CarUi.get(this), active, config);
        CarUi.get(this).session(true);
        CarConnectionProvider.set(this, true);
        state = "Projecting " + config.width + "x" + config.height;
        phase("Connected to " + (standbyDevice != null ? carName(standbyDevice) : "head unit"));
    }
    @Override public synchronized void stopVideo() {
        if (encoder != null) { encoder.close(); encoder = null; }
    }
    @Override public void audioReady(ProjectionSession active) {
        String output = Settings.audioOutput(this);
        boolean system = AudioBridge.systemAvailable(this), root = !system && Settings.CAR_ROOT.equals(output);
        boolean car = Settings.CAR_ROOT.equals(output) || Settings.CAR.equals(output);
        if (car && !system && !root && projection == null) audioStatus = "Allow screen-audio capture in Settings";
        active.setMediaAudio(car && (system || root || projection != null));
    }
    @Override public synchronized void audio(boolean playing, ProjectionSession.AudioConfig config, ProjectionSession active) {
        if (bridge != null) { bridge.close(); bridge = null; }
        if (!playing || config == null) { audioStatus = Settings.PHONE.equals(Settings.audioOutput(this)) ? "Phone speakers / Bluetooth" : "Car audio idle"; return; }
        // rom build ignores the root pick, policy loopback does it w/o su anyway
        boolean system = AudioBridge.systemAvailable(this), root = !system && Settings.CAR_ROOT.equals(Settings.audioOutput(this));
        if (!system && !root && projection == null) { audioStatus = "Allow screen-audio capture in Settings"; return; }
        bridge = new AudioBridge(this, config.rate, config.channels, active::pcmQuiet);
        if (system) bridge.startSystem(); else if (root) bridge.startRoot(); else bridge.startApp(projection);
        audioStatus = system || root ? "All phone audio playing on the car" : "Phone audio playing on the car";
        log("Capturing phone audio for the car (" + config.rate + " Hz, " + config.channels + " ch)");
    }
    @Override public void touch(float x, float y, int action) { CarUi.get(this).touch(x, y, action); }
    @Override public void key(int code) { CarUi.get(this).key(code); }
    private String carName(String address) {
        return CarLink.isCar(this, address) ? CarLink.name(this) : "head unit";
    }
    @Override public void onDestroy() {
        if (bridge != null) bridge.close();
        if (projection != null) projection.stop();
        headline = ""; foreground = false;
        standbyDevice = null; main.removeCallbacksAndMessages(null);
        stopTransport(); unregisterReceiver(usbEvents);
        if (bluetoothRegistered) unregisterReceiver(bluetoothEvents);
        if (wakeLock.isHeld()) wakeLock.release();
        log("Projection service stopped"); super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
