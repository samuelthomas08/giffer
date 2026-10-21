package giffer;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * Makes HTTPS trust the Windows certificate store in addition to Java's own list of certificate authorities.
 *
 * <p>Behind a company proxy that re-signs HTTPS traffic, the proxy's certificate is installed in Windows (so the
 * browser works) but not in Java (so a bundled Java runtime fails with "PKIX path building failed"). Trusting the
 * union of both stores fixes that without weakening anything: it is exactly what the browser trusts.
 */
final class TlsTrust {
    private TlsTrust() {}

    /** Safe to call on any system: if the Windows store is not available nothing changes. */
    static void trustWindowsCertificateStoreToo() {
        try {
            X509TrustManager javaDefault = trustManagerFor(null);
            X509TrustManager windows = windowsTrustManager();
            if (windows == null) return;

            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{new TrustEither(List.of(javaDefault, windows))}, null);
            HttpsURLConnection.setDefaultSSLSocketFactory(context.getSocketFactory());
        } catch (Exception cannotConfigure) {
            // keep Java's default behaviour
        }
    }

    private static X509TrustManager windowsTrustManager() {
        try {
            KeyStore windowsRoots = KeyStore.getInstance("Windows-ROOT");
            windowsRoots.load(null, null);
            return trustManagerFor(windowsRoots);
        } catch (Exception windowsStoreUnavailable) {
            return null; // not Windows, or the runtime was built without jdk.crypto.mscapi
        }
    }

    /** The X.509 trust manager for the given key store; {@code null} means Java's default (cacerts). */
    private static X509TrustManager trustManagerFor(KeyStore keyStore) throws Exception {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(keyStore);
        for (TrustManager trustManager : factory.getTrustManagers()) {
            if (trustManager instanceof X509TrustManager x509TrustManager) return x509TrustManager;
        }
        throw new IllegalStateException("no X509TrustManager");
    }

    /** Accepts a certificate chain if at least one of the wrapped trust managers accepts it. */
    private static final class TrustEither implements X509TrustManager {
        private final List<X509TrustManager> trustManagers;

        TrustEither(List<X509TrustManager> trustManagers) {
            this.trustManagers = trustManagers;
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            check(chain, authType, true);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            check(chain, authType, false);
        }

        private void check(X509Certificate[] chain, String authType, boolean client) throws CertificateException {
            CertificateException lastFailure = null;
            for (X509TrustManager trustManager : trustManagers) {
                try {
                    if (client) trustManager.checkClientTrusted(chain, authType);
                    else trustManager.checkServerTrusted(chain, authType);
                    return;
                } catch (CertificateException rejected) {
                    lastFailure = rejected;
                }
            }
            throw lastFailure;
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            List<X509Certificate> issuers = new ArrayList<>();
            for (X509TrustManager trustManager : trustManagers) issuers.addAll(List.of(trustManager.getAcceptedIssuers()));
            return issuers.toArray(new X509Certificate[0]);
        }
    }
}
