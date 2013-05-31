package com.hoccer.talk.client;

import com.hoccer.talk.client.model.TalkClientContact;
import com.hoccer.talk.client.model.TalkClientSelf;
import com.hoccer.talk.model.TalkGroup;
import com.hoccer.talk.model.TalkGroupMember;
import com.hoccer.talk.model.TalkPresence;
import com.hoccer.talk.model.TalkRelationship;
import com.j256.ormlite.dao.Dao;

import java.sql.SQLException;
import java.util.List;

public class TalkClientDatabase {

    ITalkClientDatabaseBackend mBackend;

    Dao<TalkClientContact, Integer> mClientContacts;
    Dao<TalkClientSelf, Integer> mClientSelfs;
    Dao<TalkPresence, String> mPresences;
    Dao<TalkRelationship, Long> mRelationships;
    Dao<TalkGroup, String> mGroups;
    Dao<TalkGroupMember, Long> mGroupMembers;

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
    }

    public void saveContact(TalkClientContact contact) throws SQLException {
        mClientContacts.update(contact);
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

}
