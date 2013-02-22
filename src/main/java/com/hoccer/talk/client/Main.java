package com.hoccer.talk.client;

import java.net.URI;

import org.apache.log4j.Logger;
import org.eclipse.jetty.websocket.WebSocketClientFactory;

import better.jsonrpc.client.JsonRpcClient;
import better.jsonrpc.server.JsonRpcServer;
import better.jsonrpc.util.ProxyUtil;
import better.jsonrpc.websocket.JsonRpcWsClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hoccer.talk.model.TalkDelivery;
import com.hoccer.talk.model.TalkMessage;
import com.hoccer.talk.rpc.TalkRpcClient;
import com.hoccer.talk.rpc.TalkRpcServer;

public class Main {
	
	private static final Logger log = Logger.getLogger(Main.class);

	public static void main(String[] args) {
		try {
			WebSocketClientFactory f = new WebSocketClientFactory();
			f.start();
			
			JsonRpcWsClient connection = new JsonRpcWsClient(
					f, new ObjectMapper(), new URI("ws://localhost:8080/"));
			JsonRpcServer srv = new JsonRpcServer(TalkRpcClient.class);
			connection.setHandler(new TalkRpcClient() {
				@Override
				public void outgoingDelivery(TalkDelivery d) {
					log.info("outgoing delivery");
				}
				@Override
				public void incomingDelivery(TalkDelivery d, TalkMessage m) {
					log.info("incoming delivery");
				}
			});
			connection.setServer(srv);
			connection.connect();
			
			TalkRpcServer server = (TalkRpcServer)	
					ProxyUtil.createClientProxy(
							Main.class.getClassLoader(),
							TalkRpcServer.class, connection);
			
			Thread.sleep(1000);
			
			for(int i = 0; i < 10; i++) {
				server.deliveryConfirm(new TalkDelivery());
				Thread.sleep(1000);
			}
			
			Thread.sleep(500);
			
			connection.disconnect();
			
			Thread.sleep(500);
			
			System.exit(0);
			
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

}
