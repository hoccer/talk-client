package com.hoccer.talk.client;

import java.util.logging.Logger;

import better.jsonrpc.core.JsonRpcConnection;
import better.jsonrpc.util.ProxyUtil;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hoccer.talk.logging.HoccerLoggers;
import com.hoccer.talk.model.TalkDelivery;
import com.hoccer.talk.model.TalkMessage;
import com.hoccer.talk.rpc.TalkRpcClient;
import com.hoccer.talk.rpc.TalkRpcServer;

public class HoccerTalkClient implements JsonRpcConnection.Listener {

	private static final Logger log = HoccerLoggers.getLogger(HoccerTalkClient.class);
	
	JsonRpcConnection mConnection;
	
	TalkRpcClientImpl mHandler;
	
	TalkRpcServer mServerRpc;
	
	HoccerTalkClient(JsonRpcConnection connection) {
		mConnection = connection;
		mHandler = new TalkRpcClientImpl();
		mServerRpc = ProxyUtil.createClientProxy(
				this.getClass().getClassLoader(),
				TalkRpcServer.class,
				mConnection);
	}
	
	public TalkRpcServer getServerRpc() {
		return mServerRpc;
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
		log.info("sent: " + message.toString());
	}

	@Override
	public void onMessageReceived(JsonRpcConnection connection, ObjectNode message) {
		log.info("received: " + message.toString());
	}
	
	public class TalkRpcClientImpl implements TalkRpcClient {

		@Override
		public void incomingDelivery(TalkDelivery d, TalkMessage m) {
			log.info("call incomingDelivery()");
		}

		@Override
		public void outgoingDelivery(TalkDelivery d) {
			log.info("call outgoingDelivery()");
		}
		
	}
	
}
