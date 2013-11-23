package com.hoccer.talk.client;

import org.eclipse.jetty.websocket.WebSocketClientFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Interface implemented by programs that host a XoClient
 */
public interface IXoClientHost {

    public ScheduledExecutorService getBackgroundExecutor();
    public IXoClientDatabaseBackend getDatabaseBackend();
    public WebSocketClientFactory   getWebSocketFactory();

    public InputStream openInputStreamForUrl(String url) throws IOException;

}