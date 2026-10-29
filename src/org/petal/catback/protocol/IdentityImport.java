package org.petal.catback.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

// digs the car cert + key out of whatever android auto apk the user feeds us, nothing of google's ships with us
public final class IdentityImport {
    public static final class Result {
        public final PrivateKey key;
        public final X509Certificate leaf, root;
        Result(PrivateKey key, X509Certificate leaf, X509Certificate root) { this.key = key; this.leaf = leaf; this.root = root; }
        public KeyStore keyStore(char[] password) throws Exception {
            KeyStore store = KeyStore.getInstance("PKCS12"); store.load(null, null);
            store.setKeyEntry("catback-car", key, password, new Certificate[]{leaf, root});
            return store;
        }
    }

    private static final Pattern DEX = Pattern.compile("classes\\d*\\.dex");
    private static final byte[] BEGIN = "-----BEGIN CERTIFICATE-----".getBytes(StandardCharsets.US_ASCII),
            END = "-----END CERTIFICATE-----".getBytes(StandardCharsets.US_ASCII);
    private IdentityImport() { }

    // takes a plain .apk or an .apks/.xapk bundle, splits w/o dex just get skipped
    public static Result fromApk(InputStream apk) throws Exception {
        Set<String> pems = new LinkedHashSet<>();
        List<byte[]> arrays = new ArrayList<>();
        scanZip(new ZipInputStream(apk), pems, arrays, 0);
        if (pems.isEmpty()) throw new IOException("No Android Auto code in that file. Pick the base APK or the whole bundle");
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        List<byte[]> raw = new ArrayList<>(); List<X509Certificate> certs = new ArrayList<>();
        for (String pem : pems) {
            byte[] bytes = pem.getBytes(StandardCharsets.ISO_8859_1);
            try { certs.add((X509Certificate) factory.generateCertificate(new ByteArrayInputStream(bytes))); raw.add(bytes); }
            catch (Exception junk) { }
        }
        List<byte[]> salts = new ArrayList<>(), sealed = new ArrayList<>();
        for (byte[] a : arrays) {
            if (a.length <= 512) salts.add(a);
            if (a.length % 16 == 0 && a.length >= 1024 && a.length <= 3072) sealed.add(a);
        }
        for (int l = 0; l < certs.size(); l++) {
            X509Certificate leaf = certs.get(l);
            if (!leaf.getSubjectX500Principal().getName().contains("O=CarService")) continue;
            for (int r = 0; r < certs.size(); r++) {
                X509Certificate root = certs.get(r);
                if (r == l || !leaf.getIssuerX500Principal().equals(root.getSubjectX500Principal())) continue;
                try { leaf.verify(root.getPublicKey()); } catch (Exception notIt) { continue; }
                for (byte[] salt : salts) {
                    int[] state = derive(raw.get(l), raw.get(r), salt);
                    for (byte[] blob : sealed) {
                        PrivateKey key = unseal(state, blob);
                        if (key != null && matches(key, leaf)) return new Result(key, leaf, root);
                    }
                }
            }
        }
        throw new IOException("Couldn't find a car identity in this Android Auto version");
    }

    private static void scanZip(ZipInputStream zip, Set<String> pems, List<byte[]> arrays, int depth) throws IOException {
        ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
            String name = entry.getName(), leafName = name.substring(name.lastIndexOf('/') + 1);
            if (DEX.matcher(leafName).matches()) scanDex(readAll(zip), pems, arrays);
            else if (depth < 2 && leafName.endsWith(".apk")) scanZip(new ZipInputStream(new Unclosable(zip)), pems, arrays, depth + 1);
        }
    }

    private static void scanDex(byte[] d, Set<String> pems, List<byte[]> arrays) {
        for (int at = indexOf(d, BEGIN, 0); at >= 0; at = indexOf(d, BEGIN, at + 1)) {
            int end = indexOf(d, END, at);
            if (end < 0) break;
            end += END.length;
            if (end < d.length && d[end] == '\n') end++;
            pems.add(new String(d, at, end - at, StandardCharsets.ISO_8859_1));
        }
        // fill-array-data payloads w/ byte width, that's where the salt and sealed key hide
        for (int o = 0; o + 8 <= d.length; o += 4) {
            if (d[o] != 0 || d[o + 1] != 3 || d[o + 2] != 1 || d[o + 3] != 0) continue;
            long size = (d[o + 4] & 0xffL) | (d[o + 5] & 0xffL) << 8 | (d[o + 6] & 0xffL) << 16 | (d[o + 7] & 0xffL) << 24;
            if (size >= 16 && size <= 4096 && o + 8 + size <= d.length) arrays.add(Arrays.copyOfRange(d, o + 8, o + 8 + (int) size));
        }
    }

    // gearhead's own homebrew kdf, re-reads data[p] inside the loop on purpose since state aliases data at the end
    private static int[] derive(byte[] leaf, byte[] root, byte[] salt) {
        int[] state = new int[48], s = unsigned(salt);
        mix(unsigned(leaf), state, s); mix(unsigned(root), state, s);
        for (int i = 0; i < 7; i++) mix(state, state, s);
        return state;
    }
    private static void mix(int[] data, int[] state, int[] salt) {
        for (int p = 0; p < data.length; p++)
            for (int i = 0; i < 48; i++) {
                int v = state[i];
                state[i] = ((((v << 1) | (v >> 7)) + 33) ^ salt[i % salt.length] ^ data[p]) & 255;
            }
    }

    private static PrivateKey unseal(int[] state, byte[] blob) {
        try {
            byte[] k = new byte[32], iv = new byte[16];
            for (int i = 0; i < 32; i++) k[i] = (byte) state[i];
            for (int i = 0; i < 16; i++) iv[i] = (byte) state[32 + i];
            SecretKeySpec aes = new SecretKeySpec(k, "AES");
            Cipher peek = Cipher.getInstance("AES/CBC/NoPadding");
            peek.init(Cipher.DECRYPT_MODE, aes, new IvParameterSpec(iv));
            if (!new String(peek.doFinal(blob, 0, 16), StandardCharsets.ISO_8859_1).startsWith("-----BEGIN")) return null;
            Cipher full = Cipher.getInstance("AES/CBC/PKCS5Padding");
            full.init(Cipher.DECRYPT_MODE, aes, new IvParameterSpec(iv));
            String pem = new String(full.doFinal(blob), StandardCharsets.US_ASCII);
            if (!pem.contains("-----BEGIN PRIVATE KEY-----")) return null;
            String body = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
        } catch (Exception wrongGuess) { return null; }
    }

    private static boolean matches(PrivateKey key, X509Certificate leaf) {
        return key instanceof RSAPrivateKey && leaf.getPublicKey() instanceof RSAPublicKey
                && ((RSAPrivateKey) key).getModulus().equals(((RSAPublicKey) leaf.getPublicKey()).getModulus());
    }

    private static int[] unsigned(byte[] b) { int[] out = new int[b.length]; for (int i = 0; i < b.length; i++) out[i] = b[i] & 255; return out; }
    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20); byte[] chunk = new byte[1 << 16]; int n;
        while ((n = in.read(chunk)) > 0) out.write(chunk, 0, n);
        return out.toByteArray();
    }
    private static int indexOf(byte[] hay, byte[] needle, int from) {
        outer:
        for (int i = Math.max(0, from); i <= hay.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) if (hay[i + j] != needle[j]) continue outer;
            return i;
        }
        return -1;
    }
    // nested zip closing would nuke the outer bundle stream, nope
    private static final class Unclosable extends FilterInputStream {
        Unclosable(InputStream in) { super(in); }
        @Override public void close() { }
    }
}
