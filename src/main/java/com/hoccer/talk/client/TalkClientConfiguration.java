package com.hoccer.talk.client;

public class TalkClientConfiguration {

    public static final String PROTOCOL_STRING = "com.hoccer.talk.v1";

    public static final String SERVER_URI = "ws://192.168.2.30:8080/";

    /** Connection timeout (seconds) */
    public static final int CONNECT_TIMEOUT = 10;

    /** Idle timeout for server connection (seconds) */
    public static final int IDLE_TIMEOUT = 60;

    /** WS connection idle timeout (msecs) */
    public static final int CONNECTION_IDLE_TIMEOUT = 1800 * 1000;

    /** WS connection max text message size */
    public static final int CONNECTION_MAX_TEXT_SIZE = 2 ^ 16;

    /** WS connection max binary message size */
    public static final int CONNECTION_MAX_BINARY_SIZE = 2 ^ 16;

    /** Fixed reconnect backoff delay (secs) */
    public static final double RECONNECT_BACKOFF_FIXED_DELAY = 3.0;
    /** Variable reconnect backoff delay - time factor (seconds) */
    public static final double RECONNECT_BACKOFF_VARIABLE_FACTOR = 1.0;
    /** Variable reconnect backoff delay - time maximum (seconds) */
    public static final double RECONNECT_BACKOFF_VARIABLE_MAXIMUM = 60.0;

}
