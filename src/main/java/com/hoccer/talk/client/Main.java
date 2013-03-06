package com.hoccer.talk.client;

import java.net.URI;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.logging.Logger;

import com.hoccer.talk.model.TalkClient;
import com.hoccer.talk.model.TalkDelivery;
import com.hoccer.talk.model.TalkMessage;
import org.eclipse.jetty.websocket.WebSocketClientFactory;

import better.jsonrpc.server.JsonRpcServer;
import better.jsonrpc.websocket.JsonRpcWsClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hoccer.talk.rpc.TalkRpcClient;
import com.hoccer.talk.rpc.TalkRpcServer;

public class Main {
	
	private static final Logger LOG = Logger.getLogger(Main.class.getSimpleName());

    private static class DummyDatabase implements HoccerTalkDatabase {

        TalkClient mOwnClient;

        DummyDatabase() {
            mOwnClient = new TalkClient(UUID.randomUUID().toString());
        }

        @Override
        public TalkClient getClient() {
            return mOwnClient;
        }

        @Override
        public TalkMessage getMessageByTag(String messageTag) throws Exception {
            return null;  //To change body of implemented methods use File | Settings | File Templates.
        }

        @Override
        public TalkDelivery[] getDeliveriesByTag(String messageTag) throws Exception {
            return new TalkDelivery[0];  //To change body of implemented methods use File | Settings | File Templates.
        }
    }

    private static final ScheduledExecutorService EXECUTOR = Executors.newSingleThreadScheduledExecutor();

	public static void main(String[] args) {
		try {
			HoccerTalkClient c = new HoccerTalkClient(EXECUTOR, new DummyDatabase());

            Thread.sleep(120000);

			System.exit(0);
			
		} catch (Exception e) {
			e.printStackTrace();
		}

	}

}
