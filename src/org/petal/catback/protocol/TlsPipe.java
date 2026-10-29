package org.petal.catback.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;

// tls records inside AAP frames, not tls over the socket. caller locks
public final class TlsPipe {
    private final SSLEngine engine;
    private final ByteBuffer network = ByteBuffer.allocate(131072);
    public boolean ready;

    public TlsPipe(SSLEngine engine) throws IOException {
        this.engine = engine;
        engine.setUseClientMode(false);
        engine.setNeedClientAuth(true);
        engine.setEnabledProtocols(new String[]{"TLSv1.2"});
        engine.beginHandshake();
    }

    public byte[] handshake(byte[] incoming) throws IOException {
        append(incoming);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteBuffer app = ByteBuffer.allocate(65536);
        for (int count = 0; count < 128; count++) {
            SSLEngineResult.HandshakeStatus state = engine.getHandshakeStatus();
            switch (state) {
                case NEED_TASK:
                    Runnable task;
                    while ((task = engine.getDelegatedTask()) != null) task.run();
                    break;
                case NEED_WRAP:
                    ByteBuffer record = ByteBuffer.allocate(65536);
                    SSLEngineResult wrapped = engine.wrap(ByteBuffer.allocate(0), record);
                    check(wrapped);
                    output.write(record.array(), 0, record.position());
                    if (wrapped.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.FINISHED) ready = true;
                    break;
                case NEED_UNWRAP:
                    network.flip();
                    SSLEngineResult result = engine.unwrap(network, app);
                    network.compact();
                    check(result);
                    if (result.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.FINISHED) ready = true;
                    if (result.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) return output.toByteArray();
                    break;
                case FINISHED:
                    ready = true;
                    return output.toByteArray();
                case NOT_HANDSHAKING:
                    if (!ready) throw new IOException("TLS stopped before FINISHED");
                    return output.toByteArray();
                default: throw new IOException("Unsupported TLS handshake state " + state);
            }
        }
        throw new IOException("TLS handshake made too many transitions");
    }

    public byte[] encrypt(byte[] plaintext) throws IOException {
        if (!ready) throw new IOException("TLS not ready");
        ByteBuffer input = ByteBuffer.wrap(plaintext), output = ByteBuffer.allocate(65536);
        SSLEngineResult result = engine.wrap(input, output);
        check(result);
        if (input.hasRemaining() || result.getHandshakeStatus() != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING)
            throw new IOException("Unexpected TLS wrap progress");
        byte[] bytes = new byte[output.position()];
        output.flip(); output.get(bytes);
        return bytes;
    }

    public byte[] decrypt(byte[] ciphertext) throws IOException {
        if (!ready) throw new IOException("Encrypted frame before TLS completion");
        append(ciphertext);
        ByteBuffer output = ByteBuffer.allocate(65536);
        network.flip();
        while (network.hasRemaining()) {
            SSLEngineResult result = engine.unwrap(network, output);
            check(result);
            if (result.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) break;
            if (result.bytesConsumed() == 0 && result.bytesProduced() == 0)
                throw new IOException("TLS unwrap made no progress");
        }
        network.compact();
        byte[] bytes = new byte[output.position()];
        output.flip(); output.get(bytes);
        return bytes;
    }

    private void append(byte[] data) throws IOException {
        if (data.length > network.remaining()) throw new IOException("TLS input limit");
        network.put(data);
    }
    private static void check(SSLEngineResult r) throws IOException {
        if (r.getStatus() == SSLEngineResult.Status.CLOSED) throw new IOException("TLS peer closed");
        if (r.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) throw new IOException("TLS output limit");
    }
}
