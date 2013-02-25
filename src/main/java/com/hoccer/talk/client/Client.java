package com.hoccer.talk.client;

import org.apache.log4j.Logger;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hoccer.talk.model.TalkDelivery;
import com.hoccer.talk.model.TalkMessage;
import com.hoccer.talk.rpc.TalkRpcClient;
import com.hoccer.talk.rpc.TalkRpcServer;

import better.jsonrpc.annotations.JsonRpcNotification;
import better.jsonrpc.core.JsonRpcConnection;
import better.jsonrpc.util.ProxyUtil;
import better.jsonrpc.websocket.JsonRpcWsClient;

public class Client implements JsonRpcConnection.Listener {

	private static final Logger log = Logger.getLogger(Client.class);
	
	JsonRpcConnection mConnection;
	
	TalkRpcClientImpl mHandler;
	
	TalkRpcServer mServerRpc;
	
	Client(JsonRpcConnection connection) {
		mConnection = connection;
		mHandler = new TalkRpcClientImpl();
		mServerRpc = ProxyUtil.createClientProxy(
				this.getClass().getClassLoader(),
				TalkRpcServer.class,
				mConnection);
	}
	
	public TalkRpcClient getHandler() {
		return mHandler;
	}
	
	@Override
	public void onOpen(JsonRpcConnection connection) {
		log.info("connection opened");
	}

	@Override
	public void onClose(JsonRpcConnection connection) {
		log.info("connection closed");
	}
	
	@Override
	public void onMessageSent(JsonRpcConnection connection, ObjectNode message) {
	}

	@Override
	public void onMessageReceived(JsonRpcConnection connection, ObjectNode message) {
	}
	
	public class TalkRpcClientImpl implements TalkRpcClient {

		@Override
		public void incomingDelivery(TalkDelivery d, TalkMessage m) {
			
		}

		@Override
		public void outgoingDelivery(TalkDelivery d) {
			
		}
		
	}
	
}
