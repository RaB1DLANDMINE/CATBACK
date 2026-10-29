package org.petal.catback.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import javax.security.auth.x500.X500Principal;

// bare-bones self-signed cert, plain JCA
public final class LocalIdentity {
    public static KeyStore create(char[] password) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        KeyPair keys = generator.generateKeyPair();
        // sha256WithRSA oid w/ explicit NULL
        byte[] algorithm = der(0x30, new byte[]{6,9,42,(byte)134,72,(byte)134,(byte)247,13,1,1,11,5,0});
        // DHU wants O=CarService, just a label, not google's chain
        byte[] name = new X500Principal("CN=CATBACK,OU=PetalOS,O=CarService").getEncoded();
        byte[] validity = der(0x30, join(der(0x17, "200101000000Z".getBytes(StandardCharsets.US_ASCII)),
                der(0x17, "400101000000Z".getBytes(StandardCharsets.US_ASCII))));
        byte[] serial = new BigInteger(127, new SecureRandom()).add(BigInteger.ONE).toByteArray();
        byte[] tbs = der(0x30, join(der(0xa0, der(2, new byte[]{2})), der(2, serial), algorithm,
                name, validity, name, keys.getPublic().getEncoded()));
        Signature signer = Signature.getInstance("SHA256withRSA"); signer.initSign(keys.getPrivate()); signer.update(tbs);
        byte[] certificate = der(0x30, join(tbs, algorithm, der(3, join(new byte[]{0}, signer.sign()))));
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(certificate));
        cert.verify(keys.getPublic()); cert.checkValidity();
        KeyStore store = KeyStore.getInstance("PKCS12"); store.load(null, password);
        store.setKeyEntry("petal-auto", keys.getPrivate(), password, new java.security.cert.Certificate[]{cert});
        return store;
    }
    private static byte[] der(int tag, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); out.write(tag);
        if (value.length < 128) out.write(value.length);
        else if (value.length < 256) { out.write(0x81); out.write(value.length); }
        else { out.write(0x82); out.write(value.length >>> 8); out.write(value.length); }
        out.write(value, 0, value.length); return out.toByteArray();
    }
    private static byte[] join(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.write(p, 0, p.length);
        return out.toByteArray();
    }
}
