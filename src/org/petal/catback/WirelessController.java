package org.petal.catback;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.MacAddress;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.RouteInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.net.ssl.SSLSocket;
import org.petal.catback.protocol.Proto;
import org.petal.catback.protocol.WifiBootstrap;

// bt WPP -> car wifi -> AAP over tcp
public final class WirelessController implements Closeable {
    public interface Listener {
        void log(String text);
        void state(String text);
        void projection(Socket socket);
        // projected=false = died before AAP even started
        default void closed(boolean projected) { }
    }
    private final Context context;
    private final Listener listener;
    private final ConnectivityManager connectivity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService workers = Executors.newCachedThreadPool(r -> new Thread(r, "Catback-Wireless"));
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "Catback-WirelessTimer"));
    private final Set<Closeable> resources = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean(), projectionStarted = new AtomicBoolean();
    private final String deviceId, sessionId = UUID.randomUUID().toString();
    private final boolean fiveGhz;
    private volatile ConnectivityManager.NetworkCallback callback;
    private volatile Network network;
    private volatile WifiBootstrap bluetoothBootstrap;
    private volatile boolean networkHandoff, projectionRequested;

    public WirelessController(Context context, Listener listener) {
        this.context = context.getApplicationContext(); this.listener = listener;
        connectivity = context.getSystemService(ConnectivityManager.class);
        WifiManager wifi = this.context.getSystemService(WifiManager.class);
        fiveGhz = wifi != null && wifi.is5GHzBandSupported();
        android.content.SharedPreferences preferences = context.getSharedPreferences("wireless", Context.MODE_PRIVATE);
        String saved = preferences.getString("device-id", null);
        if (saved == null) { saved = UUID.randomUUID().toString(); preferences.edit().putString("device-id", saved).apply(); }
        deviceId = saved;
    }

    // runtime check for the bt calls below (31+)
    private boolean bluetoothAllowed() {
        return Build.VERSION.SDK_INT < 31
                || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    public boolean isClosed() { return closed.get(); }

    // rom build only, wifi joins w/o asking
    public static boolean silentWifiApproval(Context context) {
        return context.checkSelfPermission(COMPANION_APPROVE_WIFI) == PackageManager.PERMISSION_GRANTED;
    }
    private static final String COMPANION_APPROVE_WIFI = "android.permission.COMPANION_APPROVE_WIFI_CONNECTIONS";

    @SuppressLint("MissingPermission")
    public void start(BluetoothDevice device) {
        workers.execute(() -> {
            try {
                if (!bluetoothAllowed()) throw new IOException("Bluetooth permission not granted");
                BluetoothAdapter adapter = context.getSystemService(BluetoothManager.class).getAdapter();
                if (adapter == null || !adapter.isEnabled()) throw new IOException("Enable Bluetooth on the phone first");
                if (device.getBondState() != BluetoothDevice.BOND_BONDED)
                    throw new IOException("Pair the head unit in Android Bluetooth settings first");
                UUID service = WifiBootstrap.SERVICE;
                ParcelUuid[] services = device.getUuids();
                boolean primary = false, reversed = false;
                if (services != null) for (ParcelUuid uuid : services) {
                    primary |= WifiBootstrap.SERVICE.equals(uuid.getUuid());
                    reversed |= WifiBootstrap.REVERSED_SERVICE.equals(uuid.getUuid());
                }
                if (!primary && reversed) service = WifiBootstrap.REVERSED_SERVICE;
                listener.state("Wireless: connecting Bluetooth setup");
                listener.log("Opening standard Android Auto RFCOMM service " + service);
                // car opens WPP after HFP, retry a couple times
                BluetoothSocket socket = null;
                for (int attempt = 1; socket == null; attempt++) {
                    BluetoothSocket candidate = track(device.createRfcommSocketToServiceRecord(service));
                    ScheduledFuture<?> timeout = timer.schedule(() -> quietlyClose(candidate), 15, TimeUnit.SECONDS);
                    try { candidate.connect(); socket = candidate; }
                    catch (IOException error) {
                        resources.remove(candidate); quietlyClose(candidate);
                        if (attempt >= 4 || closed.get()) throw error;
                        listener.log("RFCOMM attempt " + attempt + " failed (" + error.getMessage() + "); retrying");
                        Thread.sleep(3000);
                    } finally { timeout.cancel(false); }
                }
                if (closed.get()) return;
                listener.log("Bluetooth WPP connected; waiting for receiver setup");
                InputStream in = new BufferedInputStream(socket.getInputStream(), 32768);
                OutputStream out = new BufferedOutputStream(socket.getOutputStream(), 32768);
                WifiBootstrap bootstrap = createBootstrap(out, false, Proto.EMPTY);
                bluetoothBootstrap = bootstrap;
                readControl(in, bootstrap, false);
            } catch (Exception error) {
                if (!closed.get()) {
                    if (networkHandoff) listener.log("Bluetooth setup channel ended after Wi-Fi handoff");
                    else fail("Bluetooth setup failed", error);
                }
            }
        });
    }

    // bench, real wifi transport but stays on the current AP
    public void startOnCurrentWifi(WifiBootstrap.Endpoint endpoint) {
        workers.execute(() -> {
            for (Network candidate : connectivity.getAllNetworks()) {
                NetworkCapabilities caps = connectivity.getNetworkCapabilities(candidate);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    network = candidate;
                    listener.log("Bench mode: current Wi-Fi; Bluetooth/AP bootstrap is not exercised");
                    startProjection(endpoint);
                    return;
                }
            }
            fail("Wi-Fi transport", new IOException("Phone is not connected to a Wi-Fi network"));
        });
    }

    private WifiBootstrap createBootstrap(OutputStream output, boolean tcp, byte[] token) {
        return new WifiBootstrap((id, body) -> {
            WifiBootstrap.write(output, id, body);
            listener.log("WPP " + (tcp ? "TLS/TCP" : "RFCOMM") + " TX id=" + id + " bytes=" + body.length);
        }, new WifiBootstrap.Events() {
            public void log(String text) { listener.log(text); }
            public void join(WifiBootstrap.AccessPoint ap, WifiBootstrap.Endpoint endpoint, boolean controlTls, byte[] setupToken) {
                networkHandoff = true;
                joinNetwork(ap, endpoint, controlTls, setupToken);
            }
            public void project(WifiBootstrap.Endpoint endpoint) { startProjection(endpoint); }
        }, deviceId, sessionId, phoneName(), phoneAddress(), fiveGhz, tcp, token);
    }

    @SuppressLint("MissingPermission")
    private String phoneName() {
        if (!bluetoothAllowed()) return null;
        try { return context.getSystemService(BluetoothManager.class).getAdapter().getName(); }
        catch (RuntimeException unavailable) { return null; }
    }
    // normal apps get 02:00:00:00:00:00, just leave it out
    @SuppressLint({"MissingPermission", "HardwareIds"})
    private String phoneAddress() {
        if (!bluetoothAllowed()) return null;
        try {
            String address = context.getSystemService(BluetoothManager.class).getAdapter().getAddress();
            return BluetoothAdapter.checkBluetoothAddress(address) && !address.equals("02:00:00:00:00:00") ? address : null;
        } catch (RuntimeException unavailable) { return null; }
    }

    private void readControl(InputStream input, WifiBootstrap bootstrap, boolean tcp) throws IOException {
        while (!closed.get()) {
            try {
                WifiBootstrap.Frame frame = WifiBootstrap.read(input);
                bootstrap.receive(frame.id, frame.body);
            } catch (IOException error) {
                if (closed.get()) return;
                if (projectionRequested || (!tcp && networkHandoff)) {
                    listener.log("WPP setup channel closed; projection handoff continues");
                    return;
                }
                throw error;
            }
        }
    }

    private void joinNetwork(WifiBootstrap.AccessPoint ap, WifiBootstrap.Endpoint endpoint, boolean controlTls, byte[] token) {
        main.post(() -> {
            if (closed.get()) return;
            try {
                WifiManager wifi = context.getSystemService(WifiManager.class);
                if (wifi == null || !wifi.isWifiEnabled()) throw new IOException("Enable Wi-Fi on the phone first");
                // hidden ssid match finds broadcast ones too, loads of cars hide theirs
                WifiNetworkSpecifier.Builder specifier = new WifiNetworkSpecifier.Builder().setSsid(ap.ssid).setIsHiddenSsid(true);
                if (ap.hasBssid()) specifier.setBssid(MacAddress.fromString(ap.bssid));
                // wpa2/wpa3 mixed joins as wpa2 same as gearhead
                if (ap.security == WifiBootstrap.WPA3) specifier.setWpa3Passphrase(ap.key);
                else if (ap.security != WifiBootstrap.OPEN) specifier.setWpa2Passphrase(ap.key);
                NetworkRequest request = new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(specifier.build()).build();
                // rom build skips the "connect to this network?" crap, otherwise user okays it once per car
                boolean silent = silentWifiApproval(context);
                listener.state(silent ? "Wireless: joining head-unit Wi-Fi" : "Wireless: approve the head-unit Wi-Fi connection");
                listener.log("Requesting receiver-advertised Wi-Fi network (" + (silent ? "pre-approved" : "user approval")
                        + (ap.hasBssid() ? ", exact BSSID" : ", SSID only") + "); credentials and tokens are not logged");
                ConnectivityManager.NetworkCallback cb = new ConnectivityManager.NetworkCallback() {
                    @Override public void onAvailable(Network available) {
                        if (closed.get() || network != null) return;
                        network = available;
                        listener.log("Receiver Wi-Fi network available");
                        if (controlTls) workers.execute(() -> startTlsControl(endpoint, token));
                        else startProjection(endpoint);
                    }
                    @Override public void onUnavailable() {
                        fail("Receiver Wi-Fi unavailable", new IOException("Network request declined, timed out, or AP not found"));
                    }
                    @Override public void onLost(Network lost) {
                        if (lost.equals(network)) fail("Receiver Wi-Fi lost", new IOException("Projection network disconnected"));
                    }
                };
                callback = cb;
                connectivity.requestNetwork(request, cb, main, 65000);
            } catch (Exception error) { fail("Wi-Fi setup failed", error); }
        });
    }

    private void startTlsControl(WifiBootstrap.Endpoint endpoint, byte[] token) {
        try {
            listener.state("Wireless: negotiating Wi-Fi control session");
            Socket raw = connect(endpoint);
            SSLSocket ssl = track((SSLSocket) Identity.context(context).getSocketFactory()
                    .createSocket(raw, raw.getInetAddress().getHostAddress(), endpoint.port, true));
            ssl.setUseClientMode(false); ssl.setNeedClientAuth(true); ssl.setEnabledProtocols(new String[]{"TLSv1.2"});
            ssl.setSoTimeout(20000); ssl.startHandshake();
            listener.log("WPP/TCP mutual TLS established");
            WifiBootstrap bootstrap = createBootstrap(new BufferedOutputStream(ssl.getOutputStream(), 32768), true, token);
            readControl(new BufferedInputStream(ssl.getInputStream(), 32768), bootstrap, true);
        } catch (Exception error) {
            if (!closed.get() && !projectionRequested) fail("Wi-Fi control session failed", error);
        }
    }

    private void startProjection(WifiBootstrap.Endpoint endpoint) {
        if (!projectionStarted.compareAndSet(false, true) || closed.get()) return;
        projectionRequested = true;
        workers.execute(() -> {
            try {
                listener.state("Wireless: connecting projection TCP");
                Socket socket = connect(endpoint);
                WifiBootstrap bootstrap = bluetoothBootstrap;
                if (bootstrap != null) {
                    try { bootstrap.connected(); }
                    catch (IOException ended) { listener.log("Bluetooth setup already ended at projection handoff"); }
                }
                if (closed.get()) return;
                listener.log("Wireless projection TCP connected to receiver-advertised endpoint " + endpoint);
                listener.projection(socket); // AAP runs on this thread
            } catch (Exception error) { if (!closed.get()) fail("Wireless projection failed", error); }
            finally { close(); }
        });
    }

    private Socket connect(WifiBootstrap.Endpoint endpoint) throws IOException {
        Network chosen = network;
        if (chosen == null) throw new IOException("No receiver Wi-Fi network");
        String host = endpoint.host;
        if (host.equals("0.0.0.0")) {
            LinkProperties properties = connectivity.getLinkProperties(chosen);
            InetAddress gateway = null;
            if (properties != null) {
                if (Build.VERSION.SDK_INT >= 30) gateway = properties.getDhcpServerAddress();
                if (gateway == null) for (RouteInfo route : properties.getRoutes())
                    if (route.isDefaultRoute() && route.hasGateway()) { gateway = route.getGateway(); break; }
            }
            if (gateway == null) throw new IOException("Receiver advertised a dynamic endpoint but no DHCP gateway is available");
            host = gateway.getHostAddress();
        }
        // car network is useless for a few secs after onAvailable (dhcp, listener not up), retry ~15s
        IOException last = null;
        for (int attempt = 1; attempt <= 15 && !closed.get(); attempt++) {
            Socket socket = track(chosen.getSocketFactory().createSocket());
            try {
                socket.setTcpNoDelay(true); socket.setSoTimeout(30000);
                socket.connect(new InetSocketAddress(host, endpoint.port), 5000);
                if (attempt > 1) listener.log("Projection TCP connected on attempt " + attempt);
                return socket;
            } catch (IOException error) {
                last = error; resources.remove(socket); quietlyClose(socket);
                if (attempt == 1 || attempt % 5 == 0) listener.log("Projection TCP attempt " + attempt + ": " + error.getMessage());
                try { Thread.sleep(1000); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
            }
        }
        throw new IOException("Could not connect to advertised wireless endpoint", last);
    }

    private <T extends Closeable> T track(T resource) throws IOException {
        if (closed.get()) { resource.close(); throw new IOException("Wireless connection stopped"); }
        resources.add(resource);
        if (closed.get()) { resources.remove(resource); resource.close(); throw new IOException("Wireless connection stopped"); }
        return resource;
    }
    private void fail(String label, Exception error) {
        if (closed.get()) return;
        listener.log(label + ": " + error.getClass().getSimpleName() + ": " + error.getMessage());
        listener.state(label + ": " + error.getMessage());
        WifiBootstrap bootstrap = bluetoothBootstrap;
        if (bootstrap != null && !projectionRequested) workers.execute(() -> {
            try { bootstrap.failed(-11); } catch (IOException ignored) { }
            close();
        });
        else close();
    }
    private static void quietlyClose(Closeable resource) { try { resource.close(); } catch (IOException ignored) { } }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        for (Closeable resource : resources) quietlyClose(resource);
        resources.clear(); timer.shutdownNow(); workers.shutdownNow();
        ConnectivityManager.NetworkCallback cb = callback; callback = null;
        if (cb != null) main.post(() -> {
            try { connectivity.unregisterNetworkCallback(cb); } catch (IllegalArgumentException ignored) { }
        });
        listener.closed(projectionStarted.get());
    }
}
