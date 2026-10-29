package org.petal.catback.audio;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import java.io.OutputStream;

// runs as root via su + app_process, dumps the submix as raw s16le to stdout till we hang up
public final class RootAudioHelper {
    @android.annotation.SuppressLint("MissingPermission") // uid 0 doesn't care about RECORD_AUDIO
    public static void main(String[] args) throws Exception {
        int rate = Integer.parseInt(args[0]), channels = Integer.parseInt(args[1]);
        int mask = channels == 1 ? AudioFormat.CHANNEL_IN_MONO : AudioFormat.CHANNEL_IN_STEREO;
        int min = AudioRecord.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord record = new AudioRecord(MediaRecorder.AudioSource.REMOTE_SUBMIX, rate, mask,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(min, rate / 5 * channels * 2));
        if (record.getState() != AudioRecord.STATE_INITIALIZED) { System.err.println("AudioRecord not initialized"); System.exit(2); }
        record.startRecording();
        OutputStream out = System.out;
        byte[] buffer = new byte[rate / 50 * channels * 2];
        try {
            while (true) {
                int n = record.read(buffer, 0, buffer.length);
                if (n < 0) break;
                out.write(buffer, 0, n);
                out.flush();
            }
        } finally { record.stop(); record.release(); }
    }
}
