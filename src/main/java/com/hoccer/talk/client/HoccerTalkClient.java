package com.hoccer.talk.client;

import java.math.BigInteger;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.util.Vector;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import better.jsonrpc.client.JsonRpcClient;
import better.jsonrpc.core.JsonRpcConnection;
import better.jsonrpc.server.JsonRpcServer;

import better.jsonrpc.websocket.JsonRpcWsClient;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hoccer.talk.model.*;
import com.hoccer.talk.rpc.ITalkRpcClient;
import com.hoccer.talk.rpc.ITalkRpcServer;
import com.hoccer.talk.srp.SRP6Parameters;
import com.hoccer.talk.srp.SRP6VerifyingClient;
import org.apache.log4j.Logger;
import org.bouncycastle.crypto.CryptoException;
import org.bouncycastle.crypto.Digest;
import org.bouncycastle.crypto.agreement.srp.SRP6VerifierGenerator;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.eclipse.jetty.websocket.WebSocketClient;
import org.eclipse.jetty.websocket.WebSocketClientFactory;

public class HoccerTalkClient implements JsonRpcConnection.Listener {

    private static final Logger LOG = Logger.getLogger(HoccerTalkClient.class);

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

    /** Digest instance used for SRP auth */
    private final Digest SRP_DIGEST = new SHA256Digest();
    /** RNG used for SRP auth */
    private static final SecureRandom SRP_RANDOM = new SecureRandom();
    /** Constant SRP parameters */
    private static final SRP6Parameters SRP_PARAMETERS = SRP6Parameters.CONSTANTS_1024;

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

        // create JSON-RPC client
        mConnection = new JsonRpcWsClient(uri, TalkClientConfiguration.PROTOCOL_STRING);
        WebSocketClient wsClient = mConnection.getWebSocketClient();
        wsClient.setMaxIdleTime(TalkClientConfiguration.CONNECTION_IDLE_TIMEOUT);
        //wsClient.setMaxTextMessageSize(TalkClientConfiguration.CONNECTION_MAX_TEXT_SIZE);
        //wsClient.setMaxBinaryMessageSize(TalkClientConfiguration.CONNECTION_MAX_BINARY_SIZE);

        // create client-side RPC handler object
        mHandler = new TalkRpcClientImpl();

        // create common object mapper
        ObjectMapper mapper = createObjectMapper();

        // configure JSON-RPC client
        JsonRpcClient clt = new JsonRpcClient();
        mConnection.bindClient(clt);

        // configure JSON-RPC server object
        JsonRpcServer srv = new JsonRpcServer(ITalkRpcClient.class);
        mConnection.bindServer(srv, getHandler());

        // listen for connection state changes
        mConnection.addListener(this);

        // create RPC proxy
		mServerRpc = (ITalkRpcServer)mConnection.makeProxy(ITalkRpcServer.class);
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

    public boolean isAwake() {
        return mState > STATE_IDLE;
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
        mAutoDisconnectFuture.cancel(true);
        mLoginFuture.cancel(true);
        mConnectFuture.cancel(true);
        mDisconnectFuture.cancel(true);
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
        LOG.info("handleDisconnect()");
        switch(mState) {
            case STATE_INACTIVE:
            case STATE_IDLE:
                LOG.info("supposed to be disconnected");
                // we are supposed to be disconnected, things are fine
                break;
            case STATE_CONNECTING:
            case STATE_CONNECTED:
            case STATE_ACTIVE:
                LOG.info("supposed to be connected - scheduling connect");
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

    private void shutdownConnect() {
        if(mConnectFuture != null) {
            mConnectFuture.cancel(false);
            mConnectFuture = null;
        }
    }

    private void scheduleConnect(boolean isReconnect) {
        LOG.info("scheduleConnect()");
        shutdownConnect();

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

    private void shutdownLogin() {
        if(mLoginFuture != null) {
            mLoginFuture.cancel(false);
            mLoginFuture = null;
        }
    }

    private void scheduleLogin() {
        LOG.info("scheduleLogin()");
        shutdownLogin();
        mLoginFuture = mExecutor.schedule(new Runnable() {
            @Override
            public void run() {
                try {
                TalkClient client = mDatabase.getClient();
                Digest digest = SRP_DIGEST;

                // do we need to register?
                if(client == null) {
                    byte[] salt = new byte[digest.getDigestSize()];
                    byte[] secret = new byte[digest.getDigestSize()];
                    SRP6VerifierGenerator vg = new SRP6VerifierGenerator();

                    vg.init(SRP_PARAMETERS.N, SRP_PARAMETERS.g, digest);

                    SRP_RANDOM.nextBytes(salt);
                    SRP_RANDOM.nextBytes(secret);

                    String saltString = bytesToHex(salt);
                    String secretString = bytesToHex(secret);

                    String clientId = mServerRpc.generateId();

                    BigInteger verifier = vg.generateVerifier(salt, clientId.getBytes(), secret);

                    mServerRpc.srpRegister(verifier.toString(16), bytesToHex(salt));

                    client = new TalkClient(clientId);
                    client.setSrpSalt(saltString);
                    client.setSrpSecret(secretString);

                    mDatabase.saveClient(client);
                }

                String id = client.getClientId();

                SRP6VerifyingClient vc = new SRP6VerifyingClient();
                vc.init(SRP_PARAMETERS.N, SRP_PARAMETERS.g, digest, SRP_RANDOM);

                byte[] loginId = id.getBytes();
                byte[] loginSalt = fromHexString(client.getSrpSalt());
                byte[] loginSecret = fromHexString(client.getSrpSecret());
                BigInteger A = vc.generateClientCredentials(loginSalt, loginId, loginSecret);

                String Bs = mServerRpc.srpPhase1(id,  A.toString(16));
                try {
                    vc.calculateSecret(new BigInteger(Bs, 16));
                } catch (CryptoException e) {
                    e.printStackTrace();
                }

                String Vc = bytesToHex(vc.calculateVerifier());
                String Vs = mServerRpc.srpPhase2(Vc);
                if(!vc.verifyServer(fromHexString(Vs))) {
                    throw new RuntimeException("Could not verify server");
                }

                switchState(STATE_ACTIVE, "login successful");
                } catch (Throwable t) {
                    t.printStackTrace();
                }
                mLoginFuture = null;
            }
        }, 0, TimeUnit.SECONDS);
    }

    private void shutdownDisconnect() {
        if(mDisconnectFuture != null) {
            mDisconnectFuture.cancel(false);
            mDisconnectFuture = null;
        }
    }

    private void scheduleDisconnect() {
        LOG.info("scheduleDisconnect()");
        shutdownDisconnect();
        mDisconnectFuture = mExecutor.schedule(new Runnable() {
            @Override
            public void run() {
                try {
                    doDisconnect();
                } catch (Throwable t) {
                    t.printStackTrace();
                }
                mDisconnectFuture = null;
            }
        }, 0, TimeUnit.SECONDS);
    }


    /**
     * Client-side RPC implementation
     */
	public class TalkRpcClientImpl implements ITalkRpcClient {

        @Override
        public void ping() {
            LOG.info("server: ping()");
        }

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
            LOG.info("server: presenceUpdated(" + presence.getClientId() + ")");
        }

        @Override
        public void relationshipUpdated(TalkRelationship relationship) {
            LOG.info("server: relationshipUpdated(" + relationship.getOtherClientId() + ")");
        }

        @Override
        public void groupUpdated(TalkGroup group) {
            LOG.info("server: groupUpdated(" + group.getGroupId() + ")");
        }

        @Override
        public void groupMemberUpdated(TalkGroupMember member) {
            LOG.info("server: groupMemberUpdated(" + member.getGroupId() + "/" + member.getClientId() + ")");
        }

    }

    /** XXX junk */
    private static String bytesToHex(byte[] bytes) {
        final char[] hexArray = {'0','1','2','3','4','5','6','7','8','9','A','B','C','D','E','F'};
        char[] hexChars = new char[bytes.length * 2];
        int v;
        for ( int j = 0; j < bytes.length; j++ ) {
            v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }

    private static byte[] fromHexString(final String encoded) {
        if ((encoded.length() % 2) != 0) {
            throw new IllegalArgumentException("Input string must contain an even number of characters");
        }

        final byte result[] = new byte[encoded.length()/2];
        final char enc[] = encoded.toCharArray();
        for (int i = 0; i < enc.length; i += 2) {
            StringBuilder curr = new StringBuilder(2);
            curr.append(enc[i]).append(enc[i + 1]);
            result[i/2] = (byte) Integer.parseInt(curr.toString(), 16);
        }
        return result;
    }
	
}
