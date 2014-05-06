package com.hoccer.talk.client;

import better.jsonrpc.client.JsonRpcClient;
import better.jsonrpc.client.JsonRpcClientException;
import better.jsonrpc.core.JsonRpcConnection;
import better.jsonrpc.server.JsonRpcServer;
import better.jsonrpc.websocket.JsonRpcWsClient;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hoccer.talk.client.model.TalkClientContact;
import com.hoccer.talk.client.model.TalkClientDownload;
import com.hoccer.talk.client.model.TalkClientMembership;
import com.hoccer.talk.client.model.TalkClientMessage;
import com.hoccer.talk.client.model.TalkClientSelf;
import com.hoccer.talk.client.model.TalkClientSmsToken;
import com.hoccer.talk.client.model.TalkClientUpload;
import com.hoccer.talk.crypto.AESCryptor;
import com.hoccer.talk.crypto.CryptoJSON;
import com.hoccer.talk.crypto.RSACryptor;
import com.hoccer.talk.model.*;
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
import java.nio.charset.Charset;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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
    protected IXoClientHost mClientHost;

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
    protected JsonRpcWsClient mConnection;
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

    Vector<IXoPairingListener> mPairingListeners = new Vector<IXoPairingListener>();
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

    // temporary group for geolocation grouping
    String mEnvironmentGroupId;
    AtomicBoolean mEnvironmentUpdateCallPending = new AtomicBoolean(false);

    int mRSAKeysize = 1024;

    /**
     * Create a Hoccer Talk client using the given client database
     */
    public XoClient(IXoClientHost host) {
        initialize(host);
    }

    public void initialize(IXoClientHost host) {
        // remember the host
        mClientHost = host;

        // fetch executor and db immediately
        mExecutor = host.getBackgroundExecutor();

        // create and initialize the database
        mDatabase = new XoClientDatabase(mClientHost.getDatabaseBackend());
        try {
            mDatabase.initialize();
        } catch (SQLException e) {
            LOG.error("sql error in database initialization", e);
        }

        // create URI object referencing the server
        URI uri = null;
        try {
            uri = new URI(mClientHost.getServerUri());
        } catch (URISyntaxException e) {
            LOG.error("uri is wrong", e);
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

        createJsonRpcClient(uri, wsClient, rpcMapper);

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

    protected void createJsonRpcClient(URI uri, WebSocketClient wsClient, ObjectMapper rpcMapper) {
        String protocol = XoClientConfiguration.USE_BSON_PROTOCOL
                ? XoClientConfiguration.PROTOCOL_STRING_BSON
                : XoClientConfiguration.PROTOCOL_STRING_JSON;
        mConnection = new JsonRpcWsClient(uri, protocol, wsClient, rpcMapper);
        mConnection.setMaxIdleTime(XoClientConfiguration.CONNECTION_IDLE_TIMEOUT);
        mConnection.setSendKeepAlives(XoClientConfiguration.KEEPALIVE_ENABLED);
        if(XoClientConfiguration.USE_BSON_PROTOCOL) {
            mConnection.setSendBinaryMessages(true);
        }
    }


    private void ensureSelfContact() {
        try {
            mSelfContact = mDatabase.findSelfContact(true);
            if(mSelfContact.initializeSelf()) {
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
        return mClientHost;
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
    public synchronized int getState() {
        return mState;
    }

    /**
     * @return the current state of this client (textual)
     */
    public synchronized String getStateString() {
        return stateToString(mState);
    }

    public synchronized void registerStateListener(IXoStateListener listener) {
        mStateListeners.add(listener);
    }

    public synchronized void unregisterStateListener(IXoStateListener listener) {
        mStateListeners.remove(listener);
    }

    public synchronized void registerContactListener(IXoContactListener listener) {
        mContactListeners.add(listener);
    }

    public synchronized void unregisterContactListener(IXoContactListener listener) {
        mContactListeners.remove(listener);
    }

    public synchronized void registerMessageListener(IXoMessageListener listener) {
        mMessageListeners.add(listener);
    }

    public synchronized void unregisterMessageListener(IXoMessageListener listener) {
        mMessageListeners.remove(listener);
    }

    public synchronized void registerUnseenListener(IXoUnseenListener listener) {
        mUnseenListeners.add(listener);
    }

    public synchronized void unregisterUnseenListener(IXoUnseenListener listener) {
        mUnseenListeners.remove(listener);
    }

    public synchronized void registerTransferListener(IXoTransferListener listener) {
        mTransferAgent.registerListener(listener);
    }

    public synchronized void unregisterTransferListener(IXoTransferListener listener) {
        mTransferAgent.unregisterListener(listener);
    }

    public synchronized void registerTokenListener(IXoTokenListener listener) {
        mTokenListeners.add(listener);
    }

    public synchronized void unregisterTokenListener(IXoTokenListener listener) {
        mTokenListeners.remove(listener);
    }

    public synchronized void registerPairingListener(IXoPairingListener listener) {
        mPairingListeners.add(listener);
    }

    public synchronized void unregisterPairingListener(IXoPairingListener listener) {
        mPairingListeners.remove(listener);
    }

    private void notifyUnseenMessages(boolean notify) {
        LOG.debug("notifyUnseenMessages()");
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
            // run transfer fixups on database in background
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    mTransferAgent.runFixups();
                }
            });
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
     * delete old key pair and create a new one
     */
    public void regenerateKeyPair() throws SQLException {
        LOG.debug("regenerateKeyPair()");
        mSelfContact.setPublicKey(null);
        mSelfContact.setPrivateKey(null);
        ensureSelfKey(mSelfContact);
    }

    public void hello() {
        try {
            TalkClientInfo clientInfo = new TalkClientInfo();
            clientInfo.setClientName(mClientHost.getClientName());
            clientInfo.setClientTime(mClientHost.getClientTime());
            clientInfo.setClientLanguage(mClientHost.getClientLanguage());
            clientInfo.setClientVersion(mClientHost.getClientVersionName());
            clientInfo.setClientBuildNumber(mClientHost.getClientVersionCode());
            clientInfo.setDeviceModel(mClientHost.getDeviceModel());
            clientInfo.setSystemName(mClientHost.getSystemName());
            clientInfo.setSystemLanguage(mClientHost.getSystemLanguage());
            clientInfo.setSystemVersion(mClientHost.getSystemVersion());
            if (mClientHost.isSupportModeEnabled()) {
                clientInfo.setSupportTag(mClientHost.getSupportTag());
            }

            LOG.debug("Hello: Saying hello to the server.");
            TalkServerInfo talkServerInfo = mServerRpc.hello(clientInfo);
            if (talkServerInfo != null) {
                LOG.debug("Hello: Current server time: " + talkServerInfo.getServerTime().toString());
                LOG.debug("Hello: Server switched to supportMode: " + talkServerInfo.isSupportMode());
                LOG.debug("Hello: Server version is '" + talkServerInfo.getVersion() + "'");
            }
        } catch (JsonRpcClientException e) {
            LOG.error("Error while sending Hello: ", e);
        }
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
                for (int i = 0; i < mContactListeners.size(); i++) {
                    IXoContactListener listener = mContactListeners.get(i);
                    listener.onClientPresenceChanged(mSelfContact);
                }
                sendPresence();
            }
        } catch (Exception e) {
            LOG.error("setClientString", e);
        }
    }

    public void setClientConnectionStatus(String newStatus) {
        try {
            TalkPresence presence = mSelfContact.getClientPresence();
            if(presence != null & presence.getClientId() != null) {
                if(newStatus != null && newStatus != presence.getClientStatus()) {
                    presence.setClientStatus(newStatus);
                    mSelfContact.updatePresence(presence);
                    mDatabase.savePresence(presence);
                    for (int i = 0; i < mContactListeners.size(); i++) {
                        IXoContactListener listener = mContactListeners.get(i);
                        listener.onClientPresenceChanged(mSelfContact);
                    }
                    sendPresence();
                }
            }
        } catch (SQLException e) {
            LOG.error("sql error", e);
        } catch (Exception e) { // TODO: specify exception in XoClientDatabase.savePresence!
            LOG.error("error in setClientConnectionStatus", e);
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
                    for (int i = 0; i < mContactListeners.size(); i++) {
                        IXoContactListener listener = mContactListeners.get(i);
                        listener.onClientPresenceChanged(mSelfContact);
                    }
                    LOG.debug("sending new presence");
                    sendPresence();
                } catch (Exception e) {
                    LOG.error("setClientAvatar", e);
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
               if (presence == null) {
                   LOG.error("group has no presence");
                   return;
               }
               presence.setGroupName(groupName);
               if (group.isGroupRegistered()) {
                   try {
                       mDatabase.saveGroup(presence);
                       mDatabase.saveContact(group);
                       LOG.debug("sending new group presence");
                       mServerRpc.updateGroup(presence);
                   } catch (SQLException e) {
                       LOG.error("sql error", e);
                   } catch (JsonRpcClientException e) {
                       LOG.error("Error while sending new group presence: " , e);
                   }
               }
               for (int i = 0; i < mContactListeners.size(); i++) {
                   IXoContactListener listener = mContactListeners.get(i);
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
                LOG.debug("setting and requesting upload");
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
                        } catch (JsonRpcClientException e) {
                            LOG.error("Error while sending new group presence: " , e);
                        }
                    }
                    mTransferAgent.requestUpload(upload);
                    LOG.debug("group presence update");
                    for (int i = 0; i < mContactListeners.size(); i++) {
                        IXoContactListener listener = mContactListeners.get(i);
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

        try {
            String token = mServerRpc.generateToken(tokenPurpose, tokenLifetime);
            LOG.debug("got pairing token " + token);
            return token;
        }  catch (JsonRpcClientException e) {
            LOG.error("Error while generating pairing token: ", e);
        }
        return null;
    }

    public void performTokenPairing(final String token) {
        resetIdle();
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                if (mServerRpc.pairByToken(token)) {
                    for (IXoPairingListener listener : mPairingListeners) {
                        listener.onTokenPairingSucceeded(token);
                    }
                } else {
                    for (IXoPairingListener listener : mPairingListeners) {
                        listener.onTokenPairingFailed(token);
                    }
                }
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

                    for (int i = 0; i < mContactListeners.size(); i++) {
                        IXoContactListener listener = mContactListeners.get(i);
                        listener.onContactRemoved(contact);
                    }

                    if(contact.isClient() && contact.isClientRelated()) {
                        mServerRpc.depairClient(contact.getClientId());
                    }

                    if(contact.isGroup()) {
                        if(contact.isGroupJoined() && !(contact.isGroupExisting() && contact.isGroupAdmin())) {
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
        LOG.debug("createGroup()");
        resetIdle();
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    LOG.debug("creating group");
                    TalkGroup groupPresence = contact.getGroupPresence();
                    TalkClientUpload avatarUpload = contact.getAvatarUpload();

                    contact.hackSetGroupPresence(null);
                    contact.setAvatarUpload(null);

                    try {
                        mDatabase.saveContact(contact);
                    } catch (SQLException e) {
                        LOG.error("SQL error", e);
                    }

                    LOG.debug("creating group on server");
                    String groupId = mServerRpc.createGroup(groupPresence);

                    if(groupId == null) {
                        return;
                    }

                    groupPresence.setGroupId(groupId);            // was null
                    groupPresence.setState(TalkGroup.STATE_NONE); // was null
                    contact.updateGroupId(groupId);
                    contact.updateGroupPresence(groupPresence);   // was missing

                    try {
                        mDatabase.saveGroup(groupPresence);
                        mDatabase.saveContact(contact);
                    } catch (SQLException e) {
                        LOG.error("sql error", e);
                    }

                    LOG.debug("new group contact " + contact.getClientContactId());

                    for (int i = 0; i < mContactListeners.size(); i++) {
                        IXoContactListener listener = mContactListeners.get(i);
                        listener.onContactAdded(contact);
                    }

                    if(avatarUpload != null) {
                        setGroupAvatar(contact, avatarUpload);
                    }

                } catch (JsonRpcClientException e) {
                    LOG.error("Error while creating group: ", e);
                }
            }
        });
    }

    public void inviteClientToGroup(final String groupId, final String clientId) {
        resetIdle();
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    mServerRpc.inviteGroupMember(groupId, clientId);
                }  catch (JsonRpcClientException e) {
                    LOG.error("Error while sending group invitation: " , e);
                }
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
        LOG.info("[connection #" + mConnection.getConnectionId() + "] state " + STATE_NAMES[mState] + " -> " + STATE_NAMES[newState] + " (" + message + ")");

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
            LOG.info("[connection #" + mConnection.getConnectionId() + "] scheduling requested reconnect");
            mConnectionFailures = 0;
            scheduleConnect(true);
            resetIdle();
        } else if(mState == STATE_CONNECTING) {
            if(previousState <= STATE_IDLE) {
                LOG.info("[connection #" + mConnection.getConnectionId() + "] scheduling connect");
                // initial connect
                mConnectionFailures = 0;
                scheduleConnect(false);
            } else {
                LOG.info("[connection #" + mConnection.getConnectionId() + "] scheduling reconnect");
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
                    LOG.info("[connection #" + mConnection.getConnectionId() + "] connected and ready");
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
        LOG.debug("performing connect on connection #" + mConnection.getConnectionId());
        try {
            mConnection.connect(XoClientConfiguration.CONNECT_TIMEOUT, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.warn("[connection #" + mConnection.getConnectionId() + "] exception while connecting: " + e.toString());
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
                        LOG.error("error sending keepalive", e);
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
            LOG.debug("registration not confirmed. Auto-registering.");
            //return;
            mSelfContact.updateSelfConfirmed();
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
                    LOG.debug("sync: HELLO");
                    hello();
                    LOG.debug("sync: updating presence");
                    ScheduledFuture sendPresenceFuture = sendPresence();
                    LOG.debug("sync: syncing presences");
                    TalkPresence[] presences = mServerRpc.getPresences(never);
                    for (TalkPresence presence : presences) {
                        updateClientPresence(presence);
                    }
                    LOG.debug("sync: syncing relationships");
                    TalkRelationship[] relationships = mServerRpc.getRelationships(never);
                    for (TalkRelationship relationship : relationships) {
                        updateClientRelationship(relationship);
                    }
                    LOG.debug("sync: syncing groups");
                    TalkGroup[] groups = mServerRpc.getGroups(never);
                    for (TalkGroup group : groups) {
                        if (group.getState().equals(TalkGroup.STATE_EXISTS)) {
                            updateGroupPresence(group);
                        }
                    }

                    LOG.debug("sync: syncing group memberships");
                    List<TalkClientContact> contacts = mDatabase.findAllGroupContacts();
                    List<TalkClientContact> groupContacts = new ArrayList<TalkClientContact>();
                    List<String> groupIds = new ArrayList<String>();
                    for (TalkClientContact contact : contacts) {
                        if (contact.isGroup()) {
                            groupContacts.add(contact);
                            groupIds.add(contact.getGroupId());
                        }
                    }
                    Boolean[] groupMembershipFlags = mServerRpc.isMemberInGroups(groupIds.toArray(new String[groupIds.size()]));

                    for (int i = 0; i < groupContacts.size(); i++) {
                        TalkClientContact groupContact = groupContacts.get(i);
                        try {
                            LOG.debug("sync: membership in group (" + groupContact.getGroupId() + ") : '" + groupMembershipFlags[i] + "'");

                            if (groupMembershipFlags[i]) {
                                TalkGroupMember[] members = mServerRpc.getGroupMembers(groupContact.getGroupId(), never);
                                for (TalkGroupMember member : members) {
                                    updateGroupMember(member);
                                }
                            } // TODO: check if else => delete contact?

                        } catch (JsonRpcClientException e) {
                            LOG.error("Error while updating group member: ", e);
                        } catch (RuntimeException e) {
                            LOG.error("Error while updating group members: ", e);
                        }
                    }

                    // ensure we are finished with generating pub/private keys before actually going active...
                    // TODO: have a proper statemachine
                    sendPresenceFuture.get();

                } catch (SQLException e) {
                    LOG.error("SQL Error while syncing: ", e);
                } catch (JsonRpcClientException e) {
                    LOG.error("Error while syncing: ", e);
                } catch (InterruptedException e) {
                    LOG.error("Error while asserting future", e);
                } catch (ExecutionException e) {
                    e.printStackTrace();
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
                    LOG.error("error disconnecting", t);
                }
                mDisconnectFuture = null;
            }
        }, 0, TimeUnit.SECONDS);
    }

    public TalkClientMessage composeClientMessage(TalkClientContact contact, String messageText) {
        return composeClientMessage(contact, messageText, null);
    }

    public TalkClientMessage composeClientMessage(TalkClientContact contact, String messageText, TalkClientUpload upload) {
        XoClientDatabase db = getDatabase();
        // construct message and delivery objects
        final TalkClientMessage clientMessage = new TalkClientMessage();
        final TalkMessage message = new TalkMessage();
        final TalkDelivery delivery = new TalkDelivery();

        final String messageTag = message.generateMessageTag();
        message.setBody(messageText);
        message.setSenderId(getSelfContact().getClientId());

        delivery.setMessageTag(messageTag);

        if (contact.isGroup()) {
            delivery.setGroupId(contact.getGroupId());
        }
        if (contact.isClient()) {
            delivery.setReceiverId(contact.getClientId());
        }

        clientMessage.markAsSeen();
        clientMessage.setText(messageText);
        clientMessage.setMessageTag(messageTag);
        clientMessage.setConversationContact(contact);
        clientMessage.setSenderContact(getSelfContact());
        clientMessage.setMessage(message);
        clientMessage.setOutgoingDelivery(delivery);

        if (upload != null) {
            clientMessage.setAttachmentUpload(upload);
        }

        try {
            if (upload != null) {
                db.saveClientUpload(upload);
            }
            db.saveMessage(message);
            db.saveDelivery(delivery);
            db.saveClientMessage(clientMessage);
        } catch (SQLException e) {
            LOG.error("sql error", e);
        }

        // log to help debugging
        LOG.debug("created message with id " + clientMessage.getClientMessageId() + " and tag " + message.getMessageTag());

        return clientMessage;
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

//        String saltString = Hex.encodeHexString(salt);
//        String secretString = Hex.encodeHexString(secret);
        String saltString = new String(Hex.encodeHex(salt));
        String secretString = new String(Hex.encodeHex(secret));

        try {
            String clientId = mServerRpc.generateId();

            LOG.debug("registration: started with id " + clientId);

            BigInteger verifier = vg.generateVerifier(salt, clientId.getBytes(), secret);

//        mServerRpc.srpRegister(verifier.toString(16), Hex.encodeHexString(salt));
            mServerRpc.srpRegister(verifier.toString(16), new String(Hex.encodeHex(salt)));

            LOG.debug("registration: finished");

            TalkClientSelf self = mSelfContact.getSelf();
            self.provideCredentials(saltString, secretString);
            selfContact.updateSelfRegistered(clientId);

            try {
                TalkPresence presence = ensureSelfPresence(mSelfContact);
                presence.setClientId(clientId);
                presence.setClientName(self.getRegistrationName());
                mDatabase.saveCredentials(self);
                mDatabase.savePresence(presence);
                mDatabase.saveContact(selfContact);
            } catch (SQLException e) {
                LOG.error("SQL error on performRegistration", e);
            } catch (Exception e) { // TODO: specify exception in XoClientDatabase.savePresence!
                LOG.error("error on performRegistration", e);
            }

        } catch (JsonRpcClientException e) {
            LOG.error("Error while performing registration: ", e);
        }

    }

    private byte[] extractCredentialsAsJson(TalkClientContact selfContact) throws RuntimeException {
        try {
            String clientId = selfContact.getClientId();
            TalkClientSelf self = selfContact.getSelf();

            //String saltString = new String(Base64.encodeBase64(Hex.decodeHex(self.getSrpSalt().toCharArray())));
            //byte[] secretString = new String(Base64.encodeBase64(Hex.decodeHex(self.getSrpSecret().toCharArray())));

            ObjectMapper jsonMapper = new ObjectMapper();
            ObjectNode rootNode = jsonMapper.createObjectNode();
            rootNode.put("password", self.getSrpSecret());
            rootNode.put("salt", self.getSrpSalt());
            rootNode.put("clientId", clientId);
            String jsonString = jsonMapper.writeValueAsString(rootNode);
            return jsonString.getBytes("UTF-8");
        } catch (Exception e) {
            LOG.error("decoder exception in extractCredentials", e);
            throw new RuntimeException("exception during extractCredentials", e);
        }
    }

    private byte[] makeCryptedCredentialsContainer(TalkClientContact selfContact, String containerPassword) throws Exception {
        byte[] credentials = extractCredentialsAsJson(selfContact);
        byte[] container = CryptoJSON.encryptedContainer(credentials, containerPassword, "credentials");
        return container;
    }

    private boolean setCryptedCredentialsFromContainer(TalkClientContact selfContact, byte[] jsonContainer, String containerPassword) {
        try {
            byte[] credentials = CryptoJSON.decryptedContainer(jsonContainer,containerPassword,"credentials");
            ObjectMapper jsonMapper = new ObjectMapper();
            JsonNode json = jsonMapper.readTree(credentials);
            if (json == null ||  !json.isObject()) {
                throw new Exception("setCryptedCredentialsFromContainer: not a json object");
            }
            JsonNode password = json.get("password");
            if (password == null) {
                throw new Exception("setCryptedCredentialsFromContainer: missing password");
            }
            JsonNode saltNode = json.get("salt");
            if (saltNode == null ) {
                throw new Exception("setCryptedCredentialsFromContainer: missing salt");
            }
            JsonNode clientIdNode = json.get("clientId");
            if (clientIdNode == null) {
                throw new Exception("parseEncryptedContainer: wrong or missing ciphered content");
            }
            TalkClientSelf self = selfContact.getSelf();
            self.provideCredentials(saltNode.asText(), password.asText());
            selfContact.updateSelfRegistered(clientIdNode.asText());
            return true;
        } catch (Exception e) {
            LOG.error("setCryptedCredentialsFromContainer", e);
        }
        return false;
    }

    public void testCredentialsContainer(TalkClientContact selfContact) {
        try {
            byte[] container = makeCryptedCredentialsContainer(selfContact,"12345678");
            String containerString = new String(container,"UTF-8");
            LOG.info(containerString);
            if (setCryptedCredentialsFromContainer(selfContact,container,"12345678")) {
                LOG.info("reading credentials from container succeeded");
            } else {
                LOG.info("reading credentials from container failed");

            }
        } catch (UnsupportedEncodingException e) {
            LOG.error("testCredentialsContainer", e);
        } catch (Exception e) {
            LOG.error("testCredentialsContainer", e);
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

//            String Vc = Hex.encodeHexString(vc.calculateVerifier());
            String Vc = new String(Hex.encodeHex(vc.calculateVerifier()));
            String Vs = mServerRpc.srpPhase2(Vc);
            vc.verifyServer(Hex.decodeHex(Vs.toCharArray()));
        } catch (JsonRpcClientException e) {
            LOG.error("Error while performing registration: ", e);
        } catch (Exception e) {
            LOG.error("decoder exception in login", e);
            throw new RuntimeException("exception during login", e);
        }
        // testCredentialsContainer(selfContact);
        LOG.debug("login: successful");
    }

    private void performDeliveries() {
        LOG.debug("performing deliveries");
        try {
            List<TalkClientMessage> clientMessages = mDatabase.findMessagesForDelivery();
            LOG.debug(clientMessages.size() + " to deliver");
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
                    encryptMessage(clientMessage, deliveries[i], messages[i]); //Encrypting here
                } catch (Throwable t) {
                    LOG.error("error encrypting", t);
                }
                i++;
            }
            for(i = 0; i < messages.length; i++) {
                LOG.debug("delivering " + i);
                TalkDelivery[] delivery = new TalkDelivery[1];
                delivery[0] = deliveries[i];
                TalkDelivery[] resultingDeliveries = new TalkDelivery[0];
                try {
                    resultingDeliveries = mServerRpc.deliveryRequest(messages[i], delivery);
                } catch (Exception ex) {
                    LOG.debug("Caught exception " + ex.getMessage());
                }
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
                presence.setClientStatus(TalkPresence.CONN_STATUS_ONLINE);
                presence.setTimestamp(new Date());
                contact.updatePresence(presence);
                mDatabase.savePresence(presence);
                mDatabase.saveContact(contact);
            }
            return presence;
        } catch (Exception e) {
            LOG.error("ensureSelfPresence", e);
            return null;
        }
    }

    private void ensureSelfKey(TalkClientContact contact) throws SQLException {
        LOG.debug("ensureSelfKey()");
        TalkKey publicKey = contact.getPublicKey();
        TalkPrivateKey privateKey = contact.getPrivateKey();
        if(publicKey == null || privateKey == null) {
            Date now = new Date();
            try {
                LOG.info("[connection #" + mConnection.getConnectionId() + "] generating new RSA keypair");

                mRSAKeysize = mClientHost.getRSAKeysize();
                LOG.debug("generating RSA keypair with size "+mRSAKeysize);
                KeyPair keyPair = RSACryptor.generateRSAKeyPair(mRSAKeysize);

                LOG.trace("unwrapping public key");
                PublicKey pubKey = keyPair.getPublic();
                byte[] pubEnc = RSACryptor.unwrapRSA_X509(pubKey.getEncoded());
//                String pubStr = Base64.encodeBase64String(pubEnc);
                String pubStr = new String(Base64.encodeBase64(pubEnc));

                LOG.trace("unwrapping private key");
                PrivateKey privKey = keyPair.getPrivate();
                byte[] privEnc = RSACryptor.unwrapRSA_PKCS8(privKey.getEncoded());
//                String privStr = Base64.encodeBase64String(privEnc);
                String privStr = new String(Base64.encodeBase64(privEnc));

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

                LOG.info("[connection #" + mConnection.getConnectionId() + "] generated new key with key id " + kid);

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

    private ScheduledFuture sendPresence() {
        LOG.debug("sendPresence()");
        return mExecutor.schedule(new Runnable() {
            @Override
            public void run() {
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
                    LOG.error("SQL error", e);
                } catch (JsonRpcClientException e) {
                    LOG.error("Error while sending presence: ", e);
                } catch (Exception e) { // TODO: specify own exception in XoClientDatabase.savePresence!
                    LOG.error("error in sendPresence", e);
                }
            }
        }, 0, TimeUnit.SECONDS);
    }

    public void sendEnvironmentUpdate(TalkEnvironment environment) {
        LOG.debug("sendEnvironmentUpdate()");
        if (this.getState() == STATE_ACTIVE && environment != null) {
            if (mEnvironmentUpdateCallPending.compareAndSet(false,true)) {

                final TalkEnvironment environmentToSend = environment;
                mExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            environmentToSend.setClientId(mSelfContact.getClientId());
                            environmentToSend.setGroupId(mEnvironmentGroupId);
                            mEnvironmentGroupId = mServerRpc.updateEnvironment(environmentToSend);
                        } catch (Throwable t) {
                            LOG.error("sendEnvironmentUpdate: other error", t);
                        }
                        mEnvironmentUpdateCallPending.set(false);
                    }
                });
            } else {
                LOG.debug("sendEnvironmentUpdate(): another update is still pending");
            }
        } else {
            LOG.debug("sendEnvironmentUpdate(): client not yet active or no environment");
        }
    }


    public void sendDestroyEnvironment(final String type) {
        if (this.getState() == STATE_ACTIVE) {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        mServerRpc.destroyEnvironment(type);
                    } catch (Throwable t) {
                        LOG.error("sendDestroyEnvironment: other error", t);
                    }
                }
            });
        }
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
            LOG.error("SQL error", e);
            return;
        }

        clientMessage.updateOutgoing(delivery);

        try {
            mDatabase.saveDelivery(clientMessage.getOutgoingDelivery());
            mDatabase.saveClientMessage(clientMessage);
        } catch (SQLException e) {
            LOG.error("SQL error", e);
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
            LOG.error("SQL error", e);
        }

        if(delivery.getState().equals(TalkDelivery.STATE_DELIVERING)) {
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    LOG.debug("confirming " + delivery.getMessageId());
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

        if (!message.getMessageTag().matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            byte[] hmac = message.computeHMAC();
            String hmacString = new String(Base64.encodeBase64(hmac));
            if (hmacString.equals(message.getMessageTag())) {
                LOG.info("HMAC ok");
            }  else {
                LOG.error("HMAC mismatch");
            }
        }

        // default message text
        // LOG.debug("Setting message's default body to empty string");
        clientMessage.setText("");

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
//                        decryptedKey = RSACryptor.decryptRSA(privateKey, Base64.decodeBase64(keyCiphertext));
                        decryptedKey = RSACryptor.decryptRSA(privateKey, Base64.decodeBase64(keyCiphertext.getBytes(Charset.forName("UTF-8"))));
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
//            decryptedKey = Base64.decodeBase64(contact.getGroupKey());
            decryptedKey = Base64.decodeBase64(contact.getGroupKey().getBytes(Charset.forName("UTF-8")));
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
//            byte[] decodedSalt = Base64.decodeBase64(keySalt);
            byte[] decodedSalt = Base64.decodeBase64(keySalt.getBytes(Charset.forName("UTF-8")));
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
//                decryptedBodyRaw = AESCryptor.decrypt(decryptedKey, AESCryptor.NULL_SALT, Base64.decodeBase64(rawBody));
                decryptedBodyRaw = AESCryptor.decrypt(decryptedKey, AESCryptor.NULL_SALT, Base64.decodeBase64(rawBody.getBytes(Charset.forName("UTF-8"))));
                decryptedBody = new String(decryptedBodyRaw, "UTF-8");
                //LOG.debug("determined decrypted body to be '" + decryptedBody + "'");
            }
            // decrypt attachment
            if(rawAttachment != null) {
//                decryptedAttachmentRaw = AESCryptor.decrypt(decryptedKey, AESCryptor.NULL_SALT, Base64.decodeBase64(rawAttachment));
                decryptedAttachmentRaw = AESCryptor.decrypt(decryptedKey, AESCryptor.NULL_SALT, Base64.decodeBase64(rawAttachment.getBytes(Charset.forName("UTF-8"))));
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
        if (decryptedBody != null) {
            //LOG.debug("Setting message's body to '" + decryptedBody + "' after decryption");
            clientMessage.setText(decryptedBody);
        }
        if(decryptedAttachment != null) {
            TalkClientDownload download = new TalkClientDownload();
            download.initializeAsAttachment(decryptedAttachment, message.getMessageId(), decryptedKey);
            clientMessage.setAttachmentDownload(download);
        }
    }

    private void encryptMessage(TalkClientMessage clientMessage, TalkDelivery delivery, TalkMessage message) {

        LOG.debug("encrypting message with id '" + clientMessage.getClientMessageId() + "'");

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

            // TODO: do not just log here! Raise! This can lead to plaintext being sent!
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
//                delivery.setKeyCiphertext(Base64.encodeBase64String(encryptedKey));
                delivery.setKeyCiphertext(new String(Base64.encodeBase64(encryptedKey)));
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
        } else { //TODO: should elseif for isGroup() and MUST add a check for isSelf() (i.e. for sending messages to yourself)
            LOG.trace("using group key for encryption");
            // get and decode the group key
            String groupKey = receiver.getGroupKey();
            if(groupKey == null) {
                LOG.warn("no group key");
                return;
            }
//            plainKey = Base64.decodeBase64(groupKey);
            plainKey = Base64.decodeBase64(groupKey.getBytes(Charset.forName("UTF-8")));
            // generate message-specific salt
            keySalt = AESCryptor.makeRandomBytes(AESCryptor.KEY_SIZE);
            // encode the salt for transmission
//            String encodedSalt = Base64.encodeBase64String(keySalt);
            String encodedSalt = new String(Base64.encodeBase64(keySalt));
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

//            upload.provideEncryptionKey(Hex.encodeHexString(plainKey));
            upload.provideEncryptionKey(new String(Hex.encodeHex(plainKey)));

            try {
                mDatabase.saveClientUpload(upload);
            } catch (SQLException e) {
                LOG.error("sql error", e);
            }

            LOG.debug("attachment download url is '" + upload.getDownloadUrl() + "'");
            attachment = new TalkAttachment();
            attachment.setFilename(upload.getFileName());
            attachment.setUrl(upload.getDownloadUrl());
            attachment.setContentSize(Integer.toString(upload.getDataLength()));
            attachment.setMediaType(upload.getMediaType());
            attachment.setMimeType(upload.getContentType());
            attachment.setAspectRatio(upload.getAspectRatio());
            attachment.setHmac(upload.getContentHmac());
        }

        // encrypt body and attachment dtor
        try {
            // encrypt body
            LOG.trace("encrypting body");
            byte[] encryptedBody = AESCryptor.encrypt(plainKey, AESCryptor.NULL_SALT, message.getBody().getBytes("UTF-8"));
//            message.setBody(Base64.encodeBase64String(encryptedBody));
            message.setBody(new String(Base64.encodeBase64(encryptedBody)));
            // encrypt attachment dtor
            if(attachment != null) {
                LOG.trace("encrypting attachment");
                byte[] encodedAttachment = mJsonMapper.writeValueAsBytes(attachment);
                byte[] encryptedAttachment = AESCryptor.encrypt(plainKey, AESCryptor.NULL_SALT, encodedAttachment);
//                message.setAttachment(Base64.encodeBase64String(encryptedAttachment));
                message.setAttachment(new String(Base64.encodeBase64(encryptedAttachment)));
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
        message.setTimeSent(new Date());
        byte[] hmac = message.computeHMAC();
        message.setMessageTag(new String(Base64.encodeBase64(hmac)));

    }

    private void updateClientPresence(TalkPresence presence) {
        LOG.debug("updateClientPresence(" + presence.getClientId() + ")");
        TalkClientContact clientContact = null;
        try {
            clientContact = mDatabase.findContactByClientId(presence.getClientId(), true);
        } catch (SQLException e) {
            LOG.error("SQL error", e);
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
        } catch (Exception e) {
            LOG.error("updateClientPresence", e);
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

        for (int i = 0; i < mContactListeners.size(); i++) {
            IXoContactListener listener = mContactListeners.get(i);
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

        try {
            LOG.debug("retrieving key " + currentKeyId + " for client " + clientId);
            TalkKey key = mServerRpc.getKey(client.getClientId(), currentKeyId);
            if (key != null) {
                try {
                    client.setPublicKey(key);
                    mDatabase.savePublicKey(key);
                    mDatabase.saveContact(client);
                } catch (SQLException e) {
                    LOG.error("SQL error", e);
                }
            }
        } catch (JsonRpcClientException e) {
            LOG.error("Error while retrieving key: ", e);
        }
    }

    private void updateClientRelationship(TalkRelationship relationship) {
        LOG.debug("updateClientRelationship(" + relationship.getOtherClientId() + ")");
        TalkClientContact clientContact = null;
        try {
            clientContact = mDatabase.findContactByClientId(relationship.getOtherClientId(), relationship.isRelated());
        } catch (SQLException e) {
            LOG.error("SQL error", e);
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
            LOG.error("SQL error", e);
        }

        for (int i = 0; i < mContactListeners.size(); i++) {
            IXoContactListener listener = mContactListeners.get(i);
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
            LOG.error("SQL error", e);
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
            LOG.error("SQL error", e);
        }
        if(avatarDownload != null) {
            mTransferAgent.requestDownload(avatarDownload);
        }

        for (int i = 0; i < mContactListeners.size(); i++) {
            IXoContactListener listener = mContactListeners.get(i);
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
                        LOG.debug("creating group for member in state '" + member.getState() + "' group '" + member.getGroupId() + "'");
                        groupContact = mDatabase.findContactByGroupId(member.getGroupId(), true);
                        needGroupUpdate = true;
                    }
                }
            }
        } catch (SQLException e) {
            LOG.error("SQL error", e);
            return;
        }
        if(clientContact == null) {
            LOG.warn("groupMemberUpdate for unknown client: " + member.getClientId());
        }
        if(groupContact == null) {
            LOG.warn("groupMemberUpdate for unknown group: " + member.getGroupId());
        }
        if(clientContact == null || groupContact == null) {
            return;
        }
        // if this concerns our own membership
        if(clientContact.isSelf()) {
            LOG.debug("groupMember is about us, decrypting group key");
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
                LOG.error("SQL error", e);
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

                /* Mark as nearby contact and save to database. */
                if (groupContact.getGroupPresence().isTypeNearby() && member.isJoined()) {
                    clientContact.setNearby(true);
                    mDatabase.saveContact(clientContact);
                } else {
                    clientContact.setNearby(false);
                    mDatabase.saveContact(clientContact);
                }

                membership.updateGroupMember(member);
                mDatabase.saveGroupMember(membership.getMember());
                mDatabase.saveClientMembership(membership);
            } catch (SQLException e) {
                LOG.error("sql error", e);
            }
        }

        for (int i = 0; i < mContactListeners.size(); i++) {
            IXoContactListener listener = mContactListeners.get(i);
            listener.onGroupMembershipChanged(groupContact);
            // TODO: ?? send onClientContactUpdated ??
        }

        if(needGroupUpdate) {
            LOG.debug("we now require a group update to retrieve presences");
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        TalkGroup[] groups = mServerRpc.getGroups(new Date(0));
                        for (TalkGroup group : groups) {
                            if (group.getState().equals(TalkGroup.STATE_EXISTS)) {
                                LOG.debug("updating group " + group.getGroupId());
                                updateGroupPresence(group);
                            }
                        }
                    } catch (JsonRpcClientException e) {
                        LOG.error("Error while getting groups: ", e);
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
//                    byte[] rawEncryptedGroupKey = Base64.decodeBase64(encryptedGroupKey);
                    byte[] rawEncryptedGroupKey = Base64.decodeBase64(encryptedGroupKey.getBytes(Charset.forName("UTF-8")));
                    byte[] rawGroupKey = RSACryptor.decryptRSA(privateKey, rawEncryptedGroupKey);
                    LOG.debug("successfully decrypted group key");
//                    String groupKey = Base64.encodeBase64String(rawGroupKey);
                    String groupKey = new String(Base64.encodeBase64(rawGroupKey));
                    group.setGroupKey(groupKey);
                }
            }
        } catch (SQLException e) {
            LOG.error("SQL error", e);
        } catch (IllegalBlockSizeException e) {
            LOG.error("error decrypting group key", e);
        } catch (InvalidKeyException e) {
            LOG.error("error decrypting group key", e);
        } catch (BadPaddingException e) {
            LOG.error("error decrypting group key", e);
        } catch (NoSuchAlgorithmException e) {
            LOG.error("error decrypting group key", e);
        } catch (NoSuchPaddingException e) {
            LOG.error("error decrypting group key", e);
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
//        group.setGroupKey(Base64.encodeBase64String(newGroupKey));
        group.setGroupKey(new String(Base64.encodeBase64(newGroupKey)));
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
//                            String encodedGroupKey = Base64.encodeBase64String(encryptedGroupKey);
                            String encodedGroupKey = new String(Base64.encodeBase64(encryptedGroupKey));
                            // send the key to the server for distribution

                            // TODO: use updateGroupKeys
                            //mServerRpc.updateGroupKey(group.getGroupId(), client.getClientId(), clientPubKey.getKeyId(), encodedGroupKey);

                            byte [] sharedKeyIdSalt = AESCryptor.makeRandomBytes(AESCryptor.KEY_SIZE);
                            String sharedKeyIdSaltString = new String(Base64.encodeBase64(sharedKeyIdSalt));
                            byte [] sharedKeyId = AESCryptor.calcSymmetricKeyId(newGroupKey,sharedKeyIdSalt);
                            String sharedKeyIdString = new String(Base64.encodeBase64(sharedKeyId));

                            mServerRpc.updateGroupKeys(group.getGroupId(), sharedKeyIdString, sharedKeyIdSaltString,
                                    new String[]{client.getClientId()},
                                    new String[]{clientPubKey.getKeyId()},
                                    new String[]{encodedGroupKey});
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
                    }  catch (JsonRpcClientException e) {
                        LOG.error("Error while updating group key: ", e);
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
                listener.onTokensChanged(tokens, notifyUser);
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
