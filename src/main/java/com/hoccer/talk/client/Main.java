package com.hoccer.talk.client;

import java.net.URI;
import java.util.UUID;
import java.util.logging.Logger;

import com.hoccer.talk.model.TalkClient;
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
    }

	public static void main(String[] args) {
		try {
			HoccerTalkClient c = new HoccerTalkClient(new DummyDatabase());

            Thread.sleep(120000);

			System.exit(0);
			
		} catch (Exception e) {
			e.printStackTrace();
		}

	}

}
