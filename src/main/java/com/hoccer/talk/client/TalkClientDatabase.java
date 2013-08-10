package com.hoccer.talk.client;

import com.hoccer.talk.client.model.*;
import com.hoccer.talk.model.*;
import com.j256.ormlite.dao.Dao;
import org.apache.log4j.Logger;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class TalkClientDatabase {

    private static final Logger LOG = Logger.getLogger(TalkClientDatabase.class);

    ITalkClientDatabaseBackend mBackend;

    Dao<TalkClientContact, Integer> mClientContacts;
    Dao<TalkClientSelf, Integer> mClientSelfs;
    Dao<TalkPresence, String> mPresences;
    Dao<TalkRelationship, Long> mRelationships;
    Dao<TalkGroup, String> mGroups;
    Dao<TalkGroupMember, Long> mGroupMembers;

    Dao<TalkClientMembership, Integer> mClientMemberships;

    Dao<TalkClientMessage, Integer> mClientMessages;
    Dao<TalkMessage, String> mMessages;
    Dao<TalkDelivery, Long> mDeliveries;

    Dao<TalkKey, Long> mPublicKeys;
    Dao<TalkPrivateKey, Long> mPrivateKeys;

    Dao<TalkClientDownload, Integer> mClientDownloads;
    Dao<TalkClientUpload, Integer> mClientUploads;


    public TalkClientDatabase(ITalkClientDatabaseBackend backend) {
        mBackend = backend;
    }

    public void initialize() throws SQLException {
        mClientContacts = mBackend.getDao(TalkClientContact.class);
        mClientSelfs = mBackend.getDao(TalkClientSelf.class);
        mPresences = mBackend.getDao(TalkPresence.class);
        mRelationships = mBackend.getDao(TalkRelationship.class);
        mGroups = mBackend.getDao(TalkGroup.class);
        mGroupMembers = mBackend.getDao(TalkGroupMember.class);

        mClientMemberships = mBackend.getDao(TalkClientMembership.class);

        mClientMessages = mBackend.getDao(TalkClientMessage.class);
        mMessages = mBackend.getDao(TalkMessage.class);
        mDeliveries = mBackend.getDao(TalkDelivery.class);

        mPublicKeys = mBackend.getDao(TalkKey.class);
        mPrivateKeys = mBackend.getDao(TalkPrivateKey.class);

        mClientDownloads = mBackend.getDao(TalkClientDownload.class);
        mClientUploads = mBackend.getDao(TalkClientUpload.class);
    }

    public void saveContact(TalkClientContact contact) throws SQLException {
        mClientContacts.createOrUpdate(contact);
    }

    public void saveCredentials(TalkClientSelf credentials) throws SQLException {
        mClientSelfs.createOrUpdate(credentials);
    }

    public void savePresence(TalkPresence presence) throws SQLException {
        mPresences.createOrUpdate(presence);
    }

    public void saveRelationship(TalkRelationship relationship) throws SQLException {
        mRelationships.createOrUpdate(relationship);
    }

    public void saveGroup(TalkGroup group) throws SQLException {
        mGroups.createOrUpdate(group);
    }

    public void saveGroupMember(TalkGroupMember member) throws SQLException {
        mGroupMembers.createOrUpdate(member);
    }

    public void saveClientMessage(TalkClientMessage message) throws SQLException {
        mClientMessages.createOrUpdate(message);
    }

    public void saveMessage(TalkMessage message) throws SQLException {
        mMessages.createOrUpdate(message);
    }

    public void saveDelivery(TalkDelivery delivery) throws SQLException {
        mDeliveries.createOrUpdate(delivery);
    }

    public void savePublicKey(TalkKey publicKey) throws SQLException {
        mPublicKeys.createOrUpdate(publicKey);
    }

    public void savePrivateKey(TalkPrivateKey privateKey) throws SQLException {
        mPrivateKeys.createOrUpdate(privateKey);
    }

    public void saveClientDownload(TalkClientDownload download) throws SQLException {
        mClientDownloads.createOrUpdate(download);
    }

    public void saveClientUpload(TalkClientUpload upload) throws SQLException {
        mClientUploads.createOrUpdate(upload);
    }

    public void refreshClientContact(TalkClientContact contact) throws SQLException {
        mClientContacts.refresh(contact);
    }

    public void refreshClientDownload(TalkClientDownload download) throws SQLException {
        mClientDownloads.refresh(download);
    }

    public void refreshClientUpload(TalkClientUpload upload) throws SQLException {
        mClientUploads.refresh(upload);
    }

    public TalkClientContact findClientContactById(int clientContactId) throws SQLException {
        return mClientContacts.queryForId(clientContactId);
    }

    public List<TalkClientContact> findAllContacts() throws SQLException {
        return mClientContacts.queryForAll();
    }

    public List<TalkClientContact> findAllClientContacts() throws SQLException {
        return mClientContacts.queryBuilder().where()
                .eq("contactType", TalkClientContact.TYPE_CLIENT)
                .query();
    }

    public List<TalkClientContact> findAllGroupContacts() throws SQLException {
        return mClientContacts.queryBuilder().where()
                .eq("contactType", TalkClientContact.TYPE_GROUP)
                .query();
    }

    public TalkClientContact findSelfContact(boolean create) throws SQLException {
        TalkClientContact contact = null;

        contact = mClientContacts.queryBuilder()
                    .where().eq("contactType", TalkClientContact.TYPE_SELF)
                    .queryForFirst();

        if(create && contact == null) {
            contact = new TalkClientContact(TalkClientContact.TYPE_SELF);
            mClientContacts.create(contact);
        }

        return contact;
    }

    public TalkClientContact findContactByClientId(String clientId, boolean create) throws SQLException {
        TalkClientContact contact = null;

        contact = mClientContacts.queryBuilder()
                    .where().eq("clientId", clientId)
                    .queryForFirst();

        if(create && contact == null) {
            contact = new TalkClientContact(TalkClientContact.TYPE_CLIENT, clientId);
            mClientContacts.create(contact);
        }

        return contact;
    }

    public TalkClientContact findContactByGroupId(String groupId, boolean create) throws SQLException {
        TalkClientContact contact = null;

        contact = mClientContacts.queryBuilder()
                    .where().eq("groupId", groupId)
                    .queryForFirst();

        if(create && contact == null) {
            contact = new TalkClientContact(TalkClientContact.TYPE_GROUP, groupId);
            mClientContacts.create(contact);
        }

        return contact;
    }

    public TalkClientContact findContactByGroupTag(String groupTag) throws SQLException {
        return mClientContacts.queryBuilder()
                .where().eq("groupTag", groupTag)
                .queryForFirst();
    }

    public List<TalkClientMessage> findMessagesForDelivery() throws SQLException {
        List<TalkDelivery> newDeliveries = mDeliveries.queryForEq(TalkDelivery.FIELD_STATE, TalkDelivery.STATE_NEW);

        List<TalkClientMessage> messages = new ArrayList<TalkClientMessage>();
        try {
        for(TalkDelivery d: newDeliveries) {
            TalkClientMessage m = mClientMessages.queryBuilder()
                                    .where().eq("outgoingDelivery" + "_id", d)
                                    .queryForFirst();
            if(m != null) {
                messages.add(m);
            } else {
                LOG.error("no message for delivery for tag " + d.getMessageTag());
            }
        }
        } catch (Throwable t) {
            LOG.error("SQL fnord", t);
        }

        return messages;
    }

    public TalkClientMessage findMessageByMessageId(String messageId, boolean create) throws SQLException {
        TalkClientMessage message = null;

        message = mClientMessages.queryBuilder()
                    .where().eq("messageId", messageId)
                    .queryForFirst();

        if(create && message == null) {
            message = new TalkClientMessage();
            mClientMessages.create(message);
        }

        return message;
    }

    public TalkClientMessage findMessageByMessageTag(String messageTag, boolean create) throws SQLException {
        TalkClientMessage message = null;

        message = mClientMessages.queryBuilder()
                .where().eq("messageTag", messageTag)
                .queryForFirst();

        if(create && message == null) {
            message = new TalkClientMessage();
            mClientMessages.create(message);
        }

        return message;
    }

    public List<TalkClientMessage> findMessagesByContactId(int contactId) throws SQLException {
        return mClientMessages.queryForEq("conversationContact_id", contactId);
    }

    public TalkPrivateKey findPrivateKeyByKeyId(String keyId) throws SQLException {
        return mPrivateKeys.queryBuilder().where().eq("keyId", keyId).queryForFirst();
    }

    public TalkClientUpload findClientUploadById(int clientUploadId) throws SQLException {
        return mClientUploads.queryForId(clientUploadId);
    }

    public TalkClientDownload findClientDownloadById(int clientDownloadId) throws SQLException {
        return mClientDownloads.queryForId(clientDownloadId);
    }

    public TalkClientMessage findClientMessageById(int clientMessageId) throws SQLException {
        return mClientMessages.queryForId(clientMessageId);
    }

    public long findUnseenMessageCountByContactId(int contactId) throws SQLException {
        return mClientMessages.queryBuilder().where()
                .eq("conversationContact_id", contactId)
                .eq("seen", false)
                .and(2)
                .countOf();
    }

    public TalkClientMessage findLatestMessageByContactId(int contactId) throws SQLException {
        return mClientMessages.queryBuilder()
                .orderBy("timestamp", false)
                    .where()
                        .isNotNull("text")
                        .eq("conversationContact_id", contactId)
                    .and(2)
                .queryForFirst();
    }

    public List<TalkClientMessage> findUnseenMessages() throws SQLException {
        return mClientMessages.queryForEq("seen", false);
    }

}
