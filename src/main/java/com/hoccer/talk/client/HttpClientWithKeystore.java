package com.hoccer.talk.client;

import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Enumeration;

import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;
import org.apache.http.params.HttpParams;
import org.apache.log4j.Logger;

/**
 * An HTTP client using a globally configured keystore with a certificate chain. Use static methods to configure the
 * keystore.
 * 
 * @author Arne Handt, it@handtwerk.de
 */
public class HttpClientWithKeystore extends DefaultHttpClient {

    // Constants ---------------------------------------------------------

    private static final Logger LOG = Logger.getLogger(HttpClientWithKeystore.class);

    // Static Variables --------------------------------------------------

    private static SchemeRegistry sRegistry;

    // Static Methods ----------------------------------------------------

    /* currently not used */
    public static synchronized void initializeSsl(KeyStore pTrustStore) throws GeneralSecurityException {
        LOG.debug("initializeSsl()");

        LOG.trace("aliases:");
        Enumeration<String> aliases = pTrustStore.aliases();
        while (aliases.hasMoreElements()) {
            LOG.trace(" - " + aliases.nextElement());
        }

        LOG.debug("creating socket factory");
        SSLSocketFactory socketFactory = new SSLSocketFactory(pTrustStore);
        socketFactory.setHostnameVerifier(SSLSocketFactory.ALLOW_ALL_HOSTNAME_VERIFIER);

        LOG.debug("creating scheme registry");
        sRegistry = new SchemeRegistry();
        sRegistry.register(new Scheme("http", PlainSocketFactory.getSocketFactory(), 80));
        sRegistry.register(new Scheme("https", socketFactory, 443));
        sRegistry.register(new Scheme("http", socketFactory, 443)); // appears to be necessary...whatever...
    }

    public static SchemeRegistry getSchemeRegistry() {

        return sRegistry;
    }

    // Constructors ------------------------------------------------------

    public HttpClientWithKeystore() {

        super();
    }

    public HttpClientWithKeystore(ClientConnectionManager pConman, HttpParams pParams) {

        super(pConman, pParams);
    }

    public HttpClientWithKeystore(HttpParams pParams) {

        super(pParams);
    }

    // Protected Instance Methods ----------------------------------------

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


}
