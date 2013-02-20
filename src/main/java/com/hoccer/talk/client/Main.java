package com.hoccer.talk.client;

import java.net.URI;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.websocket.WebSocket;
import org.eclipse.jetty.websocket.WebSocketClient;
import org.eclipse.jetty.websocket.WebSocketClientFactory;

public class Main {

	public static void main(String[] args) {
		try {
			WebSocketClientFactory f = new WebSocketClientFactory();
			f.start();
			
			for(int i = 0; i < 1000; i++) {
				Socket socket = new Socket();
				WebSocketClient c = f.newWebSocketClient();
				c.open(new URI("ws://localhost:8080/"), socket);
			}
			
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

}
