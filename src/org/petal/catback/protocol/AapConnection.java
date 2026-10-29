package org.petal.catback.protocol;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import javax.net.ssl.SSLEngine;

// reader + locked writer + tls for one AAP session
public final class AapConnection implements Closeable {
    public interface Listener { void message(int channel, boolean control, boolean encrypted, int id, byte[] body) throws Exception; }
    private final DataInputStream input;
    private final DataOutputStream output;
    private final Closeable transport;
    private final TlsPipe tls;
    private final Object writer = new Object();
    private final Object crypto = new Object();
    private final Pending[] pending = new Pending[256];
    private volatile boolean closed;
    private int buffered;
    private static final int LIMIT = 4 * 1024 * 1024;

    private static final class Pending {
        final int total, flags;
        final ByteArrayOutputStream data = new ByteArrayOutputStream();
        Pending(int t, int f) { total = t; flags = f; }
    }

    public AapConnection(InputStream in, OutputStream out, Closeable owner, SSLEngine engine) throws IOException {
        input = new DataInputStream(in); output = new DataOutputStream(out); transport = owner;
        tls = new TlsPipe(engine);
    }

    public void handshake(byte[] data) throws IOException {
        byte[] reply;
        synchronized (crypto) { reply = tls.handshake(data); }
        if (reply.length > 0) send(0, false, false, 3, reply);
    }
    public boolean tlsReady() { synchronized (crypto) { return tls.ready; } }

    public void readLoop(Listener listener) throws Exception {
        while (!closed) {
            int channel = input.readUnsignedByte(), flags = input.readUnsignedByte();
            if ((flags & ~15) != 0) throw new IOException("Unsupported AAP flags " + flags);
            int size = input.readUnsignedShort(), fragment = flags & 3;
            int total = fragment == 1 ? input.readInt() : -1;
            if (fragment == 1 && (total < 2 || total > LIMIT)) throw new IOException("AAP message length limit");
            byte[] payload = new byte[size]; input.readFully(payload);
            if ((flags & 8) != 0) synchronized (crypto) { payload = tls.decrypt(payload); }
            Pending p = pending[channel];
            if ((flags & 1) != 0) {
                if (p != null) throw new IOException("Duplicate first fragment");
                p = new Pending(total, flags & 12); pending[channel] = p;
            }
            if (p == null || p.flags != (flags & 12)) throw new IOException("Invalid continuation fragment");
            if (payload.length > LIMIT - p.data.size() || buffered + payload.length > 8 * 1024 * 1024)
                throw new IOException("AAP reassembly limit");
            p.data.write(payload); buffered += payload.length;
            if (p.total >= 0 && p.data.size() > p.total) throw new IOException("AAP fragment overflow");
            if ((flags & 2) != 0) {
                byte[] message = p.data.toByteArray();
                buffered -= message.length; pending[channel] = null;
                if (message.length < 2 || (p.total >= 0 && message.length != p.total))
                    throw new IOException("AAP incomplete message");
                int id = ((message[0] & 255) << 8) | (message[1] & 255);
                listener.message(channel, (flags & 4) != 0, (flags & 8) != 0, id,
                        Arrays.copyOfRange(message, 2, message.length));
            }
        }
    }

    public void send(int channel, boolean control, boolean encrypted, int id, byte[] body) throws IOException {
        if (channel < 0 || channel > 255 || body.length > LIMIT - 2) throw new IOException("AAP send size/channel limit");
        byte[] message = ByteBuffer.allocate(body.length + 2).putShort((short) id).put(body).array();
        synchronized (writer) {
            if (closed) throw new IOException("Connection closed");
            for (int offset = 0; offset < message.length;) {
                int length = Math.min(12000, message.length - offset);
                int flags = (offset == 0 ? 1 : 0) | (offset + length == message.length ? 2 : 0)
                        | (control ? 4 : 0) | (encrypted ? 8 : 0);
                byte[] payload = Arrays.copyOfRange(message, offset, offset + length);
                // don't sit on the tls lock during a blocking write or acks pile up and everything stalls
                if (encrypted) synchronized (crypto) { payload = tls.encrypt(payload); }
                output.writeByte(channel); output.writeByte(flags); output.writeShort(payload.length);
                if ((flags & 3) == 1) output.writeInt(message.length);
                output.write(payload); offset += length;
            }
            output.flush();
        }
    }

    @Override public void close() throws IOException {
        closed = true;
        transport.close(); // also kicks the reader out
    }
}
