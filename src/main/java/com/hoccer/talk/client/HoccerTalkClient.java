package com.hoccer.talk.client;

import java.math.BigInteger;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.sql.SQLException;
import java.util.Date;
import java.util.List;
import java.util.UUID;
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
import com.hoccer.talk.client.model.TalkClientContact;
import com.hoccer.talk.client.model.TalkClientMessage;
import com.hoccer.talk.client.model.TalkClientSelf;
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
    /** State of synchronization after login */
    public static final int STATE_SYNCING = 4;
    /** State while there is an active connection */
    public static final int STATE_ACTIVE = 5;

    /** Digest instance used for SRP auth */
    private final Digest SRP_DIGEST = new SHA256Digest();
    /** RNG used for SRP auth */
    private static final SecureRandom SRP_RANDOM = new SecureRandom();
    /** Constant SRP parameters */
    private static final SRP6Parameters SRP_PARAMETERS = SRP6Parameters.CONSTANTS_1024;

    /** Names of our states for debugging */
    private static final String[] STATE_NAMES = {
            "inactive", "idle", "connecting", "connected", "syncing", "active"
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

    ITalkClientDatabaseBackend mDatabaseBackend;
    TalkClientDatabase mDatabase;
	
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
     */
	public HoccerTalkClient(ScheduledExecutorService backgroundExecutor, ITalkClientDatabaseBackend databaseBackend) {
        // remember client database and background executor
        mExecutor = backgroundExecutor;
        mDatabaseBackend = databaseBackend;

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

    public TalkClientDatabase getDatabase() {
        return mDatabase;
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
        LOG.debug("client: activate()");
        if(mState == STATE_INACTIVE) {
            switchState(STATE_IDLE, "client activated");
        }
    }

    public void deactivate() {
        LOG.debug("client: deactivate()");
        if(mState != STATE_INACTIVE) {
            switchState(STATE_INACTIVE, "client deactivated");
        }
    }

    public void wake() {
        LOG.debug("client: wake()");
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
        LOG.debug("client: deactivateNow()");
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

    public void setClientName(String newName) {
        try {
            TalkClientContact contact = mDatabase.findSelfContact(false);
            if(contact != null) {
                TalkPresence presence = contact.getClientPresence();
                if(presence != null) {
                    presence.setClientName(newName);
                    mDatabase.savePresence(presence);
                    sendPresence();
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void setClientStatus(String newStatus) {
        try {
            TalkClientContact contact = mDatabase.findSelfContact(false);
            if(contact != null) {
                TalkPresence presence = contact.getClientPresence();
                if(presence != null) {
                    presence.setClientStatus(newStatus);
                    mDatabase.savePresence(presence);
                    sendPresence();
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public String generatePairingToken() {
        String tokenPurpose = TalkToken.PURPOSE_PAIRING;
        int tokenLifetime = 7 * 24 * 3600;
        String token = mServerRpc.generateToken(tokenPurpose, tokenLifetime);
        LOG.info("got pairing token " + token);
        return token;
    }

    public void pairByToken(String token) {
        LOG.info("trying to pair using token " + token);
        mServerRpc.pairByToken(token);
    }

    public TalkClientContact createGroup() {
        String groupTag = UUID.randomUUID().toString();
        TalkClientContact contact = new TalkClientContact(TalkClientContact.TYPE_GROUP);
        contact.updateGroupTag(groupTag);
        TalkGroup groupPresence = new TalkGroup();
        groupPresence.setGroupTag(groupTag);
        groupPresence.setGroupName("Group");
        contact.updateGroupPresence(groupPresence);
        String groupId = mServerRpc.createGroup(groupPresence);
        contact.updateGroupId(groupId);
        try {
            mDatabase.saveGroup(groupPresence);
            mDatabase.saveContact(contact);
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return contact;
    }

    public void inviteClientToGroup(String groupId, String clientId) {
        mServerRpc.inviteGroupMember(groupId, clientId);
    }

    public void joinGroup(String groupId) {
        mServerRpc.joinGroup(groupId);
    }

    public void leaveGroup(String groupId) {
        mServerRpc.leaveGroup(groupId);
    }

    private ObjectMapper createObjectMapper() {
        ObjectMapper result = new ObjectMapper();
        result.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        return result;
    }

    private void switchState(int newState, String message) {
        // only switch of there really was a change
        if(mState == newState) {
            LOG.debug("state remains " + STATE_NAMES[mState] + " (" + message + ")");
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
        for(ITalkClientListener listener: mListeners) {
            listener.onClientStateChange(this, newState);
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
            case STATE_CONNECTED:
            case STATE_ACTIVE:
                LOG.debug("supposed to be connected - scheduling connect");
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
        LOG.debug("onOpen()");
        scheduleAutomaticDisconnect();
        switchState(STATE_CONNECTED, "connection opened");
	}

    /**
     * Called when the connection is closed
     * @param connection
     */
	@Override
	public void onClose(JsonRpcConnection connection) {
        LOG.debug("onClose()");
        shutdownAutomaticDisconnect();
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

    private void shutdownAutomaticDisconnect() {
        if(mAutoDisconnectFuture != null) {
            mAutoDisconnectFuture.cancel(false);
            mAutoDisconnectFuture = null;
        }
    }

    private void scheduleAutomaticDisconnect() {
        LOG.debug("scheduleAutomaticDisconnect()");
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

                    if(!selfContact.isSelfRegistered()) {
                        performRegistration(selfContact);
                    }

                    performLogin(selfContact);
                } catch (Throwable t) {
                    t.printStackTrace();
                }
                mLoginFuture = null;
                switchState(STATE_SYNCING, "login successful");
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
                    LOG.info("sync: updating presence");
                    sendPresence();
                    LOG.info("sync: syncing presences");
                    TalkPresence[] presences = mServerRpc.getPresences(never);
                    for(TalkPresence presence: presences) {
                        updateClientPresence(presence);
                    }
                    LOG.info("sync: syncing relationships");
                    TalkRelationship[] relationships = mServerRpc.getRelationships(never);
                    for(TalkRelationship relationship: relationships) {
                        updateClientRelationship(relationship);
                    }
                    LOG.info("sync: syncing groups");
                    TalkGroup[] groups = mServerRpc.getGroups(never);
                    for(TalkGroup group: groups) {
                        updateGroupPresence(group);
                    }
                    LOG.info("sync: syncing group memberships");
                    List<TalkClientContact> contacts = mDatabase.findAllGroupContacts();
                    for(TalkClientContact group: contacts) {
                        if(group.getGroupId() != null) {
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
            LOG.info("server: ping()");
        }

        @Override
        public void pushNotRegistered() {
            LOG.info("server: pushNotRegistered()");
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
            LOG.info("server: presenceUpdated(" + presence.getClientId() + ")");
            updateClientPresence(presence);
        }

        @Override
        public void relationshipUpdated(TalkRelationship relationship) {
            LOG.info("server: relationshipUpdated(" + relationship.getOtherClientId() + ")");
            updateClientRelationship(relationship);
        }

        @Override
        public void groupUpdated(TalkGroup group) {
            LOG.info("server: groupUpdated(" + group.getGroupId() + ")");
            updateGroupPresence(group);
        }

        @Override
        public void groupMemberUpdated(TalkGroupMember member) {
            LOG.info("server: groupMemberUpdated(" + member.getGroupId() + "/" + member.getClientId() + ")");
            updateGroupMember(member);
        }

    }

    private void performRegistration(TalkClientContact selfContact) {
        LOG.info("registration: attempting registration");

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

        LOG.info("registration: started with id " + clientId);

        BigInteger verifier = vg.generateVerifier(salt, clientId.getBytes(), secret);

        mServerRpc.srpRegister(verifier.toString(16), bytesToHex(salt));

        LOG.info("registration: finished");

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
        LOG.info("login: attempting login as " + clientId);
        Digest digest = SRP_DIGEST;

        TalkClientSelf self = selfContact.getSelf();

        SRP6VerifyingClient vc = new SRP6VerifyingClient();
        vc.init(SRP_PARAMETERS.N, SRP_PARAMETERS.g, digest, SRP_RANDOM);

        LOG.info("login: performing phase 1");

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

        LOG.info("login: performing phase 2");

        String Vc = bytesToHex(vc.calculateVerifier());
        String Vs = mServerRpc.srpPhase2(Vc);
        if(!vc.verifyServer(fromHexString(Vs))) {
            throw new RuntimeException("Could not verify server");
        }

        LOG.info("login: successful");
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

    private void sendPresence() {
        try {
            TalkClientContact contact = ensureSelfContact();
            TalkPresence presence = ensureSelfPresence(contact);
            presence = contact.getClientPresence();
            mDatabase.savePresence(presence);
            mDatabase.saveContact(contact);
            mServerRpc.updatePresence(presence);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void updateOutgoingDelivery(TalkDelivery delivery) {
        LOG.info("updateOutgoingDelivery(" + delivery.getMessageId() + ")");
        TalkClientMessage clientMessage = null;
        try {
            clientMessage = mDatabase.findMessageByMessageId(delivery.getMessageId(), false);
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
    }

    private void updateIncomingDelivery(TalkDelivery delivery, TalkMessage message) {
        LOG.info("updateIncomingDelivery(" + delivery.getMessageId() + ")");
        TalkClientMessage clientMessage = null;
        try {
            clientMessage = mDatabase.findMessageByMessageId(delivery.getMessageId(), false);
        } catch (SQLException e) {
            e.printStackTrace();
            return;
        }

        clientMessage.updateIncoming(delivery, message);

        try {
            mDatabase.saveMessage(clientMessage.getMessage());
            mDatabase.saveDelivery(clientMessage.getIncomingDelivery());
            mDatabase.saveClientMessage(clientMessage);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void updateClientPresence(TalkPresence presence) {
        LOG.info("updateClientPresence(" + presence.getClientId() + ")");
        TalkClientContact clientContact = null;
        try {
            clientContact = mDatabase.findContactByClientId(presence.getClientId(), true);
        } catch (SQLException e) {
            e.printStackTrace();
            return;
        }

        clientContact.updatePresence(presence);

        try {
            mDatabase.savePresence(clientContact.getClientPresence());
            mDatabase.saveContact(clientContact);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void updateClientRelationship(TalkRelationship relationship) {
        LOG.info("updateClientRelationship(" + relationship.getOtherClientId() + ")");
        TalkClientContact clientContact = null;
        try {
            clientContact = mDatabase.findContactByClientId(relationship.getOtherClientId(), true);
        } catch (SQLException e) {
            e.printStackTrace();
            return;
        }

        clientContact.updateRelationship(relationship);

        try {
            mDatabase.saveRelationship(clientContact.getClientRelationship());
            mDatabase.saveContact(clientContact);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private void updateGroupPresence(TalkGroup group) {
        LOG.info("updateGroupPresence(" + group.getGroupId() + ")");
        TalkClientContact contact = null;
        try {
            contact = mDatabase.findContactByGroupId(group.getGroupId(), true);
            if(contact == null) {

            }
        } catch (SQLException e) {
            e.printStackTrace();
            return;
        }

        contact.updateGroupPresence(group);

        try {
            mDatabase.saveGroup(contact.getGroupPresence());
            mDatabase.saveContact(contact);
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void updateGroupMember(TalkGroupMember member) {
        LOG.info("updateGroupMember(" + member.getGroupId() + "/" + member.getClientId() + ")");
        TalkClientContact groupContact = null;
        TalkClientContact clientContact = null;
        try {
            groupContact = mDatabase.findContactByGroupId(member.getGroupId(), true);
            clientContact = mDatabase.findContactByClientId(member.getClientId(), true);
        } catch (SQLException e) {
            e.printStackTrace();
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
        }
        // if this concerns the membership of someone else
        if(clientContact.isClient()) {
            LOG.info("Ignoring group member for other client");
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
