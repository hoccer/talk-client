package com.hoccer.talk.client;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Vector;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import better.jsonrpc.client.JsonRpcClient;
import better.jsonrpc.core.JsonRpcConnection;
import better.jsonrpc.server.JsonRpcServer;
import better.jsonrpc.util.ProxyUtil;

import better.jsonrpc.websocket.JsonRpcWsClient;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hoccer.talk.logging.HoccerLoggers;
import com.hoccer.talk.model.TalkDelivery;
import com.hoccer.talk.model.TalkMessage;
import com.hoccer.talk.model.TalkPresence;
import com.hoccer.talk.model.TalkToken;
import com.hoccer.talk.model.TalkRelationship;
import com.hoccer.talk.rpc.ITalkRpcClient;
import com.hoccer.talk.rpc.ITalkRpcServer;
import org.eclipse.jetty.websocket.WebSocketClientFactory;

public class HoccerTalkClient implements JsonRpcConnection.Listener {

    private static final Logger LOG = HoccerLoggers.getLogger(HoccerTalkClient.class);

    /** State in which the client does not attempt any communication */
    public static final int STATE_INACTIVE = 0;
    /** State in which the client is ready to connect if awakened */
    public static final int STATE_IDLE = 1;
    /** State while establishing connection */
    public static final int STATE_CONNECTING = 2;
    /** State after connection is established (login/registration) */
    public static final int STATE_CONNECTED = 3;
    /** State while there is an active connection */
    public static final int STATE_ACTIVE = 4;

    /** Names of our states for debugging */
    private static final String[] STATE_NAMES = {
            "inactive", "idle", "connecting", "connected", "active"
    };

    /** Return the name of the given state */
    public static final String stateToString(int state) {
        if(state >= 0 && state < STATE_NAMES.length) {
            return STATE_NAMES[state];
        } else {
            return "INVALID(" + state + ")";
        }
    }


    WebSocketClientFactory mClientFactory;

	JsonRpcWsClient mConnection;

    ITalkClientDatabase mDatabase;
	
	TalkRpcClientImpl mHandler;
	
	ITalkRpcServer mServerRpc;

    ScheduledExecutorService mExecutor;

    ScheduledFuture<?> mLoginFuture;
    ScheduledFuture<?> mConnectFuture;
    ScheduledFuture<?> mDisconnectFuture;
    ScheduledFuture<?> mAutoDisconnectFuture;

    Vector<ITalkClientListener> mListeners = new Vector<ITalkClientListener>();

    int mState = STATE_INACTIVE;

    int mConnectionFailures = 0;

    private String[] mAllClients = new String[0];

    /**
     * Create a Hoccer Talk client using the given client database
     * @param database
     */
	public HoccerTalkClient(ScheduledExecutorService backgroundExecutor, ITalkClientDatabase database) {
        // remember client database and background executor
        mExecutor = backgroundExecutor;
        mDatabase = database;

        // create URI object referencing the server
        URI uri = null;
        try {
            uri = new URI(TalkClientConfiguration.SERVER_URI);
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
                uri);

        // create client-side RPC handler object
        mHandler = new TalkRpcClientImpl();

        // create common object mapper
        ObjectMapper mapper = createObjectMapper();

        // configure JSON-RPC client
        JsonRpcClient clt = new JsonRpcClient(mapper);
        mConnection.bindClient(clt);

        // configure JSON-RPC server object
        JsonRpcServer srv = new JsonRpcServer(mapper, ITalkRpcClient.class);
        mConnection.bindServer(srv, getHandler());

        // listen for connection state changes
        mConnection.addListener(this);

        // create RPC proxy
		mServerRpc = ProxyUtil.createClientProxy(
				ITalkRpcServer.class.getClassLoader(),
				ITalkRpcServer.class,
				mConnection);
	}


    /**
     * Get the RPC interface to the server
     * @return
     */
    public ITalkRpcServer getServerRpc() {
        return mServerRpc;
    }

    /**
     * Get the handler object implementing the client RPC interface
     * @return
     */
    public ITalkRpcClient getHandler() {
        return mHandler;
    }

    public int getState() {
        return mState;
    }

    public String getStateString() {
        return stateToString(mState);
    }

    public void registerListener(ITalkClientListener listener) {
        mListeners.add(listener);
    }

    public void unregisterListener(ITalkClientListener listener) {
        mListeners.remove(listener);
    }

    public boolean isActivated() {
        return mState > STATE_INACTIVE;
    }

    public void activate() {
        LOG.info("client: activate()");
        if(mState == STATE_INACTIVE) {
            switchState(STATE_IDLE, "client activated");
        }
    }

    public void deactivate() {
        LOG.info("client: deactivate()");
        if(mState != STATE_INACTIVE) {
            switchState(STATE_INACTIVE, "client deactivated");
        }
    }

    public boolean isAwake() {
        return mState > STATE_IDLE;
    }

    public void wake() {
        LOG.info("client: wake()");
        switch(mState) {
        case STATE_INACTIVE:
            break;
        case STATE_IDLE:
            switchState(STATE_CONNECTING, "client woken");
            break;
        default:
            scheduleAutomaticDisconnect();
            break;
        }
    }

    public void deactivateNow() {
        LOG.info("client: deactivateNow()");
        doDisconnect();
        mState = STATE_INACTIVE;
    }

    public void registerGcm(final String packageName, final String registrationId) {
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                mServerRpc.registerGcm(packageName, registrationId);
            }
        });
    }

    public void unregisterGcm() {
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                mServerRpc.unregisterGcm();
            }
        });
    }

    private ObjectMapper createObjectMapper() {
        ObjectMapper result = new ObjectMapper();
        result.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        return result;
    }

    private void switchState(int newState, String message) {
        // only switch of there really was a change
        if(mState == newState) {
            LOG.info("state remains " + STATE_NAMES[mState] + " (" + message + ")");
            return;
        }

        // log about it
        LOG.info("state " + STATE_NAMES[mState] + " -> " + STATE_NAMES[newState] + " (" + message + ")");

        // perform transition
        mState = newState;

        if(mState == STATE_IDLE || mState == STATE_INACTIVE) {
            // perform immediate disconnect
            scheduleDisconnect();
        }
        if(mState == STATE_CONNECTING) {
            // reset failure counter for backoff
            mConnectionFailures = 0;
            // schedule immediate connection attempt
            scheduleConnect(false);
        }
        if(mState == STATE_CONNECTED) {
            // proceed with login
            scheduleLogin();
        }
        if(mState == STATE_ACTIVE) {
            mConnectionFailures = 0;
            // start talking
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    LOG.info("ready for action");
                }
            });
        }

        // call listeners
        for(ITalkClientListener listener: mListeners) {
            listener.onClientStateChange(this, newState);
        }
    }

    private void handleDisconnect() {
        switch(mState) {
            case STATE_INACTIVE:
            case STATE_IDLE:
                // we are supposed to be disconnected, things are fine
                break;
            case STATE_CONNECTING:
            case STATE_CONNECTED:
            case STATE_ACTIVE:
                // disconnected while be should be connected - try to reconnect
                scheduleConnect(true);
                mConnectionFailures++;
                break;
        }
    }

    /**
     * Called when the connection is opened
     * @param connection
     */
	@Override
	public void onOpen(JsonRpcConnection connection) {
        LOG.info("onOpen()");
        scheduleAutomaticDisconnect();
        switchState(STATE_CONNECTED, "connection opened");
	}

    /**
     * Called when the connection is closed
     * @param connection
     */
	@Override
	public void onClose(JsonRpcConnection connection) {
        LOG.info("onClose()");
        shutdownAutomaticDisconnect();
        handleDisconnect();
	}

    private void doConnect() {
        LOG.info("performing connect");
        try {
            mConnection.connect(TalkClientConfiguration.CONNECT_TIMEOUT, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.info("exception while connecting: " + e.toString());
            scheduleConnect(true);
        }
    }

    private void doDisconnect() {
        LOG.info("performing disconnect");
        mConnection.disconnect();
    }

    private void shutdownAutomaticDisconnect() {
        if(mAutoDisconnectFuture != null) {
            mAutoDisconnectFuture.cancel(false);
            mAutoDisconnectFuture = null;
        }
    }

    private void scheduleAutomaticDisconnect() {
        LOG.info("scheduleAutomaticDisconnect()");
        shutdownAutomaticDisconnect();
        mAutoDisconnectFuture = mExecutor.schedule(new Runnable() {
            @Override
            public void run() {
                switchState(STATE_IDLE, "activity timeout");
                mAutoDisconnectFuture = null;
            }
        }, TalkClientConfiguration.IDLE_TIMEOUT, TimeUnit.SECONDS);
    }

    private void scheduleConnect(boolean isReconnect) {
        LOG.finer("scheduleConnect()");

        int backoffDelay = 0;

        if(isReconnect) {
            // compute the backoff factor
            int variableFactor = 1 << mConnectionFailures;

            // compute variable backoff component
            double variableTime =
                Math.random() * Math.min(
                    TalkClientConfiguration.RECONNECT_BACKOFF_VARIABLE_MAXIMUM,
                    variableFactor * TalkClientConfiguration.RECONNECT_BACKOFF_VARIABLE_FACTOR);

            // compute total backoff
            double totalTime = TalkClientConfiguration.RECONNECT_BACKOFF_FIXED_DELAY + variableTime;

            // convert to msecs
            backoffDelay = (int) Math.round(1000.0 * totalTime);

            LOG.info("connection attempt backed off by " + totalTime + " seconds");
        }

        // schedule the attempt
        mConnectFuture = mExecutor.schedule(new Runnable() {
            @Override
            public void run() {
                doConnect();
                mConnectFuture = null;
            }
        }, backoffDelay, TimeUnit.MILLISECONDS);
    }

    private void scheduleLogin() {
        LOG.finer("scheduleLogin()");
        mLoginFuture = mExecutor.schedule(new Runnable() {
            @Override
            public void run() {
                mServerRpc.identify(mDatabase.getClient().getClientId());
                switchState(STATE_ACTIVE, "login successful");
                mLoginFuture = null;
            }
        }, 0, TimeUnit.SECONDS);
    }

    private void scheduleDisconnect() {
        LOG.finer("scheduleDisconnect()");
        mDisconnectFuture = mExecutor.schedule(new Runnable() {
            @Override
            public void run() {
                doDisconnect();
                mDisconnectFuture = null;
            }
        }, 0, TimeUnit.SECONDS);
    }


    private void performDelivery(String messageTag) {
        LOG.info("performDelivery(" + messageTag + ")");
        TalkMessage m = null;
        TalkDelivery[] d = new TalkDelivery[mAllClients.length];
        try {
            m = mDatabase.getMessageByTag(messageTag);
            //d = mDatabase.getDeliveriesByTag(messageTag);
        } catch (Exception e) {
            // XXX fail horribly
            e.printStackTrace();
            LOG.info("fetch failed");
            return;
        }
        for(int i = 0; i < d.length; i++) {
            TalkDelivery id = new TalkDelivery();
            id.setMessageId(m.getMessageId());
            id.setReceiverId(mAllClients[i]);
            d[i] = id;
        }
        LOG.info("requesting delivery");
        mServerRpc.deliveryRequest(m, d);
    }

    public void tryToDeliver(final String messageTag) {
        LOG.info("tryToDeliver(" + messageTag + ")");
        wake();
        if(mState == STATE_ACTIVE) {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    performDelivery(messageTag);
                }
            });
        }
    }

    /**
     * Client-side RPC implementation
     */
	public class TalkRpcClientImpl implements ITalkRpcClient {

        @Override
        public void pushNotRegistered() {
            LOG.info("server: pushNotRegistered()");
        }

        @Override
		public void incomingDelivery(TalkDelivery d, TalkMessage m) {
			LOG.info("server: incomingDelivery()");
		}

		@Override
		public void outgoingDelivery(TalkDelivery d) {
			LOG.info("server: outgoingDelivery()");
		}

        @Override
        public void presenceUpdated(TalkPresence presence) {
            LOG.info("presenceUpdated(" + presence.getClientId() + ")");
        }

        @Override
        public void relationshipUpdated(TalkRelationship relationship) {
            LOG.info("relationshipUpdated(" + relationship.getOtherClientId() + ")");
        }
    }
	
}
