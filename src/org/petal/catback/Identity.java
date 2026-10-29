package org.petal.catback;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Date;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import org.petal.catback.protocol.IdentityImport;
import org.petal.catback.protocol.LocalIdentity;

// car identity: the one pulled from the user's android auto apk, else our own self-signed junk real cars bin off
public final class Identity {
    public static final String GEARHEAD = "com.google.android.projection.gearhead";
    public static volatile String status = "Identity not loaded";
    private static final char[] LOCAL_PASSWORD = "petal-auto-local".toCharArray();
    private static final String CAR_FILE = "car-identity.p12", LOCAL_FILE = "projection-identity-v2.p12";
    private static volatile Date carExpiry;
    private static volatile boolean expiryRead;

    public static SSLEngine engine(Context app) throws Exception { return context(app).createSSLEngine(); }

    // null = nothing imported yet, refresh loop hammers this so cache it
    public static Date carExpiry(Context app) {
        if (!expiryRead) {
            Date found = null;
            AtomicFile file = carFile(app);
            if (file.getBaseFile().exists()) try (FileInputStream in = file.openRead()) {
                KeyStore store = KeyStore.getInstance("PKCS12"); store.load(in, LOCAL_PASSWORD);
                found = ((X509Certificate) store.getCertificate(store.aliases().nextElement())).getNotAfter();
            } catch (Exception broken) { }
            carExpiry = found; expiryRead = true;
        }
        return carExpiry;
    }
    public static boolean carReady(Context app) {
        Date expiry = carExpiry(app);
        return expiry != null && expiry.after(new Date());
    }

    // heavy, keep it off the ui thread
    public static Date importFrom(Context app, InputStream apk) throws Exception {
        IdentityImport.Result found = IdentityImport.fromApk(apk);
        found.leaf.checkValidity();
        AtomicFile file = carFile(app);
        FileOutputStream out = file.startWrite();
        try { found.keyStore(LOCAL_PASSWORD).store(out, LOCAL_PASSWORD); file.finishWrite(out); }
        catch (Exception e) { file.failWrite(out); throw e; }
        carExpiry = found.leaf.getNotAfter(); expiryRead = true;
        return carExpiry;
    }
    private static AtomicFile carFile(Context app) { return new AtomicFile(new File(app.getFilesDir(), CAR_FILE)); }

    // same identity does AAP and the WPP 4.5+ tls control link
    public static SSLContext context(Context app) throws Exception {
        AtomicFile car = carFile(app), local = new AtomicFile(new File(app.getFilesDir(), LOCAL_FILE));
        KeyStore store = KeyStore.getInstance("PKCS12");
        InputStream devAsset = null;
        try { devAsset = app.getAssets().open("projection-identity.p12"); }
        catch (java.io.FileNotFoundException absent) { }
        if (car.getBaseFile().exists()) {
            try (FileInputStream in = car.openRead()) { store.load(in, LOCAL_PASSWORD); }
            if (devAsset != null) devAsset.close();
            status = "Car identity imported from Android Auto";
        } else if (devAsset != null) {
            // gradle -PprojectionIdentity dev builds only, the rom never has this
            try (InputStream in = devAsset) { store.load(in, LOCAL_PASSWORD); }
            status = "Bundled dev compatibility identity";
        } else if (local.getBaseFile().exists()) {
            try (FileInputStream in = local.openRead()) { store.load(in, LOCAL_PASSWORD); }
            status = "Self-signed identity, stock cars will reject it. Import Android Auto first";
        } else {
            store = LocalIdentity.create(LOCAL_PASSWORD);
            FileOutputStream out = local.startWrite();
            try { store.store(out, LOCAL_PASSWORD); local.finishWrite(out); }
            catch (Exception e) { local.failWrite(out); throw e; }
            status = "Self-signed identity, stock cars will reject it. Import Android Auto first";
        }
        String alias = store.aliases().nextElement();
        X509Certificate leaf = (X509Certificate) store.getCertificate(alias);
        leaf.checkValidity();
        status += "; certificate expires " + leaf.getNotAfter();
        KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); km.init(store, LOCAL_PASSWORD);
        SSLContext context = SSLContext.getInstance("TLSv1.2");
        // self-signed has no shared CA so just sanity check whatever the receiver sends
        X509TrustManager receiverTrust = new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] chain, String type) throws java.security.cert.CertificateException {
                if (chain == null || chain.length == 0) throw new java.security.cert.CertificateException("Receiver presented no certificate");
                for (X509Certificate certificate : chain) certificate.checkValidity();
            }
            public void checkServerTrusted(X509Certificate[] chain, String type) throws java.security.cert.CertificateException {
                throw new java.security.cert.CertificateException("Phone must be TLS server");
            }
        };
        TrustManager[] trust = new TrustManager[]{receiverTrust};
        java.security.cert.Certificate[] chain = store.getCertificateChain(alias);
        if (chain != null && chain.length > 1) {
            KeyStore anchors = KeyStore.getInstance(KeyStore.getDefaultType()); anchors.load(null, null);
            anchors.setCertificateEntry("projection-root", chain[chain.length - 1]);
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(anchors); trust = factory.getTrustManagers();
            status += "; standard receiver certificate-chain validation";
        }
        context.init(km.getKeyManagers(), trust, null);
        return context;
    }
}
