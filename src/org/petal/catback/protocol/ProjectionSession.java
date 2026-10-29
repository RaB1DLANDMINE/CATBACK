package org.petal.catback.protocol;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// phone side AAP 1.x, zero google code
public final class ProjectionSession implements Closeable, AapConnection.Listener {
    public interface Host {
        void log(String text);
        void startVideo(VideoConfig config, ProjectionSession session);
        void stopVideo();
        void audio(boolean playing, AudioConfig config, ProjectionSession session);
        void touch(float x, float y, int action);
        void key(int code);
        void evidence(String name, byte[] data);
        default void audioReady(ProjectionSession session) { }
    }
    // 1 guidance 2 system 3 media 4 telephony. media first, cheap aftermarket screens only have system so fall back
    private static int audioRank(long type) { return type == 3 ? 3 : type == 2 ? 2 : type == 1 ? 1 : 0; }
    public static final class VideoConfig {
        public int width, height, fps, index;
        // visible bit of the frame, car crops margins evenly and may nick more for its own ui
        public int contentLeft, contentTop, contentWidth, contentHeight;
        // ui units, 480 tall, width follows real aspect
        public float layoutWidth = 800, layoutHeight = 480;
        public VideoConfig(int w, int h, int f, int i) {
            width = w; height = h; fps = f; index = i;
            contentWidth = w; contentHeight = h;
        }
        // margins = VideoConfiguration 3/4, insets l/t/r/b, pixelAspectE4 = field 8 (0 square)
        public void fit(int marginW, int marginH, int[] insets, int pixelAspectE4) {
            marginW = clamp(marginW, 0, width / 2); marginH = clamp(marginH, 0, height / 2);
            int left = marginW / 2 + clamp(insets[0], 0, width / 4), top = marginH / 2 + clamp(insets[1], 0, height / 4);
            int right = marginW - marginW / 2 + clamp(insets[2], 0, width / 4);
            int bottom = marginH - marginH / 2 + clamp(insets[3], 0, height / 4);
            // cars lie, never crop more than half
            if (left + right > width / 2) { float k = (width / 2f) / (left + right); left = (int) (left * k); right = (int) (right * k); }
            if (top + bottom > height / 2) { float k = (height / 2f) / (top + bottom); top = (int) (top * k); bottom = (int) (bottom * k); }
            contentLeft = left; contentTop = top;
            contentWidth = width - left - right; contentHeight = height - top - bottom;
            // stretched pixels, lay out for what the driver actually sees
            float aspect = contentWidth / (float) contentHeight * (pixelAspectE4 > 0 ? pixelAspectE4 / 10000f : 1f);
            layoutHeight = 480;
            layoutWidth = Math.max(560, Math.min(1600, 480 * aspect));
        }
        private static int clamp(int value, int low, int high) { return Math.max(low, Math.min(high, value)); }
    }
    public static final class AudioConfig {
        public int rate, channels, index;
        public AudioConfig(int r, int c, int i) { rate = r; channels = c; index = i; }
    }
    private static final class Window {
        private int maximum = 1, pending;
        private boolean closed;
        synchronized void configure(int count) throws IOException {
            if (count < 1 || count > 4096) throw new IOException("Invalid media ACK window " + count);
            maximum = Math.min(count, 64); pending = 0;
        }
        synchronized void acquire() throws IOException {
            long deadline = System.nanoTime() + 3_000_000_000L;
            while (!closed && pending >= maximum) {
                long left = deadline - System.nanoTime();
                if (left <= 0) throw new IOException("Head unit stopped acknowledging media");
                try { wait(Math.max(1, left / 1_000_000)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("Media interrupted", e); }
            }
            if (closed) throw new IOException("Media closed");
            pending++;
        }
        synchronized void acknowledge(int count) throws IOException {
            if (count < 1 || count > 4096) throw new IOException("Invalid media ACK count");
            pending = Math.max(0, pending - count); notifyAll();
        }
        synchronized void close() { closed = true; notifyAll(); }
    }
    private static final class Channel {
        int id, kind, audioType, maxUnacked = 1;
        volatile int session = 1;
        volatile boolean opened, configured, started;
        Proto.Message descriptor;
        final Window window = new Window();
        Channel(int id, int kind, Proto.Message descriptor) { this.id = id; this.kind = kind; this.descriptor = descriptor; }
    }
    private final AapConnection connection;
    private final Host host;
    private final Map<Integer, Channel> channels = new ConcurrentHashMap<>();
    private volatile Channel video, audio;
    private volatile VideoConfig videoConfig;
    private volatile AudioConfig audioConfig;
    private volatile boolean authenticated, closed, videoFocused, audioFocused, mediaAudio;
    private boolean versionExchanged, discovered;
    private float touchWidth = 800, touchHeight = 480;
    public volatile long videoPackets, audioPackets, touchEvents;

    public ProjectionSession(AapConnection connection, Host host) { this.connection = connection; this.host = host; }
    public void run() throws Exception { connection.readLoop(this); }

    @Override public void message(int channel, boolean control, boolean encrypted, int id, byte[] body) throws Exception {
        // pings every second were drowning the whole log, skip em
        if (id != 0x8004 && !(channel != 0 && id == 0) && !(channel == 0 && (id == 0x000b || id == 0x000c)))
            host.log(String.format(Locale.ROOT, "RX ch=%d id=0x%04x %s bytes=%d", channel, id, encrypted ? "TLS" : "plain", body.length));
        if (channel == 0 || control) {
            common(channel, id, body, encrypted);
            return;
        }
        if (!authenticated || !encrypted) throw new IOException("Service message before encrypted authentication");
        Channel c = channels.get(channel);
        if (c == null || !c.opened) throw new IOException("Message on unopened channel " + channel);
        Proto.Message p = new Proto.Message(body);
        if (c.kind == 3 || c.kind == 1) {
            if (id == 0x8003) setup(c, p);
            else if (id == 0x8004) {
                if (p.integer(1, -1) == c.session) c.window.acknowledge((int) p.integer(2, 0));
                else host.log("Ignoring old ACK on ch=" + c.id + " session=" + p.integer(1, -1) + "; current=" + c.session);
            }
            else if (id == 0x8008 && c == video) {
                videoFocused = p.integer(1, 2) == 1;
                host.log("Video focus " + (videoFocused ? "projected" : "native"));
                if (videoFocused) startVideo();
                else stopVideo();
            }
        } else if (c.kind == 4 && id == 0x8001) input(p);
        else if (c.kind == 4 && id == 0x8003 && p.integer(1, 0) != 0)
            host.log("Input binding rejected: " + p.integer(1, 0));
    }

    private void common(int channel, int id, byte[] body, boolean encrypted) throws Exception {
        if (id == 1 && channel == 0) {
            if (versionExchanged || body.length < 4) throw new IOException("Invalid version request");
            ByteBuffer in = ByteBuffer.wrap(body);
            int major = in.getShort() & 65535, minor = in.getShort() & 65535;
            boolean supported = major >= 1;
            int negotiatedMinor = major == 1 ? Math.min(7, minor) : 7;
            send(0, false, false, 2, ByteBuffer.allocate(6).putShort((short) 1)
                    .putShort((short) negotiatedMinor).putShort((short) (supported ? 0 : -1)).array());
            if (!supported) throw new IOException("Unsupported AAP version " + major + "." + minor);
            versionExchanged = true;
            host.log("Negotiated AAP 1." + negotiatedMinor + " (HU requested " + major + "." + minor + ")");
        } else if (id == 3 && channel == 0) {
            if (!versionExchanged || authenticated) throw new IOException("Unexpected TLS handshake");
            connection.handshake(body);
            if (connection.tlsReady()) host.log("TLS 1.2 handshake complete; waiting for auth complete");
        } else if (id == 4 && channel == 0) {
            if (!connection.tlsReady() || authenticated) throw new IOException("Invalid auth-complete sequence");
            if (new Proto.Message(body).integer(1, 0) != 0) throw new IOException("Head unit rejected TLS identity");
            authenticated = true;
            host.log("Authenticated; requesting head-unit services");
            send(0, false, true, 5, new Proto.Writer().text(4, "CATBACK").text(5, "PetalOS").build());
        } else if (id == 11) {
            send(channel, channel != 0, encrypted, 12, body);
        } else if (id == 15) {
            send(channel, channel != 0, encrypted, 16, Proto.EMPTY);
            close();
        } else {
            if (!authenticated || !encrypted) throw new IOException("Control message before authentication: " + id);
            if (id == 6 && channel == 0) { host.evidence("discovery.pb", body); discover(new Proto.Message(body)); }
            else if (id == 8) {
                Channel c = channels.get(channel);
                if (c == null || c.opened) throw new IOException("Unexpected channel-open response");
                if (new Proto.Message(body).integer(1, 0) != 0) throw new IOException("Channel " + channel + " rejected");
                c.opened = true;
                if (c.kind == 3 || c.kind == 1)
                    send(channel, false, true, 0x8000, new Proto.Writer().integer(1, c.kind == 3 ? 3 : 1).build());
                else if (c.kind == 4)
                    send(channel, false, true, 0x8002, new Proto.Writer().integer(1, 3).integer(1, 4)
                            .integer(1, 85).integer(1, 87).integer(1, 88).integer(1, 126).integer(1, 127).build());
            } else if (id == 19 && channel == 0) {
                long state = new Proto.Message(body).integer(1, 0);
                audioFocused = state == 1 || state == 2 || state == 6 || state == 7;
                host.log("Audio focus state " + state);
                updateAudio();
            } else if (id == 9) throw new IOException("Peer closed channel " + channel);
            else if (id != 12 && id != 14 && id != 16 && id != 23)
                host.log("Unhandled common message " + id);
        }
    }

    private void discover(Proto.Message response) throws Exception {
        if (discovered) throw new IOException("Duplicate discovery response");
        discovered = true;
        host.log("Head unit: " + response.text(2, "unknown") + " / " + response.text(8, "unknown model"));
        List<Channel> selected = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (Proto.Message descriptor : response.messages(1)) {
            int id = (int) descriptor.integer(1, -1);
            if (id < 1 || id > 255 || !seen.add(id)) throw new IOException("Invalid/duplicate service channel");
            if (descriptor.has(3)) {
                Proto.Message av = descriptor.message(3);
                int codec = (int) av.integer(1, 0);
                host.log("Service " + id + ": codec=" + codec + ", video configs=" + av.messages(4).size()
                        + ", audio configs=" + av.messages(3).size() + ", audio type=" + av.integer(2, 0));
                // newer sinks skip field 1 and stick the codec in field 10 (stock DHU does this)
                if (video == null && !av.messages(4).isEmpty()) {
                    Channel c = new Channel(id, 3, av); video = c; selected.add(c);
                } else if (codec == 1 && !av.messages(3).isEmpty() && audioRank(av.integer(2, 0)) > 0
                        && (audio == null || audioRank(av.integer(2, 0)) > audioRank(audio.audioType))) {
                    if (audio != null) selected.remove(audio);
                    Channel c = new Channel(id, 1, av); c.audioType = (int) av.integer(2, 0); audio = c; selected.add(c);
                }
            } else if (descriptor.has(4)) {
                Channel c = new Channel(id, 4, descriptor.message(4)); selected.add(c);
                Proto.Message touch = c.descriptor.message(2);
                touchWidth = Math.max(1, touch.integer(1, 800));
                touchHeight = Math.max(1, touch.integer(2, 480));
            }
            // sensors/mic/bt/nav not opened yet
        }
        if (video == null) throw new IOException("Receiver advertises no baseline H.264 video sink");
        for (Channel c : selected) {
            channels.put(c.id, c);
            host.log("Opening " + (c.kind == 3 ? "H.264 video" : c.kind == 1 ? (c.audioType == 3 ? "PCM media audio" : "PCM audio (type "
                    + c.audioType + ", used for phone media)") : "input") + " channel " + c.id);
            send(c.id, true, true, 7, new Proto.Writer().integer(1, c.kind == 3 ? 1 : 2).integer(2, c.id).build());
        }
    }

    private void setup(Channel c, Proto.Message response) throws Exception {
        if (c.configured || response.integer(1, 0) != 2) throw new IOException("Media setup failed on " + c.id);
        c.maxUnacked = (int) response.integer(2, 0);
        c.window.configure(c.maxUnacked);
        List<Integer> indices = response.integers(3);
        List<Proto.Message> configs = c.descriptor.messages(c.kind == 3 ? 4 : 3);
        if (indices.isEmpty()) throw new IOException("Receiver returned no media configurations");
        for (int index : indices) {
            if (index < 0 || index >= configs.size()) continue;
            Proto.Message config = configs.get(index);
            if (c.kind == 3) {
                int resolution = (int) config.integer(1, 0);
                int width = resolution == 1 ? 800 : resolution == 2 ? 1280 : resolution == 3 ? 1920 : 0;
                int height = resolution == 1 ? 480 : resolution == 2 ? 720 : resolution == 3 ? 1080 : 0;
                if (width == 0 || config.integer(10, 3) != 3) continue;
                // gearhead jbi.a(): 1 = 60fps, 2 = 30fps
                VideoConfig chosen = new VideoConfig(width, height, config.integer(2, 2) == 1 ? 60 : 30, index);
                int[] insets = insets(config);
                chosen.fit((int) config.integer(3, 0), (int) config.integer(4, 0), insets, (int) config.integer(8, 0));
                videoConfig = chosen;
                host.log("Video configuration " + index + ": " + width + "x" + height + " @" + chosen.fps
                        + ", margins=" + config.integer(3, 0) + "x" + config.integer(4, 0) + ", density=" + config.integer(5, 0)
                        + ", pixelAspectE4=" + config.integer(8, 0) + ", insets(l,t,r,b)=" + java.util.Arrays.toString(insets)
                        + ", touch=" + (int) touchWidth + "x" + (int) touchHeight);
                host.log("Drawing UI in visible area " + chosen.contentWidth + "x" + chosen.contentHeight + " at "
                        + chosen.contentLeft + "," + chosen.contentTop + "; layout " + (int) chosen.layoutWidth + "x" + (int) chosen.layoutHeight);
            } else {
                int rate = (int) config.integer(1, 0), count = (int) config.integer(3, 0);
                if (rate < 8000 || rate > 96000 || (count != 1 && count != 2) || config.integer(2, 0) != 16) continue;
                audioConfig = new AudioConfig(rate, count, index);
                host.log("Audio configuration " + index + ": " + rate + " Hz / " + count + " channels PCM16");
            }
            c.configured = true;
            if (c == video) {
                send(c.id, false, true, 0x8007, new Proto.Writer().integer(1, 0).integer(2, 1).integer(3, 1).build());
                startVideo();
            } else { host.audioReady(this); if (mediaAudio) updateAudio(); }
            return;
        }
        throw new IOException("No supported media configuration on channel " + c.id);
    }

    private void startVideo() throws IOException {
        Channel v = video;
        if (closed || v == null || !v.configured || !videoFocused || v.started) return;
        v.started = true;
        send(v.id, false, true, 0x8001, new Proto.Writer().integer(1, v.session).integer(2, videoConfig.index).build());
        host.log("Starting projected H.264 surface");
        host.startVideo(videoConfig, this);
    }
    private void stopVideo() throws IOException {
        Channel v = video;
        host.stopVideo();
        if (v != null && v.started) {
            v.started = false;
            send(v.id, false, true, 0x8002, Proto.EMPTY);
            v.session++;
            v.window.configure(v.maxUnacked);
        }
    }

    // field 11 AdditionalVideoConfig (gearhead wcb), margin_configs 7 -> l=1 t=2 r=3 b=4
    private static int[] insets(Proto.Message config) {
        int[] result = new int[4];
        try {
            if (!config.has(11)) return result;
            List<Proto.Message> margins = config.message(11).messages(7);
            if (margins.isEmpty() || !margins.get(0).has(1)) return result;
            Proto.Message inset = margins.get(0).message(1);
            for (int i = 0; i < 4; i++) result[i] = (int) Math.max(0, inset.integer(i + 1, 0));
        } catch (IOException malformed) { /* just a hint, whatever */ }
        return result;
    }

    // touch coords -> ui units. full-frame panels include the cropped margins, else panel = visible area
    private float[] layoutPoint(long touchX, long touchY) {
        VideoConfig v = videoConfig;
        if (v == null) return new float[]{touchX * 800f / touchWidth, touchY * 480f / touchHeight};
        float fx = touchX / touchWidth, fy = touchY / touchHeight;
        boolean fullFrame = Math.abs(touchWidth - v.width) <= 1 && Math.abs(touchHeight - v.height) <= 1
                && (v.contentWidth != v.width || v.contentHeight != v.height);
        if (fullFrame) {
            fx = (fx * v.width - v.contentLeft) / v.contentWidth;
            fy = (fy * v.height - v.contentTop) / v.contentHeight;
        }
        return new float[]{fx * v.layoutWidth, fy * v.layoutHeight};
    }

    private void input(Proto.Message event) throws IOException {
        if (event.has(3)) {
            Proto.Message touch = event.message(3);
            List<Proto.Message> locations = touch.messages(1);
            if (!locations.isEmpty()) {
                int index = (int) touch.integer(2, 0);
                if (index < 0 || index >= locations.size()) return;
                Proto.Message point = locations.get(index);
                int action = (int) touch.integer(3, 0);
                float[] layout = layoutPoint(point.integer(1, 0), point.integer(2, 0));
                float x = layout[0], y = layout[1];
                touchEvents++;
                host.touch(x, y, action);
                host.log("Touch " + action + " at " + (int) x + "," + (int) y);
            }
        }
        if (event.has(4)) {
            for (Proto.Message key : event.message(4).messages(1)) {
                if (key.integer(2, 0) == 0) {
                    int code = (int) key.integer(1, 0);
                    host.key(code); host.log("Key released " + code);
                }
            }
        }
    }

    // car audio on/off, grabs or drops focus then starts/stops pcm
    public synchronized void setMediaAudio(boolean enabled) {
        mediaAudio = enabled;
        try {
            if (!authenticated || audio == null) { host.log("No PCM audio sink available"); return; }
            send(0, false, true, 18, new Proto.Writer().integer(1, enabled ? 1 : 4).build());
            updateAudio();
        } catch (Exception e) { fail(e); }
    }
    private synchronized void updateAudio() throws IOException {
        Channel a = audio;
        if (a == null || !a.configured) return;
        if (mediaAudio && audioFocused && !a.started) {
            a.started = true;
            send(a.id, false, true, 0x8001, new Proto.Writer().integer(1, a.session).integer(2, audioConfig.index).build());
            host.audio(true, audioConfig, this);
        } else if ((!mediaAudio || !audioFocused) && a.started) {
            host.audio(false, audioConfig, this);
            a.started = false;
            send(a.id, false, true, 0x8002, Proto.EMPTY);
            a.session++;
            a.window.configure(a.maxUnacked);
        }
    }

    public void video(byte[] nal, long timestampUs, boolean codecConfig) throws IOException {
        Channel v = video;
        if (closed || v == null || !v.started) return;
        int generation = v.session;
        try { v.window.acquire(); }
        catch (IOException e) { throw new IOException("Video channel " + v.id + " session " + v.session + ": " + e.getMessage(), e); }
        if (!v.started || v.session != generation) { v.window.acknowledge(1); return; }
        byte[] body = codecConfig ? nal : ByteBuffer.allocate(nal.length + 8).putLong(timestampUs).put(nal).array();
        connection.send(v.id, false, true, codecConfig ? 1 : 0, body);
        videoPackets++;
    }
    // capture threads use this so a dead send ends the session instead of blowing up the loop
    public void pcmQuiet(byte[] data, long timestampUs) {
        try { pcm(data, timestampUs); } catch (Exception e) { fail(e); }
    }
    public void pcm(byte[] data, long timestampUs) throws IOException {
        Channel a = audio;
        if (closed || a == null || !a.started) return;
        int generation = a.session;
        try { a.window.acquire(); }
        catch (IOException e) { throw new IOException("Audio channel " + a.id + " session " + a.session + ": " + e.getMessage(), e); }
        if (!a.started || a.session != generation) { a.window.acknowledge(1); return; }
        connection.send(a.id, false, true, 0, ByteBuffer.allocate(data.length + 8).putLong(timestampUs).put(data).array());
        audioPackets++;
    }
    private void send(int channel, boolean control, boolean encrypted, int id, byte[] body) throws IOException {
        if (!(channel == 0 && (id == 0x000b || id == 0x000c))) host.log(String.format(Locale.ROOT, "TX ch=%d id=0x%04x %s bytes=%d", channel, id, encrypted ? "TLS" : "plain", body.length));
        connection.send(channel, control, encrypted, id, body);
    }
    public void fail(Exception error) {
        if (!closed) host.log("Session failed: " + error.getClass().getSimpleName() + ": " + error.getMessage());
        try { close(); } catch (IOException ignored) { }
    }
    @Override public void close() throws IOException {
        if (closed) return;
        closed = true;
        for (Channel c : channels.values()) c.window.close();
        host.stopVideo(); host.audio(false, audioConfig, this);
        host.log("Session closed; video=" + videoPackets + ", audio=" + audioPackets + ", touch=" + touchEvents);
        connection.close();
    }
}
