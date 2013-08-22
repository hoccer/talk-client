package com.hoccer.talk.client;

import better.jsonrpc.client.JsonRpcClient;
import better.jsonrpc.core.JsonRpcConnection;
import better.jsonrpc.server.JsonRpcServer;
import better.jsonrpc.websocket.JsonRpcWsClient;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hoccer.talk.client.model.TalkClientContact;
import com.hoccer.talk.client.model.TalkClientDownload;
import com.hoccer.talk.client.model.TalkClientMessage;
import com.hoccer.talk.client.model.TalkClientSelf;
import com.hoccer.talk.client.model.TalkClientUpload;
import com.hoccer.talk.crypto.AESCryptor;
import com.hoccer.talk.crypto.RSACryptor;
import com.hoccer.talk.model.TalkAttachment;
import com.hoccer.talk.model.TalkDelivery;
import com.hoccer.talk.model.TalkGroup;
import com.hoccer.talk.model.TalkGroupMember;
import com.hoccer.talk.model.TalkKey;
import com.hoccer.talk.model.TalkMessage;
import com.hoccer.talk.model.TalkPresence;
import com.hoccer.talk.model.TalkPrivateKey;
import com.hoccer.talk.model.TalkRelationship;
import com.hoccer.talk.model.TalkToken;
import com.hoccer.talk.rpc.ITalkRpcClient;
import com.hoccer.talk.rpc.ITalkRpcServer;
import com.hoccer.talk.srp.SRP6Parameters;
import com.hoccer.talk.srp.SRP6VerifyingClient;
import de.undercouch.bson4jackson.BsonFactory;
import org.apache.commons.codec.binary.Base64;
import org.apache.log4j.Logger;
import org.bouncycastle.crypto.CryptoException;
import org.bouncycastle.crypto.Digest;
import org.bouncycastle.crypto.agreement.srp.SRP6VerifierGenerator;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.eclipse.jetty.websocket.WebSocketClient;
import org.eclipse.jetty.websocket.WebSocketClientFactory;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.Vector;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class HoccerTalkClient implements JsonRpcConnection.Listener {

    private static final Logger LOG = Logger.getLogger(HoccerTalkClient.class);

    /** State in which the client does not attempt any communication */
    public static final int STATE_INACTIVE = 0;
    /** State in which the client is ready to connect if awakened */
    public static final int STATE_IDLE = 1;
    /** State while establishing connection */
    public static final int STATE_CONNECTING = 2;
    /** State of voluntary reconnect */
    public static final int STATE_RECONNECTING = 3;
    /** State where we register an account */
    public static final int STATE_REGISTERING = 4;
    /** State where we log in to our account */
    public static final int STATE_LOGIN = 5;
    /** State of synchronization after login */
    public static final int STATE_SYNCING = 6;
    /** State while there is an active connection */
    public static final int STATE_ACTIVE = 7;

    /** Digest instance used for SRP auth */
    private final Digest SRP_DIGEST = new SHA256Digest();
    /** RNG used for SRP auth */
    private static final SecureRandom SRP_RANDOM = new SecureRandom();
    /** Constant SRP parameters */
    private static final SRP6Parameters SRP_PARAMETERS = SRP6Parameters.CONSTANTS_1024;

    /** Names of our states for debugging */
    private static final String[] STATE_NAMES = {
            "inactive", "idle", "connecting", "reconnecting", "registering", "login", "syncing", "active"
    };

    /** Return the name of the given state */
    public static final String stateToString(int state) {
        if(state >= 0 && state < STATE_NAMES.length) {
            return STATE_NAMES[state];
        } else {
            return "INVALID(" + state + ")";
        }
    }

    /** The database backend we use */
    ITalkClientDatabaseBackend mDatabaseBackend;
    /* The database instance we use */
    TalkClientDatabase mDatabase;

    /** Directory for avatar images */
    String mAvatarDirectory;
    /** Directory for received attachments */
    String mAttachmentDirectory;
    /** Directory for encrypted intermediate uploads */
    String mEncryptedUploadDirectory;
    /** Directory for encrypted intermediate downloads */
    String mEncryptedDownloadDirectory;

    TalkTransferAgent mTransferAgent;

    /** Factory for underlying websocket connections */
    WebSocketClientFactory mClientFactory;
    /** JSON-RPC client instance */
	JsonRpcWsClient mConnection;
    /* RPC handler for notifications */
	TalkRpcClientImpl mHandler;
    /* RPC proxy bound to our server */
	ITalkRpcServer mServerRpc;

    /** Executor doing all the heavy network and database work */
    ScheduledExecutorService mExecutor;

    /* Futures keeping track of singleton background operations */
    ScheduledFuture<?> mLoginFuture;
    ScheduledFuture<?> mRegistrationFuture;
    ScheduledFuture<?> mConnectFuture;
    ScheduledFuture<?> mDisconnectFuture;
    ScheduledFuture<?> mAutoDisconnectFuture;
    ScheduledFuture<?> mKeepAliveFuture;

    /** All our listeners */
    Vector<ITalkClientListener> mListeners = new Vector<ITalkClientListener>();

    /** Listeners for unseen messages */
    Vector<ITalkUnseenListener> mUnseenListeners = new Vector<ITalkUnseenListener>();

    /** The current state of this client */
    int mState = STATE_INACTIVE;

    /** Connection retry count for back-off */
    int mConnectionFailures = 0;

    /** Last client activity */
    long mLastActivity = 0;

    ObjectMapper mJsonMapper;

    /**
     * Create a Hoccer Talk client using the given client database
     */
	public HoccerTalkClient(ScheduledExecutorService backgroundExecutor, ITalkClientDatabaseBackend databaseBackend) {
        // remember the executor provided by the client
        mExecutor = backgroundExecutor;
        // as well as the database backend
        mDatabaseBackend = databaseBackend;

        // create and initialize the database
        mDatabase = new TalkClientDatabase(mDatabaseBackend);
        try {
            mDatabase.initialize();
        } catch (SQLException e) {
            e.printStackTrace();
        }

        // create URI object referencing the server
        URI uri = null;
        try {
            uri = new URI(TalkClientConfiguration.SERVER_URI);
        } catch (URISyntaxException e) {
            // won't happen
        }

        // create JSON object mapper
        JsonFactory jsonFactory = new JsonFactory();
        mJsonMapper = createObjectMapper(jsonFactory);

        // create RPC object mapper (BSON or JSON)
        JsonFactory rpcFactory;
        if(TalkClientConfiguration.USE_BSON_PROTOCOL) {
            rpcFactory = new BsonFactory();
        } else {
            rpcFactory = jsonFactory;
        }
        ObjectMapper rpcMapper = createObjectMapper(rpcFactory);

        // create websocket client
        WebSocketClientFactory wscFactory = new WebSocketClientFactory();
        try {
            wscFactory.start();
        } catch (Exception e) {
            e.printStackTrace();
        }
        WebSocketClient wsClient = wscFactory.newWebSocketClient();

        // create json-rpc client
        String protocol = TalkClientConfiguration.USE_BSON_PROTOCOL
                ? TalkClientConfiguration.PROTOCOL_STRING_BSON
                : TalkClientConfiguration.PROTOCOL_STRING_JSON;
        mConnection = new JsonRpcWsClient(uri, protocol, wsClient, rpcMapper);
        mConnection.setMaxIdleTime(TalkClientConfiguration.CONNECTION_IDLE_TIMEOUT);
        mConnection.setSendKeepAlives(TalkClientConfiguration.KEEPALIVE_ENABLED);
        if(TalkClientConfiguration.USE_BSON_PROTOCOL) {
            mConnection.setSendBinaryMessages(true);
        }

        // create client-side RPC handler object
        mHandler = new TalkRpcClientImpl();

        // configure JSON-RPC client
        JsonRpcClient clt = new JsonRpcClient();
        mConnection.bindClient(clt);

        // configure JSON-RPC server object
        JsonRpcServer srv = new JsonRpcServer(ITalkRpcClient.class);
        mConnection.bindServer(srv, getHandler());

        // listen for connection state changes
        mConnection.addListener(this);

        // create RPC proxy
		mServerRpc = mConnection.makeProxy(ITalkRpcServer.class);

        // create transfer agent
        mTransferAgent = new TalkTransferAgent(this);
	}

    public String getAvatarDirectory() {
        return mAvatarDirectory;
    }

    public void setAvatarDirectory(String avatarDirectory) {
        this.mAvatarDirectory = avatarDirectory;
    }

    public String getAttachmentDirectory() {
        return mAttachmentDirectory;
    }

    public void setAttachmentDirectory(String attachmentDirectory) {
        this.mAttachmentDirectory = attachmentDirectory;
    }

    public String getEncryptedUploadDirectory() {
        return mEncryptedUploadDirectory;
    }

    public void setEncryptedUploadDirectory(String encryptedUploadDirectory) {
        this.mEncryptedUploadDirectory = encryptedUploadDirectory;
    }

    public String getEncryptedDownloadDirectory() {
        return mEncryptedDownloadDirectory;
    }

    public void setEncryptedDownloadDirectory(String encryptedDownloadDirectory) {
        this.mEncryptedDownloadDirectory = encryptedDownloadDirectory;
    }

    public URI getServiceUri() {
        return mConnection.getServiceUri();
    }

    public void setServiceUri(URI serviceUri) {
        mConnection.setServiceUri(serviceUri);
        if(mState >= STATE_IDLE) {
            reconnect("URI changed");
        }
    }

    public TalkClientDatabase getDatabase() {
        return mDatabase;
    }

    public TalkTransferAgent getTransferAgent() {
        return mTransferAgent;
    }

    /**
     * @return the RPC proxy towards the server
     */
    public ITalkRpcServer getServerRpc() {
        return mServerRpc;
    }

    /**
     * @return the RPC handler for notifications
     */
    public ITalkRpcClient getHandler() {
        return mHandler;
    }

    /**
     * @return the current state of this client (numerical)
     */
    public int getState() {
        return mState;
    }

    /**
     * @return the current state of this client (textual)
     */
    public String getStateString() {
        return stateToString(mState);
    }

    /**
     * Add a listener
     * @param listener
     */
    public void registerListener(ITalkClientListener listener) {
        mListeners.add(listener);
    }

    /**
     * Remove a listener
     * @param listener
     */
    public void unregisterListener(ITalkClientListener listener) {
        mListeners.remove(listener);
    }

    public void registerUnseenListener(ITalkUnseenListener listener) {
        mUnseenListeners.add(listener);
    }

    public void unregisterUnseenListener(ITalkUnseenListener listener) {
        mUnseenListeners.remove(listener);
    }

    private void notifyUnseenMessages(boolean notify) {
        LOG.info("notifyUnseenMessages()");
        List<TalkClientMessage> unseenMessages = null;
        try {
            unseenMessages = mDatabase.findUnseenMessages();
        } catch (SQLException e) {
            LOG.error("SQL error", e);
        }
        for(ITalkUnseenListener listener: mUnseenListeners) {
            listener.onUnseenMessages(unseenMessages, notify);
        }
    }

    public boolean isIdle() {
        return (System.currentTimeMillis() - mLastActivity) > (TalkClientConfiguration.IDLE_TIMEOUT * 1000);
    }

    /**
     * Returns true if the client has been activated
     *
     * This is only true after an explicit call to activate().
     *
     * @return
     */
    public boolean isActivated() {
        return mState > STATE_INACTIVE;
    }

    /**
     * Returns true if the client is awake
     *
     * This means that the client is trying to connect or connected.
     *
     * @return
     */
    public boolean isAwake() {
        return mState > STATE_IDLE;
    }

    /**
     * Activate the client, allowing it do operate
     */
    public void activate() {
        LOG.debug("client: activate()");
        if(mState == STATE_INACTIVE) {
            if(isIdle()) {
                switchState(STATE_IDLE, "client activated idle");
            } else {
                switchState(STATE_CONNECTING, "client activated");
            }
        }
    }

    /**
     * Deactivate the client, shutting it down completely
     */
    public void deactivate() {
        LOG.debug("client: deactivate()");
        if(mState != STATE_INACTIVE) {
            switchState(STATE_INACTIVE, "client deactivated");
        }
    }

    /**
     * Wake the client so it will connect and speak with the server
     */
    public void wake() {
        LOG.debug("client: wake()");
        resetIdle();
        if(mState < STATE_CONNECTING) {
            switchState(STATE_CONNECTING, "client woken");
        }
    }

    /**
     * Reconnect the client immediately
     */
    public void reconnect(final String reason) {
        if(mState > STATE_IDLE) {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    switchState(STATE_RECONNECTING, "reconnect: " + reason);
                }
            });
        }
    }

    /**
     * Blocking version of the deactivation call
     */
    public void deactivateNow() {
        LOG.debug("client: deactivateNow()");
        mAutoDisconnectFuture.cancel(true);
        mLoginFuture.cancel(true);
        mConnectFuture.cancel(true);
        mDisconnectFuture.cancel(true);
        mState = STATE_INACTIVE;
    }

    /**
     * Register the given GCM push information with the server
     * @param packageName
     * @param registrationId
     */
    public void registerGcm(final String packageName, final String registrationId) {
        resetIdle();
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                mServerRpc.registerGcm(packageName, registrationId);
            }
        });
    }

    /**
     * Unregister any GCM push information on the server
     */
    public void unregisterGcm() {
        resetIdle();
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                mServerRpc.unregisterGcm();
            }
        });
    }

    public void setClientString(String newName, String newStatus) {
        resetIdle();
        try {
            TalkClientContact contact = mDatabase.findSelfContact(false);
            if(contact != null) {
                TalkPresence presence = contact.getClientPresence();
                if(presence != null) {
                    if(newName != null) {
                        presence.setClientName(newName);
                    }
                    if(newStatus != null) {
                        presence.setClientStatus(newStatus);
                    }
                    mDatabase.savePresence(presence);
                    sendPresence();
                }
            }
        } catch (SQLException e) {
            LOG.error("sql error", e);
        }
    }

    public void setClientAvatar(final TalkClientUpload upload) {
        LOG.info("new avatar as upload " + upload);
        resetIdle();
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                LOG.info("registering avatar");
                if(!upload.performRegistration(mTransferAgent)) {
                    LOG.error("avatar upload registration failed");
                    return;
                }
                String downloadUrl = upload.getDownloadUrl();
                if(downloadUrl == null) {
                    LOG.error("registered avatar upload without download url");
                    return;
                }
                mTransferAgent.requestUpload(upload);
                try {
                    TalkClientContact contact = mDatabase.findSelfContact(false);
                    if(contact != null) {
                        TalkPresence presence = contact.getClientPresence();
                        if(presence != null) {
                            presence.setAvatarUrl(downloadUrl);
                        }
                        contact.setAvatarUpload(upload);
                        mDatabase.savePresence(presence);
                        mDatabase.saveContact(contact);
                        for(ITalkClientListener listener: mListeners) {
                            listener.onClientPresenceChanged(contact);
                        }
                        LOG.info("sending new presence");
                        sendPresence();
                    }
                } catch (SQLException e) {
                    LOG.error("sql error", e);
                }

            }
        });
    }

    public String generatePairingToken() {
        resetIdle();
        String tokenPurpose = TalkToken.PURPOSE_PAIRING;
        int tokenLifetime = 7 * 24 * 3600;
        String token = mServerRpc.generateToken(tokenPurpose, tokenLifetime);
        LOG.debug("got pairing token " + token);
        return token;
    }

    public boolean performTokenPairing(final String token) {
        resetIdle();
        return mServerRpc.pairByToken(token);
    }

    public void depairContact(final TalkClientContact contact) {
        resetIdle();
        if(contact.isClient()) {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    mServerRpc.depairClient(contact.getClientId());
                }
            });
        }
    }

    public void deleteContact(final TalkClientContact contact) {
        resetIdle();
        if(contact.isClient() || contact.isGroup()) {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    contact.markAsDeleted();

                    try {
                        mDatabase.saveContact(contact);
                    } catch (SQLException e) {
                        LOG.error("SQL error", e);
                    }

                    for(ITalkClientListener listener: mListeners) {
                        listener.onContactRemoved(contact);
                    }

                    if(contact.isClient() && contact.isClientRelated()) {
                        mServerRpc.depairClient(contact.getClientId());
                    }
                    if(contact.isGroup() && contact.isGroupJoined()) {
                        mServerRpc.leaveGroup(contact.getGroupId());
                    }
                }
            });
        }
    }

    public void blockContact(final TalkClientContact contact) {
        resetIdle();
        if(contact.isClient()) {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    mServerRpc.blockClient(contact.getClientId());
                }
            });
        }
    }

    public void unblockContact(final TalkClientContact contact) {
        resetIdle();
        if(contact.isClient()) {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    mServerRpc.unblockClient(contact.getClientId());
                }
            });
        }
    }

    public TalkClientContact createGroup() {
        resetIdle();
        String groupTag = UUID.randomUUID().toString();
        TalkClientContact contact = new TalkClientContact(TalkClientContact.TYPE_GROUP);
        contact.updateGroupTag(groupTag);
        TalkGroup groupPresence = new TalkGroup();
        groupPresence.setGroupTag(groupTag);
        groupPresence.setGroupName("Group");
        contact.updateGroupPresence(groupPresence);
        try {
            mDatabase.saveGroup(groupPresence);
            mDatabase.saveContact(contact);
        } catch (SQLException e) {
            e.printStackTrace();
        }
        mServerRpc.createGroup(groupPresence);
        return contact;
    }

    public void inviteClientToGroup(final String groupId, final String clientId) {
        resetIdle();
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                mServerRpc.inviteGroupMember(groupId, clientId);
            }
        });
    }

    public void joinGroup(final String groupId) {
        resetIdle();
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                mServerRpc.joinGroup(groupId);
            }
        });
    }

    public void leaveGroup(final String groupId) {
        resetIdle();
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                mServerRpc.leaveGroup(groupId);
            }
        });
    }

    public void requestDelivery() {
        resetIdle();
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                performDeliveries();
            }
        });
    }

    private ObjectMapper createObjectMapper(JsonFactory jsonFactory) {
        ObjectMapper result = new ObjectMapper(jsonFactory);
        result.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        result.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return result;
    }

    private void switchState(int newState, String message) {
        // only switch of there really was a change
        if(mState == newState) {
            LOG.debug("state remains " + STATE_NAMES[mState] + " (" + message + ")");
        }

        // log about it
        LOG.info("state " + STATE_NAMES[mState] + " -> " + STATE_NAMES[newState] + " (" + message + ")");

        // perform transition
        int previousState = mState;
        mState = newState;

        // maintain keep-alives timer
        if(mState >= STATE_SYNCING) {
            scheduleKeepAlive();
        } else {
            shutdownKeepAlive();
        }

        // make disconnects happen
        if(mState == STATE_IDLE || mState == STATE_INACTIVE) {
            scheduleDisconnect();
        } else {
            shutdownDisconnect();
        }

        // make connects happen
        if(mState == STATE_RECONNECTING) {
            LOG.info("scheduling requested reconnect");
            mConnectionFailures = 0;
            scheduleConnect(true);
            resetIdle();
        } else if(mState == STATE_CONNECTING) {
            if(previousState <= STATE_IDLE) {
                LOG.info("scheduling connect");
                // initial connect
                mConnectionFailures = 0;
                scheduleConnect(false);
            } else {
                LOG.info("scheduling reconnect");
                // reconnect
                mConnectionFailures++;
                scheduleConnect(true);
            }
        } else {
            shutdownConnect();
        }

        if(mState == STATE_REGISTERING) {
            scheduleRegistration();
        } else {
            shutdownRegistration();
        }

        if(mState == STATE_LOGIN) {
            scheduleLogin();
        } else {
            shutdownLogin();
        }

        if(mState == STATE_SYNCING) {
            scheduleSync();
        }

        if(mState == STATE_ACTIVE) {
            mConnectionFailures = 0;
            // start talking
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    LOG.info("connected and ready");
                }
            });
        }

        // call listeners
        if(previousState != newState) {
            for(ITalkClientListener listener: mListeners) {
                listener.onClientStateChange(this, newState);
            }
        }
    }

    private void handleDisconnect() {
        LOG.debug("handleDisconnect()");
        switch(mState) {
            case STATE_INACTIVE:
            case STATE_IDLE:
                LOG.debug("supposed to be disconnected");
                // we are supposed to be disconnected, things are fine
                break;
            case STATE_CONNECTING:
            case STATE_RECONNECTING:
            case STATE_REGISTERING:
            case STATE_LOGIN:
            case STATE_ACTIVE:
                LOG.debug("supposed to be connected - scheduling connect");
                switchState(STATE_CONNECTING, "disconnected while active");
                break;
        }
    }

    /**
     * Called when the connection is opened
     * @param connection
     */
	@Override
	public void onOpen(JsonRpcConnection connection) {
        LOG.debug("onOpen()");
        scheduleIdle();
        try {
            TalkClientContact selfContact = mDatabase.findSelfContact(true);
            if(selfContact.isSelfRegistered()) {
                switchState(STATE_LOGIN, "connected");
            } else {
                switchState(STATE_REGISTERING, "connected and unregistered");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }

	}

    /**
     * Called when the connection is closed
     * @param connection
     */
	@Override
	public void onClose(JsonRpcConnection connection) {
        LOG.debug("onClose()");
        shutdownIdle();
        handleDisconnect();
	}

    private void doConnect() {
        LOG.debug("performing connect");
        try {
            mConnection.connect(TalkClientConfiguration.CONNECT_TIMEOUT, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.info("exception while connecting: " + e.toString());
        }
    }

    private void doDisconnect() {
        LOG.debug("performing disconnect");
        mConnection.disconnect();
    }

    private void shutdownIdle() {
        if(mAutoDisconnectFuture != null) {
            mAutoDisconnectFuture.cancel(false);
            mAutoDisconnectFuture = null;
        }
    }

    private void scheduleIdle() {
        shutdownIdle();
        if(mState > STATE_CONNECTING) {
            mAutoDisconnectFuture = mExecutor.schedule(new Runnable() {
                @Override
                public void run() {
                    switchState(STATE_IDLE, "activity timeout");
                    mAutoDisconnectFuture = null;
                }
            }, TalkClientConfiguration.IDLE_TIMEOUT, TimeUnit.SECONDS);
        }
    }

    private void resetIdle() {
        LOG.debug("resetIdle()");
        mLastActivity = System.currentTimeMillis();
        scheduleIdle();
    }

    private void shutdownKeepAlive() {
        if(mKeepAliveFuture != null) {
            mKeepAliveFuture.cancel(false);
            mKeepAliveFuture = null;
        }
    }

    private void scheduleKeepAlive() {
        shutdownKeepAlive();
        if(TalkClientConfiguration.KEEPALIVE_ENABLED) {
            mKeepAliveFuture = mExecutor.scheduleAtFixedRate(new Runnable() {
                    @Override
                    public void run() {
                        LOG.info("performing keep-alive");
                        try {
                            mConnection.sendKeepAlive();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                },
                TalkClientConfiguration.KEEPALIVE_INTERVAL,
                TalkClientConfiguration.KEEPALIVE_INTERVAL,
                TimeUnit.SECONDS);
        }
    }

    private void shutdownConnect() {
        if(mConnectFuture != null) {
            mConnectFuture.cancel(false);
            mConnectFuture = null;
        }
    }

    private void scheduleConnect(boolean isReconnect) {
        LOG.debug("scheduleConnect()");
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

            LOG.debug("connection attempt backed off by " + totalTime + " seconds");
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
        LOG.debug("scheduleLogin()");
        shutdownLogin();
        mLoginFuture = mExecutor.schedule(new Runnable() {
            @Override
            public void run() {
                try {
                    TalkClientContact selfContact = mDatabase.findSelfContact(true);
                    performLogin(selfContact);
                } catch (Throwable t) {
                    t.printStackTrace();
                }
                mLoginFuture = null;
                switchState(STATE_SYNCING, "login successful");
            }
        }, 0, TimeUnit.SECONDS);
    }

    private void shutdownRegistration() {
        if(mRegistrationFuture != null) {
            mRegistrationFuture.cancel(false);
            mRegistrationFuture = null;
        }
    }

    private void scheduleRegistration() {
        LOG.debug("scheduleRegistration()");
        shutdownRegistration();
        mRegistrationFuture = mExecutor.schedule(new Runnable() {
            @Override
            public void run() {
                try {
                    TalkClientContact selfContact = mDatabase.findSelfContact(true);
                    performRegistration(selfContact);
                    mRegistrationFuture = null;
                    switchState(STATE_LOGIN, "registered");
                } catch (SQLException e) {
                    LOG.error("sql error", e);
                }
            }
        }, 0, TimeUnit.SECONDS);
    }

    private void scheduleSync() {
        LOG.debug("scheduleSync()");
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                Date never = new Date(0);
                try {
                    LOG.debug("sync: updating presence");
                    sendPresence();
                    LOG.debug("sync: syncing presences");
                    TalkPresence[] presences = mServerRpc.getPresences(never);
                    for(TalkPresence presence: presences) {
                        updateClientPresence(presence);
                    }
                    LOG.debug("sync: syncing relationships");
                    TalkRelationship[] relationships = mServerRpc.getRelationships(never);
                    for(TalkRelationship relationship: relationships) {
                        updateClientRelationship(relationship);
                    }
                    LOG.debug("sync: syncing groups");
                    TalkGroup[] groups = mServerRpc.getGroups(never);
                    for(TalkGroup group: groups) {
                        updateGroupPresence(group);
                    }
                    LOG.debug("sync: syncing group memberships");
                    List<TalkClientContact> contacts = mDatabase.findAllGroupContacts();
                    for(TalkClientContact group: contacts) {
                        if(group.isGroup() && group.isGroupJoined()) {
                            TalkGroupMember[] members = mServerRpc.getGroupMembers(group.getGroupId(), never);
                            for(TalkGroupMember member: members) {
                                updateGroupMember(member);
                            }
                        }
                    }
                } catch (Throwable t) {
                    t.printStackTrace();
                }
                switchState(STATE_ACTIVE, "sync successful");
            }
        });
    }

    private void shutdownDisconnect() {
        if(mDisconnectFuture != null) {
            mDisconnectFuture.cancel(false);
            mDisconnectFuture = null;
        }
    }

    private void scheduleDisconnect() {
        LOG.debug("scheduleDisconnect()");
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
            LOG.debug("server: ping()");
        }

        @Override
        public void alertUser(String message) {
            LOG.debug("server: alertUser()");
            LOG.info("ALERTING USER: \"" + message + "\"");
        }

        @Override
        public void pushNotRegistered() {
            LOG.debug("server: pushNotRegistered()");
            for(ITalkClientListener listener: mListeners) {
                listener.onPushRegistrationRequested();
            }
        }

        @Override
		public void incomingDelivery(TalkDelivery d, TalkMessage m) {
			LOG.info("server: incomingDelivery()");
            updateIncomingDelivery(d, m);
		}

		@Override
		public void outgoingDelivery(TalkDelivery d) {
			LOG.info("server: outgoingDelivery()");
            updateOutgoingDelivery(d);
		}

        @Override
        public void presenceUpdated(TalkPresence presence) {
            LOG.debug("server: presenceUpdated(" + presence.getClientId() + ")");
            updateClientPresence(presence);
        }

        @Override
        public void relationshipUpdated(TalkRelationship relationship) {
            LOG.debug("server: relationshipUpdated(" + relationship.getOtherClientId() + ")");
            updateClientRelationship(relationship);
        }

        @Override
        public void groupUpdated(TalkGroup group) {
            LOG.debug("server: groupUpdated(" + group.getGroupId() + ")");
            updateGroupPresence(group);
        }

        @Override
        public void groupMemberUpdated(TalkGroupMember member) {
            LOG.debug("server: groupMemberUpdated(" + member.getGroupId() + "/" + member.getClientId() + ")");
            updateGroupMember(member);
        }

    }

    private void performRegistration(TalkClientContact selfContact) {
        LOG.debug("registration: attempting registration");

        Digest digest = SRP_DIGEST;
        byte[] salt = new byte[digest.getDigestSize()];
        byte[] secret = new byte[digest.getDigestSize()];
        SRP6VerifierGenerator vg = new SRP6VerifierGenerator();

        vg.init(SRP_PARAMETERS.N, SRP_PARAMETERS.g, digest);

        SRP_RANDOM.nextBytes(salt);
        SRP_RANDOM.nextBytes(secret);

        String saltString = bytesToHex(salt);
        String secretString = bytesToHex(secret);

        String clientId = mServerRpc.generateId();

        LOG.debug("registration: started with id " + clientId);

        BigInteger verifier = vg.generateVerifier(salt, clientId.getBytes(), secret);

        mServerRpc.srpRegister(verifier.toString(16), bytesToHex(salt));

        LOG.debug("registration: finished");

        TalkClientSelf self = new TalkClientSelf(saltString, secretString);

        selfContact.updateSelfRegistered(clientId, self);

        try {
            mDatabase.saveCredentials(self);
            mDatabase.saveContact(selfContact);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void performLogin(TalkClientContact selfContact) {
        String clientId = selfContact.getClientId();
        LOG.debug("login: attempting login as " + clientId);
        Digest digest = SRP_DIGEST;

        TalkClientSelf self = selfContact.getSelf();

        SRP6VerifyingClient vc = new SRP6VerifyingClient();
        vc.init(SRP_PARAMETERS.N, SRP_PARAMETERS.g, digest, SRP_RANDOM);

        LOG.debug("login: performing phase 1");

        byte[] loginId = clientId.getBytes();
        byte[] loginSalt = fromHexString(self.getSrpSalt());
        byte[] loginSecret = fromHexString(self.getSrpSecret());
        BigInteger A = vc.generateClientCredentials(loginSalt, loginId, loginSecret);

        String Bs = mServerRpc.srpPhase1(clientId,  A.toString(16));
        try {
            vc.calculateSecret(new BigInteger(Bs, 16));
        } catch (CryptoException e) {
            e.printStackTrace();
        }

        LOG.debug("login: performing phase 2");

        String Vc = bytesToHex(vc.calculateVerifier());
        String Vs = mServerRpc.srpPhase2(Vc);
        try {
            vc.verifyServer(fromHexString(Vs));
        } catch (CryptoException e) {
            throw new RuntimeException("Server verification failed");
        }

        LOG.debug("login: successful");
    }

    private void performDeliveries() {
        LOG.info("performing deliveries");
        try {
            List<TalkClientMessage> clientMessages = mDatabase.findMessagesForDelivery();
            LOG.info(clientMessages.size() + " to deliver");
            TalkDelivery[] deliveries = new TalkDelivery[clientMessages.size()];
            TalkMessage[] messages = new TalkMessage[clientMessages.size()];
            int i = 0;
            for(TalkClientMessage clientMessage: clientMessages) {
                LOG.info("preparing " + clientMessage.getClientMessageId());
                deliveries[i] = clientMessage.getOutgoingDelivery();
                messages[i] = clientMessage.getMessage();
                try {
                    encryptMessage(clientMessage, deliveries[i], messages[i]);
                } catch (Throwable t) {
                    LOG.error("error encrypting", t);
                }
                i++;
            }
            for(i = 0; i < messages.length; i++) {
                LOG.info("delivering " + i);
                TalkDelivery[] delivery = new TalkDelivery[1];
                delivery[0] = deliveries[i];
                TalkDelivery[] resultingDeliveries = mServerRpc.deliveryRequest(messages[i], delivery);
                for(int j = 0; j < resultingDeliveries.length; j++) {
                    updateOutgoingDelivery(resultingDeliveries[j]);
                }
            }
        } catch (SQLException e) {
            LOG.error("SQL error", e);
        }
    }

    private TalkClientContact ensureSelfContact() throws SQLException {
        TalkClientContact contact = mDatabase.findSelfContact(false);
        if(contact == null) {
            throw new RuntimeException("We should have a self contact!?");
        }
        return contact;
    }

    private TalkPresence ensureSelfPresence(TalkClientContact contact) throws SQLException {
        try {
            TalkPresence presence = contact.getClientPresence();
            if(presence == null) {
                presence = new TalkPresence();
                presence.setClientId(contact.getClientId());
                presence.setClientName("Client");
                presence.setClientStatus("I am.");
                presence.setTimestamp(new Date());
                contact.updatePresence(presence);
                mDatabase.savePresence(presence);
                mDatabase.saveContact(contact);
            }
            return presence;
        } catch (SQLException e) {
            e.printStackTrace();
            return null;
        }
    }

    private void ensureSelfKey(TalkClientContact contact) throws SQLException {
        TalkKey publicKey = contact.getPublicKey();
        TalkPrivateKey privateKey = contact.getPrivateKey();
        if(publicKey == null || privateKey == null) {
            Date now = new Date();
            try {
                LOG.info("generating new RSA keypair");

                LOG.debug("generating keypair");
                KeyPair keyPair = RSACryptor.generateRSAKeyPair(1024);

                LOG.trace("unwrapping public key");
                PublicKey pubKey = keyPair.getPublic();
                byte[] pubEnc = RSACryptor.unwrapRSA1024_X509(pubKey.getEncoded());
                String pubStr = Base64.encodeBase64String(pubEnc);

                LOG.trace("unwrapping private key");
                PrivateKey privKey = keyPair.getPrivate();
                byte[] privEnc = RSACryptor.unwrapRSA1024_PKCS8(privKey.getEncoded());
                String privStr = Base64.encodeBase64String(privEnc);

                LOG.debug("calculating key id");
                String kid = RSACryptor.calcKeyId(pubEnc);

                LOG.debug("creating database objects");

                publicKey = new TalkKey();
                publicKey.setClientId(contact.getClientId());
                publicKey.setTimestamp(now);
                publicKey.setKeyId(kid);
                publicKey.setKey(pubStr);

                privateKey = new TalkPrivateKey();
                privateKey.setClientId(contact.getClientId());
                privateKey.setTimestamp(now);
                privateKey.setKeyId(kid);
                privateKey.setKey(privStr);

                contact.setPublicKey(publicKey);
                contact.setPrivateKey(privateKey);

                LOG.info("generated new key with key id " + kid);

                mDatabase.savePublicKey(publicKey);
                mDatabase.savePrivateKey(privateKey);
                mDatabase.saveContact(contact);
            } catch (Exception e) {
                LOG.error("exception generating key", e);
            }
        } else {
            LOG.info("using key with key id " + publicKey.getKeyId());
        }
    }

    private void sendPresence() {
        LOG.debug("sendPresence()");
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                Date now = new Date();
                try {
                    TalkClientContact contact = ensureSelfContact();
                    ensureSelfPresence(contact);
                    ensureSelfKey(contact);
                    TalkPresence presence = contact.getClientPresence();
                    presence.setKeyId(contact.getPublicKey().getKeyId());
                    mDatabase.savePresence(presence);
                    mDatabase.saveContact(contact);
                    mServerRpc.updateKey(contact.getPublicKey());
                    mServerRpc.updatePresence(presence);
                } catch (SQLException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    private void updateOutgoingDelivery(final TalkDelivery delivery) {
        LOG.debug("updateOutgoingDelivery(" + delivery.getMessageId() + ")");

        TalkClientContact clientContact = null;
        TalkClientContact groupContact = null;
        TalkClientMessage clientMessage = null;
        try {
            String receiverId = delivery.getReceiverId();
            if(receiverId != null) {
                clientContact = mDatabase.findContactByClientId(receiverId, false);
                if(clientContact == null) {
                    LOG.warn("outgoing message for unknown client " + receiverId);
                    return;
                }
            }

            String groupId = delivery.getGroupId();
            if(groupId != null) {
                groupContact = mDatabase.findContactByGroupId(groupId, false);
                if(groupContact == null) {
                    LOG.warn("outgoing message for unknown group " + groupId);
                }
            }

            String messageId = delivery.getMessageId();
            String messageTag = delivery.getMessageTag();
            if(messageTag != null) {
                clientMessage = mDatabase.findMessageByMessageTag(messageTag, false);
            }
            if(clientMessage == null) {
                clientMessage = mDatabase.findMessageByMessageId(messageId, false);
            }
            if(clientMessage == null) {
                LOG.warn("outgoing delivery notification for unknown message " + delivery.getMessageId());
                return;
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return;
        }

        clientMessage.updateOutgoing(delivery);

        try {
            mDatabase.saveDelivery(clientMessage.getOutgoingDelivery());
            mDatabase.saveClientMessage(clientMessage);
        } catch (SQLException e) {
            e.printStackTrace();
        }

        if(delivery.getState().equals(TalkDelivery.STATE_DELIVERED)) {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    mServerRpc.deliveryAcknowledge(delivery.getMessageId(), delivery.getReceiverId());
                }
            });
        }

        for(ITalkClientListener listener: mListeners) {
            listener.onMessageStateChanged(clientMessage);
        }
    }

    private void updateIncomingDelivery(final TalkDelivery delivery, final TalkMessage message) {
        LOG.debug("updateIncomingDelivery(" + delivery.getMessageId() + ")");
        boolean newMessage = false;
        TalkClientContact groupContact = null;
        TalkClientContact senderContact = null;
        TalkClientMessage clientMessage = null;
        try {
            String groupId = delivery.getGroupId();
            if(groupId != null) {
                groupContact = mDatabase.findContactByGroupId(groupId, false);
                if(groupContact == null) {
                    LOG.warn("incoming message in unknown group " + groupId);
                    return;
                }
            }
            senderContact = mDatabase.findContactByClientId(message.getSenderId(), false);
            if(senderContact == null) {
                LOG.warn("incoming message from unknown client " + message.getSenderId());
                return;
            }
            clientMessage = mDatabase.findMessageByMessageId(delivery.getMessageId(), false);
            if(clientMessage == null) {
                newMessage = true;
                clientMessage = mDatabase.findMessageByMessageId(delivery.getMessageId(), true);
            }
        } catch (SQLException e) {
            LOG.error("sql error", e);
            return;
        }

        clientMessage.setSenderContact(senderContact);

        if(groupContact == null) {
            clientMessage.setConversationContact(senderContact);
        } else {
            clientMessage.setConversationContact(groupContact);
        }

        decryptMessage(clientMessage, delivery, message);

        clientMessage.updateIncoming(delivery, message);

        TalkClientDownload attachmentDownload = clientMessage.getAttachmentDownload();
        try {
            if(attachmentDownload != null) {
                mDatabase.saveClientDownload(clientMessage.getAttachmentDownload());
            }
            mDatabase.saveMessage(clientMessage.getMessage());
            mDatabase.saveDelivery(clientMessage.getIncomingDelivery());
            mDatabase.saveClientMessage(clientMessage);
        } catch (SQLException e) {
            e.printStackTrace();
        }

        if(delivery.getState().equals(TalkDelivery.STATE_DELIVERING)) {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    mServerRpc.deliveryConfirm(delivery.getMessageId());
                }
            });
        }

        if(attachmentDownload != null) {
            mTransferAgent.requestDownload(attachmentDownload);
        }

        for(ITalkClientListener listener: mListeners) {
            if(newMessage) {
                listener.onMessageAdded(clientMessage);
            } else {
                listener.onMessageStateChanged(clientMessage);
            }
        }

        notifyUnseenMessages(newMessage);
    }

    private void decryptMessage(TalkClientMessage clientMessage, TalkDelivery delivery, TalkMessage message) {

        clientMessage.setText("<Unreadable>");

        try {
            String keyId = delivery.getKeyId();
            String keyCiphertext = delivery.getKeyCiphertext();
            String rawBody = message.getBody();
            String rawAttachment = message.getAttachment();
            if(keyId == null || keyCiphertext == null) {
                if(rawBody == null) {
                    clientMessage.setText("");
                } else {
                    clientMessage.setText(message.getBody());
                }
            } else {
                TalkPrivateKey talkPrivateKey = mDatabase.findPrivateKeyByKeyId(keyId);
                if(talkPrivateKey == null) {
                    LOG.error("no private key for keyId " + keyId);
                } else {
                    PrivateKey privateKey = talkPrivateKey.getAsNative();
                    if(privateKey == null) {
                        LOG.error("could not decode private key");
                    } else {
                        byte[] decryptedKey = null;
                        byte[] decryptedBodyRaw = null;
                        String decryptedBody = "";
                        byte[] decryptedAttachmentRaw = null;
                        TalkAttachment decryptedAttachment = null;
                        try {
                            decryptedKey = RSACryptor.decryptRSA(privateKey, Base64.decodeBase64(keyCiphertext));
                            if(rawBody != null) {
                                decryptedBodyRaw = AESCryptor.decrypt(decryptedKey, AESCryptor.NULL_SALT, Base64.decodeBase64(rawBody));
                                decryptedBody = new String(decryptedBodyRaw, "UTF-8");
                            }
                            if(rawAttachment != null) {
                                decryptedAttachmentRaw = AESCryptor.decrypt(decryptedKey, AESCryptor.NULL_SALT, Base64.decodeBase64(rawAttachment));
                                decryptedAttachment = mJsonMapper.readValue(decryptedAttachmentRaw, TalkAttachment.class);
                                LOG.info("attachment: " + mJsonMapper.writeValueAsString(decryptedAttachment));
                            }
                        } catch (NoSuchPaddingException e) {
                            LOG.error("error decrypting", e);
                        } catch (NoSuchAlgorithmException e) {
                            LOG.error("error decrypting", e);
                        } catch (InvalidKeyException e) {
                            LOG.error("error decrypting", e);
                        } catch (BadPaddingException e) {
                            LOG.error("error decrypting", e);
                        } catch (IllegalBlockSizeException e) {
                            LOG.error("error decrypting", e);
                        } catch (InvalidAlgorithmParameterException e) {
                            LOG.error("error decrypting", e);
                        } catch (IOException e) {
                            LOG.error("error decrypting", e);
                        }
                        if(decryptedBody != null) {
                            clientMessage.setText(decryptedBody);
                        }
                        if(decryptedAttachment != null) {
                            TalkClientDownload download = new TalkClientDownload();
                            download.initializeAsAttachment(decryptedAttachment, message.getMessageId(), decryptedKey);
                            clientMessage.setAttachmentDownload(download);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            LOG.error("SQL error", e);
        }
    }

    private void encryptMessage(TalkClientMessage clientMessage, TalkDelivery delivery, TalkMessage message) {
        if(message.getBody() != null) {
            //return;
        }

        LOG.debug("encrypting message " + clientMessage.getClientMessageId());

        TalkClientContact receiver = clientMessage.getConversationContact();
        if(receiver == null) {
            LOG.error("no receiver");
            return;
        }

        try {
            receiver = mDatabase.findClientContactById(receiver.getClientContactId());
        } catch (SQLException e) {
            LOG.error("SQL error", e);
        }

        TalkKey talkPublicKey = receiver.getPublicKey();
        if(talkPublicKey == null) {
            LOG.error("no pubkey for encryption");
            return;
        }

        LOG.debug("using private key " + talkPublicKey.getKeyId());

        PublicKey publicKey = talkPublicKey.getAsNative();
        if(publicKey == null) {
            LOG.error("could not get public key for encryption");
            return;
        }

        LOG.trace("generating key");
        byte[] plainKey = AESCryptor.makeRandomBytes(32);

        TalkAttachment attachment = null;
        TalkClientUpload upload = clientMessage.getAttachmentUpload();
        if(upload != null) {
            LOG.info("generating attachment");

            upload.provideEncryptionKey(bytesToHex(plainKey));
            upload.performEncryption(mTransferAgent);
            upload.performRegistration(mTransferAgent);

            try {
                mDatabase.saveClientUpload(upload);
            } catch (SQLException e) {
                LOG.error("sql error", e);
            }

            attachment = new TalkAttachment();
            attachment.setUrl(upload.getDownloadUrl());
            attachment.setContentSize(Integer.toString(upload.getDataLength()));
            attachment.setMediaType(upload.getMediaType());
            attachment.setMimeType(upload.getContentType());
            attachment.setAspectRatio(upload.getAspectRatio());
        }

        try {

            LOG.trace("encrypting key");
            byte[] encryptedKey = RSACryptor.encryptRSA(publicKey, plainKey);
            delivery.setKeyId(talkPublicKey.getKeyId());
            delivery.setKeyCiphertext(Base64.encodeBase64String(encryptedKey));
            LOG.trace("encrypting body");
            byte[] encryptedBody = AESCryptor.encrypt(plainKey, AESCryptor.NULL_SALT, message.getBody().getBytes());
            message.setBody(Base64.encodeBase64String(encryptedBody));
            if(attachment != null) {
                LOG.info("encrypting attachment");
                LOG.info("attachment: " + mJsonMapper.writeValueAsString(attachment));
                byte[] encodedAttachment = mJsonMapper.writeValueAsBytes(attachment);
                byte[] encryptedAttachment = AESCryptor.encrypt(plainKey, AESCryptor.NULL_SALT, encodedAttachment);
                message.setAttachment(Base64.encodeBase64String(encryptedAttachment));
            }
        } catch (NoSuchPaddingException e) {
            LOG.error("error encrypting", e);
        } catch (NoSuchAlgorithmException e) {
            LOG.error("error encrypting", e);
        } catch (InvalidKeyException e) {
            LOG.error("error encrypting", e);
        } catch (BadPaddingException e) {
            LOG.error("error encrypting", e);
        } catch (IllegalBlockSizeException e) {
            LOG.error("error encrypting", e);
        } catch (InvalidAlgorithmParameterException e) {
            LOG.error("error encrypting", e);
        } catch (UnsupportedEncodingException e) {
            LOG.error("error encrypting", e);
        } catch (JsonProcessingException e) {
            LOG.error("error encrypting", e);
        }

        if(upload != null) {
            mTransferAgent.requestUpload(upload);
        }
    }

    private void updateClientPresence(TalkPresence presence) {
        LOG.debug("updateClientPresence(" + presence.getClientId() + ")");
        TalkClientContact clientContact = null;
        try {
            clientContact = mDatabase.findContactByClientId(presence.getClientId(), true);
        } catch (SQLException e) {
            e.printStackTrace();
            return;
        }

        clientContact.updatePresence(presence);

        boolean wantDownload = false;
        try {
            wantDownload = updateAvatarDownload(clientContact, presence.getAvatarUrl(), "c-" + presence.getClientId(), presence.getTimestamp());
        } catch (MalformedURLException e) {
            LOG.warn("malformed avatar url", e);
        }

        TalkClientDownload avatarDownload = clientContact.getAvatarDownload();
        try {
            if(avatarDownload != null) {
                mDatabase.saveClientDownload(avatarDownload);
            }
            mDatabase.savePresence(clientContact.getClientPresence());
            mDatabase.saveContact(clientContact);
        } catch (SQLException e) {
            e.printStackTrace();
        }
        if(avatarDownload != null && wantDownload) {
            mTransferAgent.requestDownload(avatarDownload);
        }

        final TalkClientContact fContact = clientContact;
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                requestClientKey(fContact);
            }
        });

        for(ITalkClientListener listener: mListeners) {
            listener.onClientPresenceChanged(clientContact);
        }
    }

    private boolean updateAvatarDownload(TalkClientContact contact, String avatarUrl, String avatarId, Date avatarTimestamp) throws MalformedURLException {
        boolean haveUrl = avatarUrl != null && !avatarUrl.isEmpty();
        if(!haveUrl) {
            LOG.warn("no avatar url for contact " + contact.getClientContactId());
            return false;
        }

        boolean wantDownload = false;
        TalkClientDownload avatarDownload = contact.getAvatarDownload();
        if(avatarDownload == null) {
            if(haveUrl) {
                LOG.info("new avatar for contact " + contact.getClientContactId());
                avatarDownload = new TalkClientDownload();
                avatarDownload.initializeAsAvatar(avatarUrl, avatarId, avatarTimestamp);
                wantDownload = true;
            }
        } else {
            try {
                mDatabase.refreshClientDownload(avatarDownload);
            } catch (SQLException e) {
                LOG.error("sql error", e);
                return false;
            }
            String downloadUrl = avatarDownload.getDownloadUrl();
            if(haveUrl) {
                if(downloadUrl == null || !downloadUrl.equals(avatarUrl)) {
                    LOG.info("new avatar for contact " + contact.getClientContactId());
                    LOG.info("o: " + downloadUrl);
                    LOG.info("n: " + avatarUrl);
                    avatarDownload = new TalkClientDownload();
                    avatarDownload.initializeAsAvatar(avatarUrl, avatarId, avatarTimestamp);
                    wantDownload = true;
                } else {
                    LOG.debug("avatar not changed for contact " + contact.getClientContactId());
                    TalkClientDownload.State state = avatarDownload.getState();
                    if(!state.equals(TalkClientDownload.State.COMPLETE) && !state.equals(TalkClientDownload.State.FAILED)) {
                        wantDownload = true;
                    }
                }
            }
        }

        if(avatarDownload != null) {
            contact.setAvatarDownload(avatarDownload);
        }

        return wantDownload;
    }

    private void requestClientKey(TalkClientContact client) {
        String clientId = client.getClientId();

        String currentKeyId = client.getClientPresence().getKeyId();
        if(currentKeyId == null || currentKeyId.isEmpty()) {
            LOG.warn("client " + clientId + " has no key id");
            return;
        }

        TalkKey clientKey = client.getPublicKey();
        if(clientKey != null) {
            if(clientKey.getKeyId().equals(currentKeyId)) {
                LOG.debug("client " + clientId + " has current key");
                return;
            }
        }

        LOG.debug("retrieving key " + currentKeyId + " for client " + clientId);
        TalkKey key = mServerRpc.getKey(client.getClientId(), currentKeyId);
        if(key != null) {
            try {
                client.setPublicKey(key);
                mDatabase.savePublicKey(key);
                mDatabase.saveContact(client);
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }

    private void updateClientRelationship(TalkRelationship relationship) {
        LOG.debug("updateClientRelationship(" + relationship.getOtherClientId() + ")");
        TalkClientContact clientContact = null;
        try {
            clientContact = mDatabase.findContactByClientId(relationship.getOtherClientId(), relationship.isRelated());
        } catch (SQLException e) {
            e.printStackTrace();
            return;
        }

        if(clientContact == null) {
            return;
        }

        clientContact.updateRelationship(relationship);

        try {
            mDatabase.saveRelationship(clientContact.getClientRelationship());
            mDatabase.saveContact(clientContact);
        } catch (SQLException e) {
            e.printStackTrace();
        }

        for(ITalkClientListener listener: mListeners) {
            listener.onClientRelationshipChanged(clientContact);
        }
    }

    private void updateGroupPresence(TalkGroup group) {
        LOG.debug("updateGroupPresence(" + group.getGroupId() + ")");
        TalkClientContact contact = null;
        try {
            contact = mDatabase.findContactByGroupId(group.getGroupId(), false);
            if(contact == null) {
                contact = mDatabase.findContactByGroupTag(group.getGroupTag());
            }
            if(contact == null) {
                contact = mDatabase.findContactByGroupId(group.getGroupId(), true);
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return;
        }

        if(contact == null) {
            return;
        }

        contact.updateGroupPresence(group);

        try {
            updateAvatarDownload(contact, group.getGroupAvatarUrl(), "g-" + group.getGroupId(), group.getLastChanged());
        } catch (MalformedURLException e) {
            LOG.warn("Malformed avatar URL", e);
        }

        TalkClientDownload avatarDownload = contact.getAvatarDownload();
        try {
            if(avatarDownload != null) {
                mDatabase.saveClientDownload(avatarDownload);
            }
            mDatabase.saveGroup(contact.getGroupPresence());
            mDatabase.saveContact(contact);
        } catch (SQLException e) {
            e.printStackTrace();
        }
        if(avatarDownload != null) {
            mTransferAgent.requestDownload(avatarDownload);
        }

        for(ITalkClientListener listener: mListeners) {
            listener.onGroupPresenceChanged(contact);
        }
    }

    public void updateGroupMember(TalkGroupMember member) {
        LOG.debug("updateGroupMember(" + member.getGroupId() + "/" + member.getClientId() + ")");
        TalkClientContact groupContact = null;
        TalkClientContact clientContact = null;
        try {
            clientContact = mDatabase.findContactByClientId(member.getClientId(), false);
            if(clientContact != null) {
                // XXX also when not self because of ordering ???
                boolean createGroup =
                        clientContact.isSelf()
                        && member.isInvolved()
                        && !member.isGroupRemoved();
                groupContact = mDatabase.findContactByGroupId(member.getGroupId(), createGroup);
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return;
        }
        if(clientContact == null) {
            LOG.warn("gm update for unknown client " + member.getClientId());
        }
        if(groupContact == null) {
            LOG.warn("gm update for unknown group " + member.getGroupId());
        }
        if(clientContact == null || groupContact == null) {
            return;
        }
        // if this concerns our own membership
        if(clientContact.isSelf()) {
            groupContact.updateGroupMember(member);
            try {
                mDatabase.saveGroupMember(groupContact.getGroupMember());
                mDatabase.saveContact(groupContact);
            } catch (SQLException e) {
                e.printStackTrace();
            }
            for(ITalkClientListener listener: mListeners) {
                listener.onGroupMembershipChanged(groupContact);
            }
        }
        // if this concerns the membership of someone else
        if(clientContact.isClient()) {
            LOG.info("Ignoring group member for other client");
        }
    }

    public void requestDownload(TalkClientDownload download) {
        mTransferAgent.requestDownload(download);
    }

    public void markAsSeen(final TalkClientMessage message) {
        resetIdle();
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                message.setSeen(true);
                try {
                    mDatabase.saveClientMessage(message);
                } catch (SQLException e) {
                    LOG.error("SQL error", e);
                }
                notifyUnseenMessages(false);
            }
        });
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
