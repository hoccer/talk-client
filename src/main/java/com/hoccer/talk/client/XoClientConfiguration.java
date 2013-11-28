package com.hoccer.talk.client;

public class XoClientConfiguration {

    public static final boolean USE_BSON_PROTOCOL = true;

    public static final String PROTOCOL_STRING_JSON = "com.hoccer.talk.v1";
    public static final String PROTOCOL_STRING_BSON = "com.hoccer.talk.v1.bson";

    public static final String SERVER_URI = "wss://server.talk.hoccer.de/";

    /** Transfer executor thread count */
    public static final int TRANSFER_THREADS = 2;

    /** Connection timeout (seconds) */
    public static final int CONNECT_TIMEOUT = 15;

    /** Idle timeout for client activity (seconds) */
    public static final int IDLE_TIMEOUT = 600;

    /** Send K-A keepalives */
    public static final boolean KEEPALIVE_ENABLED = true;
    /** Interval to send K-A keepalives at */
    public static final int KEEPALIVE_INTERVAL = 120;

    /** WS connection idle timeout (msecs) */
    public static final int CONNECTION_IDLE_TIMEOUT = 900 * 1000;

    /** WS connection max text message size */
    public static final int CONNECTION_MAX_TEXT_SIZE = 1 << 16;

    /** WS connection max binary message size */
    public static final int CONNECTION_MAX_BINARY_SIZE = 1 << 16;

    /** Fixed reconnect backoff delay (secs) */
    public static final double RECONNECT_BACKOFF_FIXED_DELAY = 3.0;
    /** Variable reconnect backoff delay - time factor (seconds) */
    public static final double RECONNECT_BACKOFF_VARIABLE_FACTOR = 1.0;
    /** Variable reconnect backoff delay - time maximum (seconds) */
    public static final double RECONNECT_BACKOFF_VARIABLE_MAXIMUM = 120.0;

    /** Enable the session cache to improve connection latency */
    public static final boolean TLS_SESSION_CACHE_ENABLED = true;

    /** Limit the session cache to a small size */
    public static final int TLS_SESSION_CACHE_SIZE = 3;

    /** Allow only AES-based cipher suites */
    public static final String TLS_CIPHERS[] = {
        "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA",
        "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA",
        "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA",
        "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA",
        "TLS_DHE_RSA_WITH_AES_128_CBC_SHA",
        "TLS_DHE_RSA_WITH_AES_256_CBC_SHA",
        "TLS_DHE_DSS_WITH_AES_128_CBC_SHA",
        "TLS_RSA_WITH_AES_128_CBC_SHA",
        "TLS_RSA_WITH_AES_256_CBC_SHA",
    };

    /** Allow only TLS 1.1 and up */
    public static final String TLS_PROTOCOLS[] = {
            "TLSv1.2",
            "TLSv1.1",
            "TLSv1"
    };

}
