package com.hoccer.talk.client;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

import better.jsonrpc.core.JsonRpcConnection;
import better.jsonrpc.server.JsonRpcServer;
import better.jsonrpc.util.ProxyUtil;

import better.jsonrpc.websocket.JsonRpcWsClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hoccer.talk.logging.HoccerLoggers;
import com.hoccer.talk.model.TalkDelivery;
import com.hoccer.talk.model.TalkMessage;
import com.hoccer.talk.rpc.TalkRpcClient;
import com.hoccer.talk.rpc.TalkRpcServer;
import org.eclipse.jetty.websocket.WebSocketClientFactory;

public class HoccerTalkClient implements JsonRpcConnection.Listener {

	private static final Logger LOG = HoccerLoggers.getLogger(HoccerTalkClient.class);

    WebSocketClientFactory mClientFactory;

	JsonRpcWsClient mConnection;

    HoccerTalkDatabase mDatabase;
	
	TalkRpcClientImpl mHandler;
	
	TalkRpcServer mServerRpc;

    Executor mExecutor;

    /**
     * Create a Hoccer Talk client using the given client database
     * @param database
     */
	public HoccerTalkClient(HoccerTalkDatabase database) {
        // remember client database
        mDatabase = database;

        // create background executor
        mExecutor = Executors.newSingleThreadScheduledExecutor();

        // create URI object referencing the server
        URI uri = null;
        try {
            uri = new URI("ws://192.168.2.41:8080/");
        } catch (URISyntaxException e) {
            // won't happen
        }

        // create superfluous client factory
        mClientFactory = new WebSocketClientFactory();
        try {
            mClientFactory.start();
        } catch (Exception e) {
            e.printStackTrace();
        }

        // create JSON-RPC client
        mConnection = new JsonRpcWsClient(
                mClientFactory,
                new ObjectMapper(),
                uri);

        // create client-side RPC handler object
        mHandler = new TalkRpcClientImpl();

        // create JSON-RPC server object
        JsonRpcServer srv = new JsonRpcServer(TalkRpcClient.class);
        mConnection.setHandler(getHandler());
        mConnection.addListener(this);
        mConnection.setServer(srv);

        // create RPC proxy
		mServerRpc = ProxyUtil.createClientProxy(
				TalkRpcServer.class.getClassLoader(),
				TalkRpcServer.class,
				mConnection);

        // XXX this should really be done by the class user
        tryToConnect();
	}

    /**
     * Get the RPC interface to the server
     * @return
     */
	public TalkRpcServer getServerRpc() {
		return mServerRpc;
	}

    /**
     * Get the handler object implementing the client RPC interface
     * @return
     */
	public TalkRpcClient getHandler() {
		return mHandler;
	}

    /**
     * Called when the connection is opened
     * @param connection
     */
	@Override
	public void onOpen(JsonRpcConnection connection) {
		LOG.info("connection opened");
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                mServerRpc.identify(mDatabase.getClient().getClientId());
            }
        });
	}

    /**
     * Called when the connection is closed
     * @param connection
     */
	@Override
	public void onClose(JsonRpcConnection connection) {
		LOG.info("connection closed");
	}

    /**
     *
     */
    private void tryToConnect() {
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                mConnection.connect();
            }
        });
    }

    /**
     * Client-side RPC implementation
     */
	public class TalkRpcClientImpl implements TalkRpcClient {

		@Override
		public void incomingDelivery(TalkDelivery d, TalkMessage m) {
			LOG.info("call incomingDelivery()");
		}

		@Override
		public void outgoingDelivery(TalkDelivery d) {
			LOG.info("call outgoingDelivery()");
		}
		
	}
	
}
