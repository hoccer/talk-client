package com.hoccer.talk.client;

import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;
import org.apache.http.params.HttpParams;
import org.apache.log4j.Logger;

import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.util.Enumeration;

/**
 * An HTTP client using a globally configured keystore with a certificate chain. Use static methods to configure the
 * keystore.
 * 
 * @author Arne Handt, it@handtwerk.de
 */
public class HttpClientWithKeystore extends DefaultHttpClient {

    private static final Logger LOG = Logger.getLogger(HttpClientWithKeystore.class);

    /**
     * Global URL scheme registry
     *
     * This will be initialized in initializeSsl() and will be
     * set up with a socket factory that enforces our policy.
     */
    private static SchemeRegistry sRegistry;

    /**
     * Global initializer
     *
     * This must be called with an appropriate KeyStore
     * before attempting to use the Talk client.
     *
     * @param pTrustStore to use
     * @throws GeneralSecurityException
     */
    public static synchronized void initializeSsl(KeyStore pTrustStore) throws GeneralSecurityException {
        LOG.debug("initializeSsl()");

        LOG.trace("aliases:");
        Enumeration<String> aliases = pTrustStore.aliases();
        while (aliases.hasMoreElements()) {
            LOG.trace(" - " + aliases.nextElement());
        }

        LOG.debug("creating socket factory");
        SSLSocketFactory socketFactory = new SocketFactory(pTrustStore);
        socketFactory.setHostnameVerifier(SSLSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);

        LOG.debug("creating scheme registry");
        SchemeRegistry registry = new SchemeRegistry();
        registry.register(new Scheme("http", PlainSocketFactory.getSocketFactory(), 80));
        registry.register(new Scheme("https", socketFactory, 443));
        registry.register(new Scheme("http", socketFactory, 443)); // appears to be necessary...whatever...
        sRegistry = registry;
    }

    /**
     * @return the scheme registry, if initialized
     */
    public static SchemeRegistry getSchemeRegistry() {
        return sRegistry;
    }
    

    public HttpClientWithKeystore() {
        super();
    }

    public HttpClientWithKeystore(ClientConnectionManager pConman, HttpParams pParams) {
        super(pConman, pParams);
    }

    public HttpClientWithKeystore(HttpParams pParams) {
        super(pParams);
    }

    @Override
    protected ClientConnectionManager createClientConnectionManager() {
        LOG.debug("createClientConnectionManager()");

        if (sRegistry == null) {
            LOG.warn("using default connection manager");
            // no certificate - revert to default implementation
            return super.createClientConnectionManager();
        }

        LOG.info("using trusted connection manager");
        return new ThreadSafeClientConnManager(getParams(), sRegistry);
    }

    private static class SocketFactory extends SSLSocketFactory {
        public SocketFactory(KeyStore truststore)
                throws NoSuchAlgorithmException, KeyManagementException,
                        KeyStoreException, UnrecoverableKeyException
        {
            super(truststore);
        }

        @Override
        public Socket createSocket() throws IOException {
            LOG.debug("createSocket()");
            SSLSocket s = (SSLSocket)super.createSocket();
            s.setEnabledCipherSuites(XoClientConfiguration.TLS_CIPHERS);
            s.setEnabledProtocols(XoClientConfiguration.TLS_PROTOCOLS);
            return s;
        }
    }

}
