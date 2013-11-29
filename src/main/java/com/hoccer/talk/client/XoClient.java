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
import com.hoccer.talk.client.model.TalkClientMembership;
import com.hoccer.talk.client.model.TalkClientMessage;
import com.hoccer.talk.client.model.TalkClientSelf;
import com.hoccer.talk.client.model.TalkClientSmsToken;
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
import com.j256.ormlite.dao.ForeignCollection;
import de.undercouch.bson4jackson.BsonFactory;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.binary.Hex;
import org.apache.log4j.Logger;
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
import java.util.Vector;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class XoClient implements JsonRpcConnection.Listener {

    private static final Logger LOG = Logger.getLogger(XoClient.class);

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

    /** Host of this client */
    IXoClientHost mHost;

    /* The database instance we use */
    XoClientDatabase mDatabase;

    /* Our own contact */
    TalkClientContact mSelfContact;

    /** Directory for avatar images */
    String mAvatarDirectory;
    /** Directory for received attachments */
    String mAttachmentDirectory;
    /** Directory for encrypted intermediate uploads */
    String mEncryptedUploadDirectory;
    /** Directory for encrypted intermediate downloads */
    String mEncryptedDownloadDirectory;

    XoTransferAgent mTransferAgent;

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

    Vector<IXoContactListener> mContactListeners = new Vector<IXoContactListener>();
    Vector<IXoMessageListener> mMessageListeners = new Vector<IXoMessageListener>();
    Vector<IXoStateListener> mStateListeners = new Vector<IXoStateListener>();
    Vector<IXoUnseenListener> mUnseenListeners = new Vector<IXoUnseenListener>();
    Vector<IXoTokenListener> mTokenListeners = new Vector<IXoTokenListener>();


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
	public XoClient(IXoClientHost host) {
        // remember the host
        mHost = host;

        // fetch executor and db immediately
        mExecutor = host.getBackgroundExecutor();

        // create and initialize the database
        mDatabase = new XoClientDatabase(mHost.getDatabaseBackend());
        try {
            mDatabase.initialize();
        } catch (SQLException e) {
            LOG.error("sql error in database initialization", e);
        }

        // create URI object referencing the server
        URI uri = null;
        try {
            uri = new URI(XoClientConfiguration.SERVER_URI);
        } catch (URISyntaxException e) {
            // won't happen
        }

        // create JSON object mapper
        JsonFactory jsonFactory = new JsonFactory();
        mJsonMapper = createObjectMapper(jsonFactory);

        // create RPC object mapper (BSON or JSON)
        JsonFactory rpcFactory;
        if(XoClientConfiguration.USE_BSON_PROTOCOL) {
            rpcFactory = new BsonFactory();
        } else {
            rpcFactory = jsonFactory;
        }
        ObjectMapper rpcMapper = createObjectMapper(rpcFactory);

        // create websocket client
        WebSocketClient wsClient = host.getWebSocketFactory().newWebSocketClient();

        // create json-rpc client
        String protocol = XoClientConfiguration.USE_BSON_PROTOCOL
                ? XoClientConfiguration.PROTOCOL_STRING_BSON
                : XoClientConfiguration.PROTOCOL_STRING_JSON;
        mConnection = new JsonRpcWsClient(uri, protocol, wsClient, rpcMapper);
        mConnection.setMaxIdleTime(XoClientConfiguration.CONNECTION_IDLE_TIMEOUT);
        mConnection.setSendKeepAlives(XoClientConfiguration.KEEPALIVE_ENABLED);
        if(XoClientConfiguration.USE_BSON_PROTOCOL) {
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
        mTransferAgent = new XoTransferAgent(this);

        // ensure we have a self contact
        ensureSelfContact();
    }

    private void ensureSelfContact() {
        try {
            mSelfContact = mDatabase.findSelfContact(true);
            if(mSelfContact.initializeSelf()) {
                mSelfContact.updateSelfConfirmed();
                mDatabase.saveCredentials(mSelfContact.getSelf());
                mDatabase.saveContact(mSelfContact);
            }
        } catch (SQLException e) {
            LOG.error("SQL error", e);
        }
    }

    public boolean isRegistered() {
        return mSelfContact.isSelfRegistered();
    }
    
    public TalkClientContact getSelfContact() {
        return mSelfContact;
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

    public IXoClientHost getHost() {
        return mHost;
    }

    public XoClientDatabase getDatabase() {
        return mDatabase;
    }

    public XoTransferAgent getTransferAgent() {
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

    public void registerStateListener(IXoStateListener listener) {
        mStateListeners.add(listener);
    }

    public void unregisterStateListener(IXoStateListener listener) {
        mStateListeners.remove(listener);
    }

    public void registerContactListener(IXoContactListener listener) {
        mContactListeners.add(listener);
    }

    public void unregisterContactListener(IXoContactListener listener) {
        mContactListeners.remove(listener);
    }

    public void registerMessageListener(IXoMessageListener listener) {
        mMessageListeners.add(listener);
    }

    public void unregisterMessageListener(IXoMessageListener listener) {
        mMessageListeners.remove(listener);
    }

    public void registerUnseenListener(IXoUnseenListener listener) {
        mUnseenListeners.add(listener);
    }

    public void unregisterUnseenListener(IXoUnseenListener listener) {
        mUnseenListeners.remove(listener);
    }

    public void registerTransferListener(IXoTransferListener listener) {
        mTransferAgent.registerListener(listener);
    }

    public void unregisterTransferListener(IXoTransferListener listener) {
        mTransferAgent.unregisterListener(listener);
    }

    public void registerTokenListener(IXoTokenListener listener) {
        mTokenListeners.add(listener);
    }

    public void unregisterTokenListener(IXoTokenListener listener) {
        mTokenListeners.remove(listener);
    }

    private void notifyUnseenMessages(boolean notify) {
        LOG.info("notifyUnseenMessages()");
        List<TalkClientMessage> unseenMessages = null;
        try {
            unseenMessages = mDatabase.findUnseenMessages();
        } catch (SQLException e) {
            LOG.error("SQL error", e);
        }
        for(IXoUnseenListener listener: mUnseenListeners) {
            listener.onUnseenMessages(unseenMessages, notify);
        }
    }

    public boolean isIdle() {
        return (System.currentTimeMillis() - mLastActivity) > (XoClientConfiguration.IDLE_TIMEOUT * 1000);
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
            TalkPresence presence = mSelfContact.getClientPresence();
            if(presence != null) {
                if(newName != null) {
                    presence.setClientName(newName);
                }
                if(newStatus != null) {
                    presence.setClientStatus(newStatus);
                }
                mDatabase.savePresence(presence);
                for(IXoContactListener listener: mContactListeners) {
                    listener.onClientPresenceChanged(mSelfContact);
                }
                sendPresence();
            }
        } catch (SQLException e) {
            LOG.error("sql error", e);
        }
    }

    public void setClientAvatar(final TalkClientUpload upload) {
        LOG.debug("new avatar as upload " + upload);
        resetIdle();
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                LOG.debug("registering client avatar");
                if(!upload.performRegistration(mTransferAgent, false)) {
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
                    TalkPresence presence = mSelfContact.getClientPresence();
                    if(presence != null) {
                        presence.setAvatarUrl(downloadUrl);
                    }
                    mSelfContact.setAvatarUpload(upload);
                    mDatabase.savePresence(presence);
                    mDatabase.saveContact(mSelfContact);
                    for(IXoContactListener listener: mContactListeners) {
                        listener.onClientPresenceChanged(mSelfContact);
                    }
                    LOG.debug("sending new presence");
                    sendPresence();
                } catch (SQLException e) {
                    LOG.error("sql error", e);
                }

            }
        });
    }

    public void setGroupName(final TalkClientContact group, final String groupName) {
       mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                LOG.debug("changing group name");
                TalkGroup presence = group.getGroupPresence();
                if(presence == null) {
                    LOG.error("group has no presence");
                    return;
                }
                presence.setGroupName(groupName);
                if(group.isGroupRegistered()) {
                    try {
                        mDatabase.saveGroup(presence);
                        mDatabase.saveContact(group);
                        LOG.debug("sending new group presence");
                        mServerRpc.updateGroup(presence);
                    } catch (SQLException e) {
                        LOG.error("sql error", e);
                    }
                }
                for(IXoContactListener listener: mContactListeners) {
                    listener.onGroupPresenceChanged(group);
                }
            }
       });
    }

    public void setGroupAvatar(final TalkClientContact group, final TalkClientUpload upload) {
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                LOG.debug("registering group avatar");
                if(!upload.performRegistration(mTransferAgent, false)) {
                    LOG.error("avatar registration failed");
                    return;
                }
                String downloadUrl = upload.getDownloadUrl();
                if(downloadUrl == null) {
                    LOG.error("registered avatar upload without download url");
                    return;
                }
                TalkGroup presence = group.getGroupPresence();
                if(presence == null) {
                    LOG.error("group has no presence");
                    return;
                }
                LOG.info("setting and requesting upload");
                try {
                    presence.setGroupAvatarUrl(downloadUrl);
                    group.setAvatarUpload(upload);
                    mDatabase.saveClientUpload(upload);
                    if(group.isGroupRegistered()) {
                        try {
                            mDatabase.saveGroup(presence);
                            mDatabase.saveContact(group);
                            LOG.debug("sending new group presence");
                            mServerRpc.updateGroup(presence);
                        } catch (SQLException e) {
                            LOG.error("sql error", e);
                        }
                    }
                    mTransferAgent.requestUpload(upload);
                    LOG.info("group presence update");
                    for(IXoContactListener listener: mContactListeners) {
                        listener.onGroupPresenceChanged(group);
                    }
                } catch (Exception e) {
                    LOG.error("error creating group avatar", e);
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

    public void performTokenPairing(final String token) {
        resetIdle();
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                mServerRpc.pairByToken(token);
            }
        });
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

                    for(IXoContactListener listener: mContactListeners) {
                        listener.onContactRemoved(contact);
                    }

                    if(contact.isClient() && contact.isClientRelated()) {
                        mServerRpc.depairClient(contact.getClientId());
                    }

                    if(contact.isGroup()) {
                        if(contact.isGroupJoined()) {
                            mServerRpc.leaveGroup(contact.getGroupId());
                        }
                        if(contact.isGroupExisting() && contact.isGroupAdmin()) {
                            mServerRpc.deleteGroup(contact.getGroupId());
                        }
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

    public void createGroup(final TalkClientContact contact) {
        LOG.info("createGroup()");
        resetIdle();
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    LOG.info("creating group");
                    TalkGroup groupPresence = contact.getGroupPresence();
                    TalkClientUpload avatarUpload = contact.getAvatarUpload();

                    contact.hackSetGroupPresence(null);
                    contact.setAvatarUpload(null);

                    try {
                        mDatabase.saveContact(contact);
                    } catch (SQLException e) {
                        LOG.error("SQL error", e);
                    }

                    LOG.info("creating group on server");
                    String groupId = mServerRpc.createGroup(groupPresence);

                    if(groupId == null) {
                        return;
                    }

                    contact.updateGroupId(groupId);

                    try {
                        mDatabase.saveGroup(groupPresence);
                        mDatabase.saveContact(contact);
                    } catch (SQLException e) {
                        LOG.error("sql error", e);
                    }

                    LOG.info("new group contact " + contact.getClientContactId());

                    for(IXoContactListener listener: mContactListeners) {
                        listener.onContactAdded(contact);
                    }

                    if(avatarUpload != null) {
                        setGroupAvatar(contact, avatarUpload);
                    }

                } catch (Throwable t) {
                    LOG.error("group creation error", t);
                }
            }
        });
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

    public void kickClientFromGroup(final String groupId, final String clientId) {
        resetIdle();
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                mServerRpc.removeGroupMember(groupId, clientId);
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

    public void requestDelivery(TalkClientMessage message) {
        for(IXoMessageListener listener: mMessageListeners) {
            listener.onMessageAdded(message);
        }
        requestDelivery();
    }

    private void requestDelivery() {
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
            for(IXoStateListener listener: mStateListeners) {
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
        if(isRegistered()) {
            switchState(STATE_LOGIN, "connected");
        } else {
            switchState(STATE_REGISTERING, "connected and unregistered");
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
            mConnection.connect(XoClientConfiguration.CONNECT_TIMEOUT, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.warn("exception while connecting: " + e.toString());
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
            }, XoClientConfiguration.IDLE_TIMEOUT, TimeUnit.SECONDS);
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
        if(XoClientConfiguration.KEEPALIVE_ENABLED) {
            mKeepAliveFuture = mExecutor.scheduleAtFixedRate(new Runnable() {
                    @Override
                    public void run() {
                        LOG.debug("performing keep-alive");
                        try {
                            mConnection.sendKeepAlive();
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                    }
                },
                XoClientConfiguration.KEEPALIVE_INTERVAL,
                XoClientConfiguration.KEEPALIVE_INTERVAL,
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
                    XoClientConfiguration.RECONNECT_BACKOFF_VARIABLE_MAXIMUM,
                    variableFactor * XoClientConfiguration.RECONNECT_BACKOFF_VARIABLE_FACTOR);

            // compute total backoff
            double totalTime = XoClientConfiguration.RECONNECT_BACKOFF_FIXED_DELAY + variableTime;

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
                performLogin(mSelfContact);
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
        if(!mSelfContact.getSelf().isRegistrationConfirmed()) {
            LOG.debug("registration not confirmed");
            return;
        }
        mRegistrationFuture = mExecutor.schedule(new Runnable() {
            @Override
            public void run() {
                try {
                    performRegistration(mSelfContact);
                    mRegistrationFuture = null;
                    switchState(STATE_LOGIN, "registered");
                } catch (Exception e) {
                    LOG.error("registration error", e);
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
                        if(group.getState().equals(TalkGroup.STATE_EXISTS)) {
                            updateGroupPresence(group);
                        }
                    }
                    LOG.debug("sync: syncing group memberships");
                    List<TalkClientContact> contacts = mDatabase.findAllGroupContacts();
                    for(TalkClientContact group: contacts) {
                        if(group.isGroup()) {
                            try {
                                TalkGroupMember[] members = mServerRpc.getGroupMembers(group.getGroupId(), never);
                                for(TalkGroupMember member: members) {
                                    updateGroupMember(member);
                                }
                            } catch (Throwable t) {
                                LOG.error("error updating group member", t);
                            }
                        }
                    }
                } catch (Throwable t) {
                    LOG.error("error during sync", t);
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
            // XXX
            //for(ITalkClientListener listener: mListeners) {
            //    listener.onPushRegistrationRequested();
            //}
        }

        @Override
		public void incomingDelivery(TalkDelivery d, TalkMessage m) {
			LOG.debug("server: incomingDelivery()");
            updateIncomingDelivery(d, m);
		}

		@Override
		public void outgoingDelivery(TalkDelivery d) {
			LOG.debug("server: outgoingDelivery()");
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

        String saltString = Hex.encodeHexString(salt);
        String secretString = Hex.encodeHexString(secret);

        String clientId = mServerRpc.generateId();

        LOG.debug("registration: started with id " + clientId);

        BigInteger verifier = vg.generateVerifier(salt, clientId.getBytes(), secret);

        mServerRpc.srpRegister(verifier.toString(16), Hex.encodeHexString(salt));

        LOG.debug("registration: finished");

        TalkClientSelf self = mSelfContact.getSelf();
        self.provideCredentials(saltString, secretString);

        selfContact.updateSelfRegistered(clientId);

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

        try {
            byte[] loginId = clientId.getBytes();
            byte[] loginSalt = Hex.decodeHex(self.getSrpSalt().toCharArray());
            byte[] loginSecret = Hex.decodeHex(self.getSrpSecret().toCharArray());
            BigInteger A = vc.generateClientCredentials(loginSalt, loginId, loginSecret);

            String Bs = mServerRpc.srpPhase1(clientId,  A.toString(16));
            vc.calculateSecret(new BigInteger(Bs, 16));

            LOG.debug("login: performing phase 2");

            String Vc = Hex.encodeHexString(vc.calculateVerifier());
            String Vs = mServerRpc.srpPhase2(Vc);
            vc.verifyServer(Hex.decodeHex(Vs.toCharArray()));
        } catch (Exception e) {
            LOG.error("decoder exception in login", e);
            throw new RuntimeException("exception during login", e);
        }

        LOG.debug("login: successful");
    }

    private void performDeliveries() {
        LOG.debug("performing deliveries");
        try {
            List<TalkClientMessage> clientMessages = mDatabase.findMessagesForDelivery();
            LOG.info(clientMessages.size() + " to deliver");
            TalkDelivery[] deliveries = new TalkDelivery[clientMessages.size()];
            TalkMessage[] messages = new TalkMessage[clientMessages.size()];
            int i = 0;
            for(TalkClientMessage clientMessage: clientMessages) {
                LOG.debug("preparing " + clientMessage.getClientMessageId());
                deliveries[i] = clientMessage.getOutgoingDelivery();
                messages[i] = clientMessage.getMessage();
                TalkClientUpload attachmentUpload = clientMessage.getAttachmentUpload();
                if(attachmentUpload != null) {
                    if(!attachmentUpload.performRegistration(mTransferAgent, true)) {
                        LOG.error("could not register attachment");
                    }
                }
                try {
                    encryptMessage(clientMessage, deliveries[i], messages[i]);
                } catch (Throwable t) {
                    LOG.error("error encrypting", t);
                }
                i++;
            }
            for(i = 0; i < messages.length; i++) {
                LOG.debug("delivering " + i);
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
                    TalkClientContact contact = mSelfContact;
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

        for(IXoMessageListener listener: mMessageListeners) {
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
            mTransferAgent.registerDownload(attachmentDownload);
        }

        for(IXoMessageListener listener: mMessageListeners) {
            if(newMessage) {
                listener.onMessageAdded(clientMessage);
            } else {
                listener.onMessageStateChanged(clientMessage);
            }
        }

        notifyUnseenMessages(newMessage);
    }

    private void decryptMessage(TalkClientMessage clientMessage, TalkDelivery delivery, TalkMessage message) {
        LOG.debug("decryptMessage()");

        // contact (provides decryption context)
        TalkClientContact contact = clientMessage.getConversationContact();

        // default message text
        clientMessage.setText("<Unreadable>");

        // get various fields
        String keyId = delivery.getKeyId();
        String keyCiphertext = delivery.getKeyCiphertext();
        String keySalt = message.getSalt();
        String rawBody = message.getBody();
        String rawAttachment = message.getAttachment();

        // get decryption key
        byte[] decryptedKey = null;
        if(contact.isClient()) {
            LOG.trace("decrypting using private key");
            // decrypt the provided key using our private key
            try {
                TalkPrivateKey talkPrivateKey = null;
                talkPrivateKey = mDatabase.findPrivateKeyByKeyId(keyId);
                if(talkPrivateKey == null) {
                    LOG.error("no private key for keyId " + keyId);
                    return;
                } else {
                    PrivateKey privateKey = talkPrivateKey.getAsNative();
                    if(privateKey == null) {
                        LOG.error("could not decode private key");
                        return;
                    } else {
                        decryptedKey = RSACryptor.decryptRSA(privateKey, Base64.decodeBase64(keyCiphertext));
                    }
                }
            } catch (SQLException e) {
                LOG.error("sql error", e);
                return;
            } catch (IllegalBlockSizeException e) {
                LOG.error("decryption error", e);
                return;
            } catch (InvalidKeyException e) {
                LOG.error("decryption error", e);
                return;
            } catch (BadPaddingException e) {
                LOG.error("decryption error", e);
                return;
            } catch (NoSuchAlgorithmException e) {
                LOG.error("decryption error", e);
                return;
            } catch (NoSuchPaddingException e) {
                LOG.error("decryption error", e);
                return;
            }
        } else if(contact.isGroup()) {
            LOG.trace("decrypting using group key");
            // get the group key for decryption
            String groupKey = contact.getGroupKey();
            if(groupKey == null) {
                LOG.warn("no group key");
                return;
            }
            decryptedKey = Base64.decodeBase64(contact.getGroupKey());
        } else {
            LOG.error("don't know how to decrypt messages from contact of type " + contact.getContactType());
            return;
        }

        // check that we have a key
        if(decryptedKey == null) {
            LOG.error("could not determine decryption key");
            return;
        }

        // apply salt if present
        if(keySalt != null) {
            byte[] decodedSalt = Base64.decodeBase64(keySalt);
            if(decodedSalt.length != decryptedKey.length) {
                LOG.error("message salt has wrong size");
                return;
            }
            for(int i = 0; i < decryptedKey.length; i++) {
                decryptedKey[i] = (byte)(decryptedKey[i] ^ decodedSalt[i]);
            }
        }

        // decrypt both body and attachment dtor
        byte[] decryptedBodyRaw;
        String decryptedBody = "";
        byte[] decryptedAttachmentRaw;
        TalkAttachment decryptedAttachment = null;
        try {
            // decrypt body
            if(rawBody != null) {
                decryptedBodyRaw = AESCryptor.decrypt(decryptedKey, AESCryptor.NULL_SALT, Base64.decodeBase64(rawBody));
                decryptedBody = new String(decryptedBodyRaw, "UTF-8");
            }
            // decrypt attachment
            if(rawAttachment != null) {
                decryptedAttachmentRaw = AESCryptor.decrypt(decryptedKey, AESCryptor.NULL_SALT, Base64.decodeBase64(rawAttachment));
                decryptedAttachment = mJsonMapper.readValue(decryptedAttachmentRaw, TalkAttachment.class);
            }
        } catch (NoSuchPaddingException e) {
            LOG.error("error decrypting", e);
            return;
        } catch (NoSuchAlgorithmException e) {
            LOG.error("error decrypting", e);
            return;
        } catch (InvalidKeyException e) {
            LOG.error("error decrypting", e);
            return;
        } catch (BadPaddingException e) {
            LOG.error("error decrypting", e);
            return;
        } catch (IllegalBlockSizeException e) {
            LOG.error("error decrypting", e);
            return;
        } catch (InvalidAlgorithmParameterException e) {
            LOG.error("error decrypting", e);
            return;
        } catch (IOException e) {
            LOG.error("error decrypting", e);
            return;
        }

        // add decrypted information to message
        if(decryptedBody != null) {
            clientMessage.setText(decryptedBody);
        }
        if(decryptedAttachment != null) {
            TalkClientDownload download = new TalkClientDownload();
            download.initializeAsAttachment(decryptedAttachment, message.getMessageId(), decryptedKey);
            clientMessage.setAttachmentDownload(download);
        }
    }

    private void encryptMessage(TalkClientMessage clientMessage, TalkDelivery delivery, TalkMessage message) {

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
            return;
        }

        byte[] plainKey = null;
        byte[] keySalt = null;
        if(receiver.isClient()) {
            LOG.trace("using client key for encryption");
            // generate message key
            plainKey = AESCryptor.makeRandomBytes(AESCryptor.KEY_SIZE);
            // get public key for encrypting the key
            TalkKey talkPublicKey = receiver.getPublicKey();
            if(talkPublicKey == null) {
                LOG.error("no pubkey for encryption");
                return;
            }
            // retrieve native version of the key
            PublicKey publicKey = talkPublicKey.getAsNative();
            if(publicKey == null) {
                LOG.error("could not get public key for encryption");
                return;
            }
            // encrypt the message key
            try {
                byte[] encryptedKey = RSACryptor.encryptRSA(publicKey, plainKey);
                delivery.setKeyId(talkPublicKey.getKeyId());
                delivery.setKeyCiphertext(Base64.encodeBase64String(encryptedKey));
            } catch (NoSuchPaddingException e) {
                LOG.error("error encrypting", e);
                return;
            } catch (NoSuchAlgorithmException e) {
                LOG.error("error encrypting", e);
                return;
            } catch (InvalidKeyException e) {
                LOG.error("error encrypting", e);
                return;
            } catch (BadPaddingException e) {
                LOG.error("error encrypting", e);
                return;
            } catch (IllegalBlockSizeException e) {
                LOG.error("error encrypting", e);
                return;
            }
        } else {
            LOG.trace("using group key for encryption");
            // get and decode the group key
            String groupKey = receiver.getGroupKey();
            if(groupKey == null) {
                LOG.warn("no group key");
                return;
            }
            plainKey = Base64.decodeBase64(groupKey);
            // generate message-specific salt
            keySalt = AESCryptor.makeRandomBytes(AESCryptor.KEY_SIZE);
            // encode the salt for transmission
            String encodedSalt = Base64.encodeBase64String(keySalt);
            message.setSalt(encodedSalt);
        }

        // apply salt if present
        if(keySalt != null) {
            if(keySalt.length != plainKey.length) {
                LOG.error("message salt has wrong size");
                return;
            }
            for(int i = 0; i < plainKey.length; i++) {
                plainKey[i] = (byte)(plainKey[i] ^ keySalt[i]);
            }
        }

        // initialize attachment upload
        TalkAttachment attachment = null;
        TalkClientUpload upload = clientMessage.getAttachmentUpload();
        if(upload != null) {
            LOG.debug("generating attachment");

            upload.provideEncryptionKey(Hex.encodeHexString(plainKey));

            try {
                mDatabase.saveClientUpload(upload);
            } catch (SQLException e) {
                LOG.error("sql error", e);
            }

            LOG.debug("attachment download url is " + upload.getDownloadUrl());

            attachment = new TalkAttachment();
            attachment.setUrl(upload.getDownloadUrl());
            attachment.setContentSize(Integer.toString(upload.getDataLength()));
            attachment.setMediaType(upload.getMediaType());
            attachment.setMimeType(upload.getContentType());
            attachment.setAspectRatio(upload.getAspectRatio());
        }

        // encrypt body and attachment dtor
        try {
            // encrypt body
            LOG.trace("encrypting body");
            byte[] encryptedBody = AESCryptor.encrypt(plainKey, AESCryptor.NULL_SALT, message.getBody().getBytes());
            message.setBody(Base64.encodeBase64String(encryptedBody));
            // encrypt attachment dtor
            if(attachment != null) {
                LOG.trace("encrypting attachment");
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

        // start the attachment upload
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

        if(clientContact.isSelf()) {
            LOG.warn("server sent self-presence due to group presence bug, ignoring");
            return;
        }

        if(!clientContact.isClient()) {
            LOG.warn("contact is not a client contact!? " + clientContact.getContactType());
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

        for(IXoContactListener listener: mContactListeners) {
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
                LOG.debug("new avatar for contact " + contact.getClientContactId());
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
                    LOG.debug("new avatar for contact " + contact.getClientContactId());
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

        for(IXoContactListener listener: mContactListeners) {
            listener.onClientRelationshipChanged(clientContact);
        }
    }

    private void updateGroupPresence(TalkGroup group) {
        LOG.debug("updateGroupPresence(" + group.getGroupId() + ")");

        TalkClientContact contact = null;
        try {
            contact = mDatabase.findContactByGroupTag(group.getGroupTag());
            if(contact == null) {
                contact = mDatabase.findContactByGroupId(group.getGroupId(), true);
            }
        } catch (SQLException e) {
            e.printStackTrace();
            return;
        }

        if(contact == null) {
            LOG.warn("gp update for unknown group " + group.getGroupId());
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

        for(IXoContactListener listener: mContactListeners) {
            listener.onGroupPresenceChanged(contact);
        }
    }

    public void updateGroupMember(TalkGroupMember member) {
        updateGroupMember(member, false);
    }

    public void updateGroupMember(TalkGroupMember member, boolean alwaysRenew) {
        LOG.debug("updateGroupMember(" + member.getGroupId() + "/" + member.getClientId() + ")");
        TalkClientContact groupContact = null;
        TalkClientContact clientContact = null;
        boolean needGroupUpdate = false;
        boolean needRenewal = alwaysRenew;
        try {
            clientContact = mDatabase.findContactByClientId(member.getClientId(), false);
            if(clientContact != null) {
                groupContact = mDatabase.findContactByGroupId(member.getGroupId(), false);
                if(groupContact == null) {
                    boolean createGroup =
                            clientContact.isSelf()
                            && member.isInvolved();
                    if(createGroup) {
                        LOG.debug("creating group for member in state " + member.getState() + " group " + member.getGroupId());
                        groupContact = mDatabase.findContactByGroupId(member.getGroupId(), true);
                        needGroupUpdate = true;
                    }
                }
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
            LOG.debug("gm is about us, decrypting group key");
            groupContact.updateGroupMember(member);
            decryptGroupKey(groupContact, member);
            if(groupContact.isGroupAdmin()) {
                if(member.getEncryptedGroupKey() == null || member.getMemberKeyId() == null) {
                    LOG.debug("we have no key, renewing");
                    needRenewal = true;
                }
            }
            try {
                mDatabase.saveGroupMember(groupContact.getGroupMember());
                mDatabase.saveContact(groupContact);
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
        // if this concerns the membership of someone else
        if(clientContact.isClient()) {
            try {
                TalkClientMembership membership = mDatabase.findMembershipByContacts(
                        groupContact.getClientContactId(), clientContact.getClientContactId(), true);
                TalkGroupMember oldMember = membership.getMember();
                LOG.debug("old member " + ((oldMember == null) ? "null" : "there"));
                if(oldMember != null) {
                    LOG.debug("old " + oldMember.getState() + " new " + member.getState());
                }
                if(groupContact.isGroupAdmin()) {
                    if(oldMember == null) {
                        LOG.debug("client is new, renewing");
                        needRenewal = true;
                    }
                    if(oldMember != null && !oldMember.isJoined()) {
                        LOG.debug("client is newly joined, renewing");
                        needRenewal = true;
                    }
                    if(member.getEncryptedGroupKey() == null || member.getMemberKeyId() == null) {
                        LOG.debug("client has no key, renewing");
                        needRenewal = true;
                    }
                }
                membership.updateGroupMember(member);
                mDatabase.saveGroupMember(membership.getMember());
                mDatabase.saveClientMembership(membership);
            } catch (SQLException e) {
                LOG.error("sql error", e);
            }
        }

        for(IXoContactListener listener: mContactListeners) {
            listener.onGroupMembershipChanged(groupContact);
        }

        if(needGroupUpdate) {
            LOG.debug("we now require a group update to retrieve presences");
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    TalkGroup[] groups = mServerRpc.getGroups(new Date(0));
                    for(TalkGroup group: groups) {
                        if(group.getState().equals(TalkGroup.STATE_EXISTS)) {
                            LOG.debug("updating group " + group.getGroupId());
                            updateGroupPresence(group);
                        }
                    }
                }
            });
        }

        if(needRenewal && groupContact.isGroupAdmin()) {
            LOG.debug("initiating key renewal");
            final TalkClientContact finalGroup = groupContact;
            mExecutor.schedule(new Runnable() {
                @Override
                public void run() {
                    renewGroupKey(finalGroup);
                }
            }, 500, TimeUnit.MILLISECONDS);
        }
    }

    private void decryptGroupKey(TalkClientContact group, TalkGroupMember member) {
        LOG.debug("decrypting group key");
        String keyId = member.getMemberKeyId();
        String encryptedGroupKey = member.getEncryptedGroupKey();
        if(keyId == null || encryptedGroupKey == null) {
            LOG.warn("can't decrypt group key because there isn't one yet");
            return;
        }
        try {
            TalkPrivateKey talkPrivateKey = mDatabase.findPrivateKeyByKeyId(keyId);
            if(talkPrivateKey == null) {
                LOG.error("no private key for keyId " + keyId);
            } else {
                PrivateKey privateKey = talkPrivateKey.getAsNative();
                if(privateKey == null) {
                    LOG.error("could not decode private key");
                } else {
                    byte[] rawEncryptedGroupKey = Base64.decodeBase64(encryptedGroupKey);
                    byte[] rawGroupKey = RSACryptor.decryptRSA(privateKey, rawEncryptedGroupKey);
                    LOG.debug("successfully decrypted group key");
                    String groupKey = Base64.encodeBase64String(rawGroupKey);
                    group.setGroupKey(groupKey);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        } catch (IllegalBlockSizeException e) {
            e.printStackTrace();
        } catch (InvalidKeyException e) {
            e.printStackTrace();
        } catch (BadPaddingException e) {
            e.printStackTrace();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        } catch (NoSuchPaddingException e) {
            e.printStackTrace();
        }
    }

    private void renewGroupKey(TalkClientContact group) {
        LOG.debug("renewing group key for group contact " + group.getClientContactId());

        if(!group.isGroupAdmin()) {
            LOG.warn("we are not admin, should not and can't renew group key");
            return;
        }

        // generate the new key
        byte[] newGroupKey = AESCryptor.makeRandomBytes(AESCryptor.KEY_SIZE);
        // remember the group key for ourselves
        group.setGroupKey(Base64.encodeBase64String(newGroupKey));
        // distribute the group key
        ForeignCollection<TalkClientMembership> memberships = group.getGroupMemberships();
        if(memberships != null) {
            LOG.debug("will send key to " + memberships.size() + " members");
            for(TalkClientMembership membership: memberships) {
                TalkGroupMember member = membership.getMember();
                if(member != null && member.isJoined()) {
                    LOG.debug("joined member contact " + membership.getClientContact().getClientContactId());
                    try {
                        TalkClientContact client = mDatabase.findClientContactById(membership.getClientContact().getClientContactId());
                        LOG.debug("encrypting new group key for client contact " + client.getClientContactId());
                        TalkKey clientPubKey = client.getPublicKey();
                        if(clientPubKey == null) {
                            LOG.warn("no public key for client contact " + client.getClientContactId());
                        } else {
                            // encrypt and encode key for client
                            PublicKey clientKey = clientPubKey.getAsNative();
                            byte[] encryptedGroupKey = RSACryptor.encryptRSA(clientKey, newGroupKey);
                            String encodedGroupKey = Base64.encodeBase64String(encryptedGroupKey);
                            // send the key to the server for distribution
                            mServerRpc.updateGroupKey(group.getGroupId(), client.getClientId(), clientPubKey.getKeyId(), encodedGroupKey);
                        }
                    } catch (SQLException e) {
                        LOG.error("sql error", e);
                    } catch (IllegalBlockSizeException e) {
                        LOG.error("encryption error", e);
                    } catch (InvalidKeyException e) {
                        LOG.error("encryption error", e);
                    } catch (BadPaddingException e) {
                        LOG.error("encryption error", e);
                    } catch (NoSuchAlgorithmException e) {
                        LOG.error("encryption error", e);
                    } catch (NoSuchPaddingException e) {
                        LOG.error("encryption error", e);
                    }
                }
            }
        }

        // save the new group key
        try {
            mDatabase.saveContact(group);
        } catch (SQLException e) {
            LOG.error("sql error", e);
        }
    }

    public void requestDownload(TalkClientDownload download) {
        mTransferAgent.requestDownload(download);
    }

    public void cancelDownload(TalkClientDownload download) {
        mTransferAgent.cancelDownload(download);
    }

    public void handleSmsUrl(final String sender, final String body, final String urlString) {
        LOG.debug("handleSmsUrl(" + sender + "," + urlString + ")");
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                // check if the url is for a pairing token
                if(urlString.startsWith("hxo://")) {
                    String token = urlString.substring(6);
                    // build new token object
                    TalkClientSmsToken tokenObject = new TalkClientSmsToken();
                    tokenObject.setSender(sender);
                    tokenObject.setToken(token);
                    tokenObject.setBody(body);
                    try {
                        mDatabase.saveSmsToken(tokenObject);
                    } catch (SQLException e) {
                        LOG.error("sql error", e);
                    }
                    // call listeners
                    notifySmsTokensChanged(true);
                }
            }
        });
    }

    private void notifySmsTokensChanged(boolean notifyUser) {
        try {
            List<TalkClientSmsToken> tokens = mDatabase.findAllSmsTokens();
            for(IXoTokenListener listener: mTokenListeners) {
                listener.onTokensChanged(tokens, true);
            }
        } catch (SQLException e) {
            LOG.error("sql error", e);
        }
    }

    public void useSmsToken(final TalkClientSmsToken token) {
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                performTokenPairing(token.getToken());
                try {
                    mDatabase.deleteSmsToken(token);
                } catch (SQLException e) {
                    LOG.error("sql error", e);
                }
                notifySmsTokensChanged(false);
            }
        });
    }

    public void rejectSmsToken(final TalkClientSmsToken token) {
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    mDatabase.deleteSmsToken(token);
                } catch (SQLException e) {
                    LOG.error("sql error", e);
                }
                notifySmsTokensChanged(false);
            }
        });
    }

    public void markAsSeen(final TalkClientMessage message) {
        resetIdle();
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                message.markAsSeen();
                try {
                    mDatabase.saveClientMessage(message);
                } catch (SQLException e) {
                    LOG.error("SQL error", e);
                }
                notifyUnseenMessages(false);
            }
        });
    }

    public void register() {
        if(!isRegistered()) {
            if(mState == STATE_REGISTERING) {
                scheduleRegistration();
            } else {
                wake();
            }
        }
    }
	
}
