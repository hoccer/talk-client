package com.hoccer.talk.client;

import java.net.URI;

import org.eclipse.jetty.websocket.WebSocketClientFactory;

import better.jsonrpc.server.JsonRpcServer;
import better.jsonrpc.websocket.JsonRpcWsClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hoccer.talk.rpc.TalkRpcClient;

public class Main {
	
	public static void main(String[] args) {
		try {
			WebSocketClientFactory f = new WebSocketClientFactory();
			f.start();
			
			JsonRpcWsClient connection = new JsonRpcWsClient(
					f, new ObjectMapper(), new URI("ws://localhost:8080/"));
			
			Client c = new Client(connection);

			JsonRpcServer srv = new JsonRpcServer(TalkRpcClient.class);
			connection.setHandler(c.getHandler());
			connection.addListener(c);
			connection.setServer(srv);
			
			connection.connect();
						
			Thread.sleep(5000);
			
			connection.disconnect();
			
			System.exit(0);
			
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

}
