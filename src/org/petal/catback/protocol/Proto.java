package org.petal.catback.protocol;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// tiny bounded protobuf reader/writer, keeps unknown fields
public final class Proto {
    public static final byte[] EMPTY = new byte[0];
    public static final int LIMIT = 4 * 1024 * 1024;

    public static final class Field {
        public final int number, wire;
        public final long value;
        public final byte[] bytes;
        Field(int n, int w, long v, byte[] b) { number = n; wire = w; value = v; bytes = b; }
    }

    public static final class Message {
        public final List<Field> fields;
        public Message(byte[] data) throws IOException { fields = parse(data); }
        public long integer(int number, long fallback) {
            for (Field f : fields) if (f.number == number && f.wire == 0) return f.value;
            return fallback;
        }
        public boolean has(int number) {
            for (Field f : fields) if (f.number == number) return true;
            return false;
        }
        public byte[] bytes(int number) {
            for (Field f : fields) if (f.number == number && f.wire == 2) return f.bytes;
            return EMPTY;
        }
        public String text(int number, String fallback) {
            return has(number) ? new String(bytes(number), StandardCharsets.UTF_8) : fallback;
        }
        public Message message(int number) throws IOException { return new Message(bytes(number)); }
        public List<Message> messages(int number) throws IOException {
            List<Message> result = new ArrayList<>();
            for (Field f : fields) if (f.number == number && f.wire == 2) result.add(new Message(f.bytes));
            return result;
        }
        public List<Integer> integers(int number) throws IOException {
            List<Integer> result = new ArrayList<>();
            for (Field f : fields) if (f.number == number) {
                if (f.wire == 0) result.add((int) f.value);
                else if (f.wire == 2) {
                    Cursor c = new Cursor(f.bytes);
                    while (c.pos < f.bytes.length) result.add((int) c.varint());
                }
            }
            return result;
        }
    }

    private static final class Cursor {
        final byte[] data;
        int pos;
        Cursor(byte[] d) { data = d; }
        long varint() throws IOException {
            long value = 0;
            for (int shift = 0; shift < 64; shift += 7) {
                if (pos == data.length) throw new IOException("Truncated protobuf varint");
                int b = data[pos++] & 255;
                if (shift == 63 && (b & 254) != 0) throw new IOException("Protobuf varint overflow");
                value |= (long) (b & 127) << shift;
                if ((b & 128) == 0) return value;
            }
            throw new IOException("Invalid protobuf varint");
        }
        byte[] take(int length) throws IOException {
            if (length < 0 || length > data.length - pos) throw new IOException("Truncated protobuf field");
            byte[] result = Arrays.copyOfRange(data, pos, pos + length);
            pos += length;
            return result;
        }
    }

    private static List<Field> parse(byte[] data) throws IOException {
        if (data.length > LIMIT) throw new IOException("Protobuf message too large");
        List<Field> result = new ArrayList<>();
        Cursor c = new Cursor(data);
        while (c.pos < data.length) {
            if (result.size() >= 16384) throw new IOException("Too many protobuf fields");
            long tag = c.varint();
            if (tag <= 0 || tag >>> 3 > 0x1fffffff) throw new IOException("Invalid protobuf tag");
            int n = (int) (tag >>> 3), w = (int) (tag & 7);
            if (n == 0) throw new IOException("Invalid protobuf field zero");
            switch (w) {
                case 0: result.add(new Field(n, w, c.varint(), EMPTY)); break;
                case 1: result.add(new Field(n, w, 0, c.take(8))); break;
                case 2:
                    long length = c.varint();
                    if (length < 0 || length > LIMIT) throw new IOException("Protobuf length limit");
                    result.add(new Field(n, w, 0, c.take((int) length))); break;
                case 5: result.add(new Field(n, w, 0, c.take(4))); break;
                default: throw new IOException("Unsupported protobuf wire type " + w);
            }
        }
        return result;
    }

    public static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private void varint(long v) {
            while ((v & ~127L) != 0) { out.write((int) (v & 127) | 128); v >>>= 7; }
            out.write((int) v);
        }
        public Writer integer(int n, long v) { varint((long) n << 3); varint(v); return this; }
        public Writer bytes(int n, byte[] v) {
            varint(((long) n << 3) | 2); varint(v.length); out.write(v, 0, v.length); return this;
        }
        public Writer text(int n, String v) { return bytes(n, v.getBytes(StandardCharsets.UTF_8)); }
        public byte[] build() { return out.toByteArray(); }
    }
}
