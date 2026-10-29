package org.petal.catback.audio;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.audiopolicy.AudioMix;
import android.media.audiopolicy.AudioMixingRule;
import android.media.audiopolicy.AudioPolicy;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.util.Log;
import java.io.Closeable;

// grabs phone audio -> pcm for the car. SYSTEM (rom build, audio policy loopback) > ROOT (su) > APP (projection prompt)
// dumb linear resampler, capture is always 48k stereo and only goes down if the car wants less
public final class AudioBridge implements Closeable {
    public enum Mode { OFF, APP, ROOT, SYSTEM }
    public interface Sink { void pcm(byte[] frame, long timestampUs); }

    private static final int CAPTURE_RATE = 48000, CAPTURE_CHANNELS = 2;
    private final Context context;
    private final int outRate, outChannels;
    private final Sink sink;
    private volatile boolean stopped;
    private Thread worker;
    public volatile Mode mode = Mode.OFF;
    public volatile String status = "Stopped";

    public AudioBridge(Context context, int outRate, int outChannels, Sink sink) {
        this.context = context.getApplicationContext();
        this.outRate = outRate; this.outChannels = outChannels; this.sink = sink;
    }

    public void startApp(MediaProjection projection) {
        if (Build.VERSION.SDK_INT < 29 || projection == null) { fail("App capture needs Android 10+ and screen-capture consent"); return; }
        start(() -> appRecord(projection), Mode.APP, "Capturing app media (no root)");
    }
    public void startRoot() { start(this::rootStream, Mode.ROOT, "Capturing all phone audio (root)"); }
    public static boolean rootAvailable() { return RootShell.available(); }
    public void startSystem() { start(this::systemRecord, Mode.SYSTEM, "Capturing all phone audio"); }
    // only true on the rom build, priv-app gets MODIFY_AUDIO_ROUTING
    public static boolean systemAvailable(Context context) {
        return context.checkSelfPermission(Manifest.permission.MODIFY_AUDIO_ROUTING) == PackageManager.PERMISSION_GRANTED;
    }

    private interface Source { Pump open() throws Exception; }
    private interface Pump { int read(short[] buffer) throws Exception; void close(); }

    private synchronized void start(Source source, Mode intended, String ok) {
        stop();
        stopped = false;
        worker = new Thread(() -> {
            Pump pump = null;
            try {
                pump = source.open();
                mode = intended; status = ok;
                Log.i("Catback", "Audio bridge: " + ok);
                loop(pump);
            } catch (Exception error) {
                if (!stopped) fail(reason(intended) + ": " + error.getMessage());
            } finally { if (pump != null) pump.close(); }
        }, "Catback-AudioCapture");
        worker.start();
    }

    private void loop(Pump pump) throws Exception {
        int frame = CAPTURE_RATE / 50;                 // 20 ms
        short[] in = new short[frame * CAPTURE_CHANNELS];
        double ratio = CAPTURE_RATE / (double) outRate;
        double phase = 0;
        while (!stopped) {
            int read = pump.read(in);
            if (read <= 0) { if (read < 0) break; continue; }
            int frames = read / CAPTURE_CHANNELS;
            int outFrames = (int) (frames / ratio) + 2;
            byte[] out = new byte[outFrames * outChannels * 2];
            int o = 0;
            while (phase < frames - 1) {
                int i = (int) phase; double f = phase - i;
                int base = i * CAPTURE_CHANNELS;
                short l = lerp(in[base], in[base + 1], f), r = CAPTURE_CHANNELS > 1 ? lerp(in[base + 1], in[Math.min(in.length - 1, base + 3)], f) : l;
                if (outChannels == 1) { short m = (short) ((l + r) / 2); o = put(out, o, m); }
                else { o = put(out, o, l); o = put(out, o, r); }
                phase += ratio;
            }
            phase -= frames;
            if (o > 0 && !stopped) {
                byte[] exact = o == out.length ? out : java.util.Arrays.copyOf(out, o);
                sink.pcm(exact, System.nanoTime() / 1000);
            }
        }
    }
    private static short lerp(short a, short b, double f) { return (short) (a + (b - a) * f); }
    private static int put(byte[] out, int o, short v) { out[o++] = (byte) v; out[o++] = (byte) (v >>> 8); return o; }

    @android.annotation.SuppressLint("MissingPermission") // settings asks for RECORD_AUDIO first
    private Pump appRecord(MediaProjection projection) {
        AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build();
        AudioFormat format = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(CAPTURE_RATE).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build();
        int min = AudioRecord.getMinBufferSize(CAPTURE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord record = new AudioRecord.Builder().setAudioFormat(format)
                .setBufferSizeInBytes(Math.max(min, CAPTURE_RATE / 5 * CAPTURE_CHANNELS * 2))
                .setAudioPlaybackCaptureConfig(config).build();
        return audioRecordPump(record);
    }
    private Closeable rootProcess;
    private Pump rootStream() throws Exception {
        RootShell.Stream stream = RootShell.captureAudio(context, CAPTURE_RATE, CAPTURE_CHANNELS);
        rootProcess = stream;
        java.io.DataInputStream in = new java.io.DataInputStream(stream.out());
        byte[] raw = new byte[CAPTURE_RATE / 50 * CAPTURE_CHANNELS * 2];
        return new Pump() {
            public int read(short[] buffer) throws Exception {
                in.readFully(raw, 0, buffer.length * 2);
                for (int i = 0, j = 0; j < buffer.length; i += 2, j++) buffer[j] = (short) ((raw[i] & 0xff) | (raw[i + 1] << 8));
                return buffer.length;
            }
            public void close() { stream.close(); }
        };
    }
    // bare REMOTE_SUBMIX never gets media routed to it on a phone, so loop media through our own policy mix.
    // loopback-only means it leaves the phone speaker too, which is what you want in a car
    private Pump systemRecord() {
        AudioManager manager = context.getSystemService(AudioManager.class);
        AudioFormat format = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(CAPTURE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build();
        AudioMixingRule rule = new AudioMixingRule.Builder()
                .addRule(usage(AudioAttributes.USAGE_MEDIA), AudioMixingRule.RULE_MATCH_ATTRIBUTE_USAGE)
                .addRule(usage(AudioAttributes.USAGE_GAME), AudioMixingRule.RULE_MATCH_ATTRIBUTE_USAGE)
                .addRule(usage(AudioAttributes.USAGE_UNKNOWN), AudioMixingRule.RULE_MATCH_ATTRIBUTE_USAGE).build();
        AudioMix mix = new AudioMix.Builder(rule).setFormat(format).setRouteFlags(AudioMix.ROUTE_FLAG_LOOP_BACK).build();
        AudioPolicy policy = new AudioPolicy.Builder(context).addMix(mix).build();
        int result = manager.registerAudioPolicy(policy);
        if (result != AudioManager.SUCCESS) throw new IllegalStateException("audio policy refused (" + result + ")");
        AudioRecord record;
        try { record = policy.createAudioRecordSink(mix); }
        catch (RuntimeException e) { manager.unregisterAudioPolicy(policy); throw e; }
        if (record == null) { manager.unregisterAudioPolicy(policy); throw new IllegalStateException("no loopback recorder"); }
        return audioRecordPump(record, () -> manager.unregisterAudioPolicy(policy));
    }
    private static AudioAttributes usage(int usage) { return new AudioAttributes.Builder().setUsage(usage).build(); }
    private Pump audioRecordPump(AudioRecord record) { return audioRecordPump(record, null); }
    private Pump audioRecordPump(AudioRecord record, Runnable after) {
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release(); if (after != null) after.run();
            throw new IllegalStateException("AudioRecord unavailable");
        }
        record.startRecording();
        return new Pump() {
            public int read(short[] buffer) { return record.read(buffer, 0, buffer.length); }
            public void close() {
                try { record.stop(); } catch (RuntimeException ignored) { }
                record.release();
                if (after != null) try { after.run(); } catch (RuntimeException ignored) { }
            }
        };
    }

    private String reason(Mode m) {
        return m == Mode.ROOT ? "Root capture failed" : m == Mode.SYSTEM ? "System capture failed" : "App capture failed";
    }
    private void fail(String message) { mode = Mode.OFF; status = message; Log.w("Catback", "Audio bridge: " + message); }

    @Override public synchronized void close() { stop(); }
    private void stop() {
        stopped = true;
        Thread w = worker; worker = null;
        if (w != null) w.interrupt();
        Closeable r = rootProcess; rootProcess = null;
        if (r != null) try { r.close(); } catch (Exception ignored) { }
        mode = Mode.OFF; status = "Stopped";
    }

}
