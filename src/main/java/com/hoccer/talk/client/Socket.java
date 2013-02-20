package com.hoccer.talk.client;

import org.apache.log4j.Logger;
import org.eclipse.jetty.websocket.WebSocket;

public class Socket implements WebSocket.OnTextMessage {
	
	private static final Logger log = Logger.getLogger(Socket.class); 
	
	public Connection connection;
	
	@Override
	public void onOpen(Connection connection) {
		log.info("connection open");
		this.connection = connection;
	}

	@Override
	public void onClose(int closeCode, String message) {
		log.info("connection close");
	}

	@Override
	public void onMessage(String data) {
		log.info("connection message " + data);
	}

}
