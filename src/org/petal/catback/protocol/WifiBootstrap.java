package org.petal.catback.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

// WPP, the bt bit before AAP. facts from gearhead xx*/pol/poz + aa-proxy-rs, nothing hardcoded
public final class WifiBootstrap {
    public static final UUID SERVICE = UUID.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66");
    public static final UUID REVERSED_SERVICE = UUID.fromString("669a0c20-0008-f4bd-e611-cb52007ae14d");
    public static final int START = 1, INFO_REQUEST = 2, INFO = 3, VERSION = 4,
            VERSION_RESPONSE = 5, CONNECT_STATUS = 6, START_RESPONSE = 7,
            PING = 8, PONG = 9, REJECTION = 10, SETUP_INFO = 11;
    public static final int MAX_FRAME = 16384;
    // gearhead xxk security modes, it only joins wpa2/wpa3/mixed
    public static final int OPEN = 1, WPA = 4, WPA2 = 8, WPA_WPA2 = 12, WPA3 = 32, WPA2_WPA3 = 40;

    public interface Sender { void send(int id, byte[] body) throws IOException; }
    public interface Events {
        void log(String text);
        void join(AccessPoint accessPoint, Endpoint endpoint, boolean controlTls, byte[] setupToken) throws IOException;
        void project(Endpoint endpoint) throws IOException;
    }
    public static final class Frame {
        public final int id;
        public final byte[] body;
        public Frame(int id, byte[] body) { this.id = id; this.body = body; }
    }
    public static Frame read(InputStream input) throws IOException {
        DataInputStream in = new DataInputStream(input);
        int size = in.readUnsignedShort(), id = in.readUnsignedShort();
        if (size > MAX_FRAME || id < 1 || id > SETUP_INFO) throw new IOException("Unsupported WPP frame header");
        byte[] body = new byte[size]; in.readFully(body);
        return new Frame(id, body);
    }
    public static void write(OutputStream output, int id, byte[] body) throws IOException {
        if (id < 1 || id > SETUP_INFO || body.length > MAX_FRAME) throw new IOException("WPP frame limit");
        synchronized (output) {
            DataOutputStream out = new DataOutputStream(output);
            out.writeShort(body.length); out.writeShort(id); out.write(body); out.flush();
        }
    }
    public static final class Endpoint {
        public final String host;
        public final int port;
        public Endpoint(String host, long port) throws IOException {
            // literal addresses only, never let car junk turn into dns lookups on mobile data
            if (host == null || host.isEmpty() || host.length() > 128 || port < 1 || port > 65535)
                throw new IOException("Invalid wireless endpoint");
            if (host.contains(":")) {
                if (!host.matches("[0-9a-fA-F:.%a-zA-Z0-9_-]+")) throw new IOException("Invalid IPv6 endpoint");
                InetAddress address = InetAddress.getByName(host);
                if (address.isMulticastAddress()) throw new IOException("Multicast wireless endpoint");
            } else {
                String[] parts = host.split("\\.", -1);
                if (parts.length != 4) throw new IOException("Wireless endpoint must be a literal address");
                for (String part : parts) {
                    if (!part.matches("[0-9]{1,3}") || Integer.parseInt(part) > 255)
                        throw new IOException("Invalid IPv4 endpoint");
                }
                int first = Integer.parseInt(parts[0]);
                if (first >= 224 || host.equals("255.255.255.255")) throw new IOException("Invalid wireless destination");
            }
            this.host = host; this.port = (int) port;
        }
        static Endpoint parse(Proto.Message p) throws IOException { return new Endpoint(p.text(1, ""), p.integer(2, 0)); }
        @Override public String toString() { return host + ":" + port; }
    }
    public static final class AccessPoint {
        public final String ssid, key, bssid;
        public final int security;
        private AccessPoint(String ssid, String key, String bssid, int security) throws IOException {
            if (ssid.isEmpty() || ssid.getBytes(StandardCharsets.UTF_8).length > 32)
                throw new IOException("Invalid head-unit SSID");
            if (security != OPEN && security != WPA && security != WPA2 && security != WPA_WPA2
                    && security != WPA3 && security != WPA2_WPA3)
                throw new IOException("Unsupported Wi-Fi security mode " + security);
            if (security != OPEN && (key.length() < 8 || key.length() > 63))
                throw new IOException("Invalid head-unit WPA passphrase");
            this.ssid = ssid; this.key = key; this.bssid = bssid; this.security = security;
        }
        static AccessPoint parse(Proto.Message p, boolean literal) throws IOException {
            String ssid = p.text(1, ""), key = p.text(2, "");
            if (!literal) { ssid = unquote(ssid); key = unquote(key); }
            int security = (int) p.integer(4, 0);
            if (security == 0) security = 8; // gearhead poz.g() does the same
            return new AccessPoint(ssid, key, p.text(3, ""), security);
        }
        public boolean hasBssid() {
            return bssid.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")
                    && !bssid.equalsIgnoreCase("00:00:00:00:00:00") && !bssid.equalsIgnoreCase("ff:ff:ff:ff:ff:ff");
        }
        // no toString on purpose, ap keys stay out of logs
    }

    private final Sender sender;
    private final Events events;
    private final String deviceId, sessionId, phoneName, phoneAddress;
    private final boolean tcpControl, fiveGhz;
    private final byte[] suppliedToken;
    private Endpoint projection, control;
    private AccessPoint accessPoint;
    private byte[] setupToken = Proto.EMPTY;
    private boolean requestedInfo, joining, projectRequested;
    private int major = 1, minor;

    // PhoneInfo 3/4 (gearhead xof), null skips
    public WifiBootstrap(Sender sender, Events events, String deviceId, String sessionId, String phoneName,
                         String phoneAddress, boolean fiveGhz, boolean tcpControl, byte[] suppliedToken) {
        this.sender = sender; this.events = events; this.deviceId = deviceId; this.sessionId = sessionId;
        this.phoneName = phoneName; this.phoneAddress = phoneAddress;
        this.fiveGhz = fiveGhz; this.tcpControl = tcpControl;
        this.suppliedToken = suppliedToken.clone();
    }
    public synchronized void receive(int id, byte[] body) throws IOException {
        Proto.Message p = new Proto.Message(body);
        events.log("WPP " + (tcpControl ? "TLS/TCP" : "RFCOMM") + " RX id=" + id + " bytes=" + body.length);
        switch (id) {
            case VERSION: version(p); break;
            case START:
                projection = Endpoint.parse(p);
                if (!tcpControl && accessPoint == null) requestInfo();
                // only status is required back (xxn field 3)
                sender.send(START_RESPONSE, new Proto.Writer().integer(3, 0).build());
                advance(); break;
            case INFO:
                if (tcpControl) throw new IOException("Unexpected Wi-Fi credentials on WPP/TCP");
                accessPoint = AccessPoint.parse(p, modern());
                events.log("Head-unit Wi-Fi configuration received; security=" + accessPoint.security);
                advance(); break;
            case SETUP_INFO:
                if (tcpControl) throw new IOException("WifiSetupInfo must arrive over Bluetooth");
                major = (int) p.integer(1, 0); minor = (int) p.integer(2, 0);
                if (!modern()) throw new IOException("WifiSetupInfo requires WPP 4.5 or later");
                setupToken = p.bytes(3).clone();
                if (setupToken.length > 4096) throw new IOException("WPP setup token limit");
                control = Endpoint.parse(p.message(4));
                accessPoint = AccessPoint.parse(p.message(5), true);
                events.log("WPP " + major + "." + minor + " setup received; using TLS control transport");
                advance(); break;
            case PING: sender.send(PONG, new Proto.Writer().integer(1, p.integer(1, 0)).build()); break;
            case PONG: break;
            case REJECTION: throw new IOException("Head unit rejected WPP setup, reason=" + p.integer(1, 0));
            default: throw new IOException("Unexpected head-unit WPP message " + id);
        }
    }
    private void version(Proto.Message p) throws IOException {
        int requestedMajor = (int) p.integer(1, -1), requestedMinor = (int) p.integer(2, -1);
        boolean supported = (requestedMajor == 0 || requestedMajor == 1 || (requestedMajor >= 4 && requestedMajor <= 6))
                && requestedMinor >= 0 && requestedMinor <= 65535;
        int channelType = (int) p.integer(3, 0);
        int selectedChannels = fiveGhz ? 2 : 1; // 0=5GHz 1=2.4 2=dual 3=none
        int status = !supported ? -1 : channelType == 3 || (!fiveGhz && channelType == 0) ? -8 : 0;
        Proto.Writer phone = new Proto.Writer().text(1, deviceId).text(2, sessionId);
        if (phoneName != null && !phoneName.isEmpty()) phone.text(3, phoneName);
        if (phoneAddress != null) phone.text(4, phoneAddress);
        Proto.Writer response = new Proto.Writer().integer(1, Math.max(0, requestedMajor))
                .integer(2, Math.max(0, requestedMinor)).text(3, deviceId).integer(4, status)
                .integer(5, status == 0 ? selectedChannels : 3).bytes(6, phone.build());
        if (suppliedToken.length > 0) response.bytes(7, suppliedToken);
        sender.send(VERSION_RESPONSE, response.build());
        if (status != 0) throw new IOException("No compatible wireless version/channel, status=" + status);
        major = requestedMajor; minor = requestedMinor;
        events.log("Negotiated WPP " + major + "." + minor);
        if (!tcpControl && p.has(6)) {
            control = Endpoint.parse(p.message(6));
            if (accessPoint == null) requestInfo();
        }
        advance();
    }
    private void requestInfo() throws IOException {
        if (!requestedInfo) { sender.send(INFO_REQUEST, Proto.EMPTY); requestedInfo = true; }
    }
    private boolean modern() { return major > 4 || (major == 4 && minor >= 5); }
    private void advance() throws IOException {
        if (tcpControl) {
            if (projection != null && !projectRequested) { projectRequested = true; events.project(projection); }
        } else if (!joining && accessPoint != null && (projection != null || control != null)) {
            joining = true;
            events.join(accessPoint, projection != null ? projection : control, projection == null, setupToken.clone());
        }
    }
    public synchronized void connected() throws IOException {
        if (!modern() && !tcpControl) sender.send(CONNECT_STATUS, new Proto.Writer().integer(1, 0).build());
    }
    public synchronized void failed(int status) throws IOException {
        if (!tcpControl) sender.send(CONNECT_STATUS, new Proto.Writer().integer(1, status).build());
    }
    private static String unquote(String value) {
        return value.length() > 1 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1) : value;
    }
}
