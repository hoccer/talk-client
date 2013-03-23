package com.hoccer.talk.client;

public class TalkClientConfiguration {

    /** Connection timeout (seconds) */
    public static final int CONNECT_TIMEOUT = 10;

    /** Idle timeout for server connection (seconds) */
    public static final int IDLE_TIMEOUT = 10;

    /** Fixed reconnect backoff delay (secs) */
    public static final double RECONNECT_BACKOFF_FIXED_DELAY = 5.0;
    /** Variable reconnect backoff delay - time factor (seconds) */
    public static final double RECONNECT_BACKOFF_VARIABLE_FACTOR = 1.0;
    /** Variable reconnect backoff delay - time maximum (seconds) */
    public static final double RECONNECT_BACKOFF_VARIABLE_MAXIMUM = 60.0;

}
