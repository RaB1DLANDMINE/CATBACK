package org.petal.catback.audio;

import android.content.Context;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

// su launcher for the root audio helper, magisk/ksu whatever
final class RootShell {
    private RootShell() { }

    static final class Stream implements Closeable {
        private final Process process;
        Stream(Process process) { this.process = process; }
        InputStream out() { return process.getInputStream(); }
        @Override public void close() { process.destroy(); }
    }

    // this is the bit that makes magisk pop its prompt
    static boolean available() {
        try {
            Process p = new ProcessBuilder("su", "-c", "id -u").redirectErrorStream(true).start();
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[64]; int n;
            while ((n = p.getInputStream().read(chunk)) > 0) bytes.write(chunk, 0, n);
            String uid = bytes.toString().trim();
            return p.waitFor() == 0 && uid.equals("0");
        } catch (IOException | InterruptedException e) { return false; }
    }

    static Stream captureAudio(Context context, int rate, int channels) throws IOException {
        String apk = context.getPackageCodePath();
        String command = "CLASSPATH=" + apk + " exec app_process /system/bin " + RootAudioHelper.class.getName() + " " + rate + " " + channels;
        return new Stream(new ProcessBuilder("su", "-c", command).start());
    }
}
