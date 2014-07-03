package com.hoccer.talk.client;

import com.hoccer.talk.client.model.TalkClientContact;
import com.hoccer.talk.client.model.TalkClientDownload;
import com.hoccer.talk.client.model.TalkClientMembership;
import com.hoccer.talk.client.model.TalkClientMessage;
import com.hoccer.talk.client.model.TalkClientSelf;
import com.hoccer.talk.client.model.TalkClientSmsToken;
import com.hoccer.talk.client.model.TalkClientUpload;
import com.hoccer.talk.model.*;
import com.j256.ormlite.dao.Dao;
import com.j256.ormlite.stmt.DeleteBuilder;
import com.j256.ormlite.stmt.QueryBuilder;
import com.j256.ormlite.stmt.UpdateBuilder;
import com.j256.ormlite.stmt.Where;
import com.j256.ormlite.support.ConnectionSource;
import com.j256.ormlite.table.TableUtils;

import org.apache.log4j.Logger;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class XoClientDatabase {

    private static final Logger LOG = Logger.getLogger(XoClientDatabase.class);

    IXoClientDatabaseBackend mBackend;

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

    Dao<TalkClientSmsToken, Integer> mSmsTokens;


    public static void createTables(ConnectionSource cs) throws SQLException {
        TableUtils.createTable(cs, TalkClientContact.class);
        TableUtils.createTable(cs, TalkClientSelf.class);
        TableUtils.createTable(cs, TalkPresence.class);
        TableUtils.createTable(cs, TalkRelationship.class);
        TableUtils.createTable(cs, TalkGroup.class);
        TableUtils.createTable(cs, TalkGroupMember.class);

        TableUtils.createTable(cs, TalkClientMembership.class);

        TableUtils.createTable(cs, TalkClientMessage.class);
        TableUtils.createTable(cs, TalkMessage.class);
        TableUtils.createTable(cs, TalkDelivery.class);

        TableUtils.createTable(cs, TalkKey.class);
        TableUtils.createTable(cs, TalkPrivateKey.class);

        TableUtils.createTable(cs, TalkAttachment.class);
        TableUtils.createTable(cs, TalkClientDownload.class);
        TableUtils.createTable(cs, TalkClientUpload.class);

        TableUtils.createTable(cs, TalkClientSmsToken.class);
    }

    public XoClientDatabase(IXoClientDatabaseBackend backend) {
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

        mSmsTokens = mBackend.getDao(TalkClientSmsToken.class);
    }

    public void saveContact(TalkClientContact contact) throws SQLException {
        mClientContacts.createOrUpdate(contact);
    }

    public void saveCredentials(TalkClientSelf credentials) throws SQLException {
        mClientSelfs.createOrUpdate(credentials);
    }

    public void savePresence(TalkPresence presence) throws Exception {
        if (presence.getClientId() == null) {
            // TODO: create own exception!
            throw new Exception("null client id");
        }
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

    public synchronized void saveClientMessage(TalkClientMessage message) throws SQLException {
        message.setProgressState(false);
        mClientMessages.createOrUpdate(message);
    }

    public void saveMessage(TalkMessage message) throws SQLException {
        mMessages.createOrUpdate(message);
    }

    public synchronized void saveDelivery(TalkDelivery delivery) throws SQLException {
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
        return mClientContacts.queryBuilder().where()
                .eq("deleted", false)
                .query();
    }

    public List<TalkClientContact> findAllClientContacts() throws SQLException {
        return findAllClientContactsOrderedByRecentMessage();
    }

    private List<TalkClientContact> findAllClientContactsOrderedByRecentMessage() throws SQLException {
        QueryBuilder<TalkClientMessage, Integer> recentUnreadMessages = mClientMessages.queryBuilder();
        QueryBuilder<TalkClientContact, Integer> recentSenders = mClientContacts.queryBuilder();
        recentUnreadMessages.orderBy("timestamp", false);
        List<TalkClientContact> orderedListOfSenders = recentSenders.join(recentUnreadMessages).where()
                .eq("deleted", false)
                .query();
        List<TalkClientContact> allContacts = mClientContacts.queryBuilder().where()
                .eq("deleted", false)
                .query();
        ArrayList<TalkClientContact> orderedListOfDistinctSenders = new ArrayList<TalkClientContact>();
        for (int i = 0; i < orderedListOfSenders.size(); i++) {
            if (!orderedListOfDistinctSenders.contains(orderedListOfSenders.get(i))) {
                orderedListOfDistinctSenders.add(orderedListOfSenders.get(i));
            }
        }
        for (int i = 0; i < allContacts.size(); i++) {
            if (!orderedListOfDistinctSenders.contains(allContacts.get(i))) {
                orderedListOfDistinctSenders.add(allContacts.get(i));
            }
        }
        return orderedListOfDistinctSenders;
    }

    public List<TalkClientContact> findAllGroupContacts() throws SQLException {
        return mClientContacts.queryBuilder().where()
                .eq("contactType", TalkClientContact.TYPE_GROUP)
                .eq("deleted", false)
                .and(2)
                .query();
    }

    public List<TalkClientContact> findAllNearbyContacts() throws SQLException {
        List<TalkClientContact> allGroupContacts = this.findAllGroupContacts();
        List<TalkClientContact> allNearbyGroupContacts = new ArrayList<TalkClientContact>();
        // add all nearby groups
        for (TalkClientContact groupContact : allGroupContacts) {
            if (groupContact.isGroupInvolved() && groupContact.isGroupExisting() && groupContact.getGroupPresence().isTypeNearby()) {
                allNearbyGroupContacts.add(groupContact);
            }
        }
        allNearbyGroupContacts.addAll(findAllNerabyContactsOrderedByRecentMessage());
        return allNearbyGroupContacts;
    }

    private List<TalkClientContact> findAllNerabyContactsOrderedByRecentMessage() throws SQLException {
        QueryBuilder<TalkClientMessage, Integer> recentUnreadMessages = mClientMessages.queryBuilder();
        QueryBuilder<TalkClientContact, Integer> recentSenders = mClientContacts.queryBuilder();
        recentUnreadMessages.orderBy("timestamp", false);
        List<TalkClientContact> orderedListOfSenders = recentSenders.join(recentUnreadMessages).where()
                .eq("deleted", false)
                .and()
                .eq("isNearby", true)
                .query();
        List<TalkClientContact> allContacts = mClientContacts.queryBuilder().where()
                .eq("deleted", false)
                .and()
                .eq("contactType", TalkClientContact.TYPE_CLIENT)
                .and()
                .eq("isNearby", true)
                .query();
        ArrayList<TalkClientContact> orderedListOfDistinctSenders = new ArrayList<TalkClientContact>();
        for (int i = 0; i < orderedListOfSenders.size(); i++) {
            if (!orderedListOfDistinctSenders.contains(orderedListOfSenders.get(i))) {
                orderedListOfDistinctSenders.add(orderedListOfSenders.get(i));
            }
        }
        for (int i = 0; i < allContacts.size(); i++) {
            if (!orderedListOfDistinctSenders.contains(allContacts.get(i))) {
                orderedListOfDistinctSenders.add(allContacts.get(i));
            }
        }
        return orderedListOfDistinctSenders;
    }

    public List<TalkClientContact> findAllNearbyGroups() throws SQLException {
        List<TalkClientContact> allGroupContacts = this.findAllGroupContacts();
        List<TalkClientContact> allNearbyGroupContacts = new ArrayList<TalkClientContact>();

        // add all nearby groups
        for (TalkClientContact groupContact : allGroupContacts) {
            if (groupContact.isGroupInvolved() && groupContact.isGroupExisting() && groupContact.getGroupPresence().isTypeNearby()) {
                allNearbyGroupContacts.add(groupContact);
            }
        }

        return allNearbyGroupContacts;
    }

    public List<TalkClientSmsToken> findAllSmsTokens() throws SQLException {
        return mSmsTokens.queryForAll();
    }

    public TalkClientContact findSelfContact(boolean create) throws SQLException {
        TalkClientContact contact = null;

        contact = mClientContacts.queryBuilder()
                .where()
                .eq("contactType", TalkClientContact.TYPE_SELF)
                .queryForFirst();

        if (create && contact == null) {
            contact = new TalkClientContact(TalkClientContact.TYPE_SELF);
            mClientContacts.create(contact);
        }

        return contact;
    }

    public synchronized TalkClientContact findContactByClientId(String clientId, boolean create) throws SQLException {
        TalkClientContact contact = null;

        contact = mClientContacts.queryBuilder()
                .where()
                .eq("clientId", clientId)
                .eq("deleted", false)
                .and(2)
                .queryForFirst();

        if (create && contact == null) {
            contact = new TalkClientContact(TalkClientContact.TYPE_CLIENT, clientId);
            mClientContacts.create(contact);
        }

        return contact;
    }

    public synchronized TalkClientContact findContactByGroupId(String groupId, boolean create) throws SQLException {
        TalkClientContact contact = null;

        contact = mClientContacts.queryBuilder()
                .where()
                .eq("groupId", groupId)
                .eq("deleted", false)
                .and(2)
                .queryForFirst();

        if (create && contact == null) {
            contact = new TalkClientContact(TalkClientContact.TYPE_GROUP, groupId);
            mClientContacts.create(contact);
        }

        return contact;
    }

    public TalkClientContact findContactByGroupTag(String groupTag) throws SQLException {
        return mClientContacts.queryBuilder()
                .where()
                .eq("groupTag", groupTag)
                .eq("deleted", false)
                .and(2)
                .queryForFirst();
    }

    public synchronized List<TalkClientMessage> findMessagesForDelivery() throws SQLException {
        List<TalkDelivery> newDeliveries = mDeliveries.queryForEq(TalkDelivery.FIELD_STATE, TalkDelivery.STATE_NEW);

        List<TalkClientMessage> messages = new ArrayList<TalkClientMessage>();
        try {
            for (TalkDelivery newDelivery : newDeliveries) {
                TalkClientMessage message = mClientMessages.queryBuilder()
                        .where()
                        .eq("deleted", false)
                        .eq("outgoingDelivery" + "_id", newDelivery)
                        .and(2)
                        .queryForFirst();

                if (message != null) {

                    if (!message.isInProgress()) {
                        messages.add(message);
                    }

                } else {
                    LOG.error("No outgoing delivery for message with tag '" + newDelivery.getMessageTag() + "'.");
                }
            }

        } catch (SQLException e) {
            LOG.error("Error while fetching messages for delivery: ", e);
        }

        return messages;
    }

    public synchronized TalkClientMessage findMessageByMessageId(String messageId, boolean create) throws SQLException {
        TalkClientMessage message = null;

        message = mClientMessages.queryBuilder()
                .where()
                .eq("messageId", messageId)
                .eq("deleted", false)
                .and(2)
                .queryForFirst();

        if (create && message == null) {
            message = new TalkClientMessage();
            message.setMessageId(messageId);
            mClientMessages.create(message);
        }

        return message;
    }

    public synchronized TalkClientMessage findMessageByMessageTag(String messageTag, boolean create) throws SQLException {
        TalkClientMessage message;

        message = mClientMessages.queryBuilder()
                .where()
                .eq("messageTag", messageTag)
                .eq("deleted", false)
                .and(2)
                .queryForFirst();

        if (create && message == null) {
            message = new TalkClientMessage();
            mClientMessages.create(message);
        }

        return message;
    }

    public List<TalkClientMessage> findNearbyMessages(long count, long offset) throws SQLException {
        List<TalkClientMessage> result = getAllNearbyGroupMessages();
        if (offset + count > result.size()) {
            count = result.size() - offset;
        }
        ArrayList<TalkClientMessage> messages = new ArrayList<TalkClientMessage>();
        for (int i = (int) offset; i < offset + count; i++) {
            messages.add(result.get(i));
        }
        return messages;
    }

    public long getNearbyMessageCount() throws SQLException {
        return getAllNearbyGroupMessages().size();
    }

    public List<TalkClientMessage> getAllNearbyGroupMessages() throws SQLException {
        QueryBuilder<TalkClientMessage, Integer> builder = mClientMessages.queryBuilder();
        builder.where().eq("deleted", false);
        builder.orderBy("timestamp", true);
        List<TalkClientMessage> messages = builder.query();
        ArrayList<TalkClientMessage> res = new ArrayList<TalkClientMessage>();
        for (TalkClientMessage message : messages) {
            if (message.getConversationContact().getContactType().equals("group")) {
                if (message.getConversationContact().getGroupPresence().isTypeNearby()) {
                    res.add(message);
                }
            }
        }
        return res;
    }

    public List<TalkClientMessage> findMessagesByContactId(int contactId, long count, long offset) throws SQLException {
        QueryBuilder<TalkClientMessage, Integer> builder = mClientMessages.queryBuilder();
        builder.limit(count);
        builder.orderBy("timestamp", true);
        builder.offset(offset);
        Where<TalkClientMessage, Integer> where = builder.where()
                .eq("conversationContact_id", contactId)
                .eq("deleted", false)
                .and(2);
        builder.setWhere(where);
        List<TalkClientMessage> messages = mClientMessages.query(builder.prepare());
        return messages;
    }

    public long getMessageCountByContactId(int contactId) throws SQLException {
        return mClientMessages.queryBuilder()
                .where()
                .eq("conversationContact_id", contactId)
                .eq("deleted", false)
                .and(2)
                .countOf();
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

    public long findUnseenMessageCountByContactId(int contactId) throws SQLException {
        return mClientMessages.queryBuilder().where()
                .eq("conversationContact_id", contactId)
                .eq("seen", false)
                .eq("deleted", false)
                .and(3)
                .countOf();
    }

    public TalkClientMessage findLatestMessageByContactId(int contactId) throws SQLException {
        return mClientMessages.queryBuilder()
                .orderBy("timestamp", false)
                .where()
                .isNotNull("text")
                .eq("conversationContact_id", contactId)
                .eq("deleted", false)
                .and(3)
                .queryForFirst();
    }

    public List<TalkClientMessage> findUnseenMessages() throws SQLException {
        return mClientMessages.queryBuilder().orderBy("timestamp", false).
                where()
                .eq("seen", false)
                .eq("deleted", false)
                .and(2)
                .query();
    }


    public TalkClientMembership findMembershipByContacts(int groupId, int clientId, boolean create) throws SQLException {
        TalkClientMembership res = mClientMemberships.queryBuilder().where()
                .eq("groupContact_id", groupId)
                .eq("clientContact_id", clientId)
                .and(2)
                .queryForFirst();

        if (create && res == null) {
            TalkClientContact groupContact = findClientContactById(groupId);
            TalkClientContact clientContact = findClientContactById(clientId);
            res = new TalkClientMembership();
            res.setGroupContact(groupContact);
            res.setClientContact(clientContact);
            mClientMemberships.create(res);
        }

        return res;
    }

    public void saveClientMembership(TalkClientMembership membership) throws SQLException {
        mClientMemberships.createOrUpdate(membership);
    }

    public TalkClientSmsToken findSmsTokenById(int smsTokenId) throws SQLException {
        return mSmsTokens.queryForId(smsTokenId);
    }

    public void saveSmsToken(TalkClientSmsToken token) throws SQLException {
        mSmsTokens.createOrUpdate(token);
    }

    public void deleteSmsToken(TalkClientSmsToken token) throws SQLException {
        mSmsTokens.delete(token);
    }

    public List<TalkClientContact> findAllPendingFriendRequests() {
        try {
            List<TalkClientContact> contacts = new ArrayList<TalkClientContact>();
            List<TalkRelationship> relationshipsInvitedMe = mRelationships.queryBuilder()
                    .where()
                    .eq("state", TalkRelationship.STATE_INVITED_ME)
                    .query();
            for (TalkRelationship relationship : relationshipsInvitedMe) {
                TalkClientContact contact = findContactByClientId(relationship.getOtherClientId(), false);
                if (contact != null) {
                    contacts.add(contact);
                }
            }

            List<TalkRelationship> relationshipsInvitedByMe = mRelationships.queryBuilder()
                    .where()
                    .eq("state", TalkRelationship.STATE_INVITED)
                    .query();
            for (TalkRelationship relationship : relationshipsInvitedByMe) {
                TalkClientContact contact = findContactByClientId(relationship.getOtherClientId(), false);
                if (contact != null) {
                    contacts.add(contact);
                }
            }

            return contacts;
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    public boolean hasPendingFriendRequests() {
        try {
            List<TalkRelationship> invitedRelations = mRelationships.queryBuilder()
                    .where()
                    .eq("state", TalkRelationship.STATE_INVITED_ME)
                    .or()
                    .eq("state", TalkRelationship.STATE_INVITED)
                    .query();
            if (invitedRelations != null && invitedRelations.size() > 0) {
                return true;
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return false;
    }


    public void deleteAllClientContacts() throws SQLException {
        UpdateBuilder<TalkClientContact, Integer> updateBuilder = mClientContacts.updateBuilder();
        updateBuilder.updateColumnValue("deleted", true).where()
                .eq("deleted", false)
                .eq("contactType", TalkClientContact.TYPE_CLIENT)
                .and(2);
        updateBuilder.update();
    }

    public void deleteAllGroupContacts() throws SQLException {
        UpdateBuilder<TalkClientContact, Integer> updateBuilder = mClientContacts.updateBuilder();
        updateBuilder.updateColumnValue("deleted", true).where()
                .eq("deleted", false)
                .eq("contactType", TalkClientContact.TYPE_GROUP)
                .and(2);
        updateBuilder.update();
    }

    public void deleteAllMessagesFromContactId(int contactId) throws SQLException {
        UpdateBuilder<TalkClientMessage, Integer> updateBuilder = mClientMessages.updateBuilder();
        updateBuilder.updateColumnValue("deleted", true).where()
                .eq("deleted", false)
                .eq("conversationContact_id", contactId)
                .and(2);
        updateBuilder.update();
    }

    public void eraseAllClientContacts() throws SQLException {
        DeleteBuilder<TalkClientContact, Integer> deleteBuilder = mClientContacts.deleteBuilder();
        deleteBuilder.where()
                .eq("deleted", false)
                .eq("contactType", TalkClientContact.TYPE_CLIENT)
                .and(2);
        deleteBuilder.delete();
    }

    public void eraseAllGroupContacts() throws SQLException {
        DeleteBuilder<TalkClientContact, Integer> deleteBuilder = mClientContacts.deleteBuilder();
        deleteBuilder.where()
                .eq("deleted", false)
                .eq("contactType", TalkClientContact.TYPE_GROUP)
                .and(2);
        deleteBuilder.delete();
    }

    public void eraseAllRelationships() throws SQLException {
        DeleteBuilder<TalkRelationship, Long> deleteBuilder = mRelationships.deleteBuilder();
        deleteBuilder.delete();
    }

    public void eraseAllGroupMemberships() throws SQLException {
        DeleteBuilder<TalkGroupMember, Long> deleteBuilder = mGroupMembers.deleteBuilder();
        deleteBuilder.delete();
    }

    public void migrateAllFilecacheUris() throws SQLException {
        List<TalkClientMessage> messages = mClientMessages.queryBuilder().where()
                .isNotNull("attachmentUpload_id")
                .or()
                .isNotNull("attachmentDownload_id").query();
        for (TalkClientMessage message : messages) {
            TalkClientDownload download = message.getAttachmentDownload();
            TalkClientUpload upload = message.getAttachmentUpload();
            if (download != null) {
                download.setDownloadUrl(migrateFilecacheUrl(download.getDownloadUrl()));
                mClientDownloads.update(download);
            }
            if (upload != null) {
                upload.setUploadUrl(migrateFilecacheUrl(upload.getUploadUrl()));
                mClientUploads.update(upload);
            }
        }
    }

    private String migrateFilecacheUrl(String url) {
        if (url == null) {
            return null;
        }
        String migratedUrl = url.substring(url.indexOf("/", 8));
        migratedUrl = "https://filecache.talk.hoccer.de:8444" + migratedUrl;
        LOG.debug("migrated url: " + url + " to: " + migratedUrl);
        return migratedUrl;
    }

    /* delivered -> deliveredPrivate
     * confirmed -> deliveredPrivateAcknowledged
     * aborted -> abortedAcknowledged
     * failed -> failedAcknowledged */
    public void migrateDeliveryStates() throws SQLException {
        List<TalkDelivery> talkDeliveries = mDeliveries.queryForAll();
        for(TalkDelivery delivery : talkDeliveries) {
            if (delivery.getState().equals(TalkDelivery.STATE_DELIVERED_OLD)) {
                delivery.setState(TalkDelivery.STATE_DELIVERED_PRIVATE);
            } else if (delivery.getState().equals(TalkDelivery.STATE_CONFIRMED_OLD)) {
                delivery.setState(TalkDelivery.STATE_DELIVERED_PRIVATE_ACKNOWLEDGED);
            } else if(delivery.getState().equals(TalkDelivery.STATE_ABORTED_OLD)) {
                delivery.setState(TalkDelivery.STATE_ABORTED_ACKNOWLEDGED);
            } else if(delivery.getState().equals(TalkDelivery.STATE_FAILED_OLD)) {
                delivery.setState(TalkDelivery.STATE_FAILED_ACKNOWLEDGED);
            }

            TalkClientMessage message = findMessageByMessageId(delivery.getMessageId(), false);
            if(message != null) {
                TalkClientUpload upload = message.getAttachmentUpload();
                TalkClientDownload download;
                if(upload == null) {
                    download = message.getAttachmentDownload();
                    if(download != null) {
                        migrateTalkClientDownload(delivery, download);
                    } else { // there is no Attachment in this delivery
                        delivery.setAttachmentState(TalkDelivery.ATTACHMENT_STATE_NONE);
                    }
                } else {
                    migrateTalkClientUpload(delivery, upload);
                }
            }

            saveDelivery(delivery);
        }
    }

    private void migrateTalkClientUpload(TalkDelivery delivery, TalkClientUpload upload) {
        switch(upload.getState()) {
            case COMPLETE: delivery.setAttachmentState(TalkDelivery.ATTACHMENT_STATE_RECEIVED_ACKNOWLEDGED);
                break;
            case FAILED: delivery.setAttachmentState(TalkDelivery.ATTACHMENT_STATE_UPLOAD_FAILED_ACKNOWLEDGED);
                break;
            default: delivery.setAttachmentState(TalkDelivery.ATTACHMENT_STATE_NEW);
                break;
        }
    }

    private void migrateTalkClientDownload(TalkDelivery delivery, TalkClientDownload download) {
        switch(download.getState()) {
            case COMPLETE: delivery.setAttachmentState(TalkDelivery.ATTACHMENT_STATE_RECEIVED_ACKNOWLEDGED);
                break;
            case FAILED: delivery.setAttachmentState(TalkDelivery.ATTACHMENT_STATE_DOWNLOAD_FAILED_ACKNOWLEDGED);
                break;
            default: delivery.setAttachmentState(TalkDelivery.ATTACHMENT_STATE_NEW);
                break;
        }
    }

}
