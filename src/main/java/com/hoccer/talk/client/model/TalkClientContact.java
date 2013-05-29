package com.hoccer.talk.client.model;

import com.hoccer.talk.model.TalkGroup;
import com.hoccer.talk.model.TalkGroupMember;
import com.hoccer.talk.model.TalkPresence;
import com.hoccer.talk.model.TalkRelationship;
import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;

/**
 * These represent a target of communication
 *
 * This may currently be either a groupPresence or another user.
 */
@DatabaseTable(tableName = "clientContact")
public class TalkClientContact {

    public static final String TYPE_SELF   = "self";
    public static final String TYPE_CLIENT = "client";
    public static final String TYPE_GROUP  = "group";

    @DatabaseField(generatedId = true)
    private int clientContactId;

    @DatabaseField
    private String contactType;


    @DatabaseField(canBeNull = true, foreign = true, foreignAutoRefresh = true)
    private TalkClientSelf self;


    @DatabaseField(canBeNull = true)
    private String clientId;

    @DatabaseField(canBeNull = true, foreign = true, foreignAutoRefresh = true)
    private TalkPresence clientPresence;

    @DatabaseField(canBeNull = true, foreign = true, foreignAutoRefresh = true)
    private TalkRelationship clientRelationship;


    @DatabaseField(canBeNull = true)
    private String groupId;

    @DatabaseField(canBeNull = true, foreign = true, foreignAutoRefresh = true)
    private TalkGroup groupPresence;

    @DatabaseField(canBeNull = true, foreign = true, foreignAutoRefresh = true)
    private TalkGroupMember groupMember;

    public TalkClientContact() {

    }

    public TalkClientContact(String contactType) {
        this.contactType = contactType;
    }

    public TalkClientContact(String contactType, String id) {
        this(contactType);
        if(contactType.equals(TYPE_CLIENT) || contactType.equals(TYPE_SELF)) {
            this.clientId = id;
        }
        if(contactType.equals(TYPE_GROUP)) {
            this.groupId = id;
        }
    }


    public boolean isSelf() {
        return this.contactType.equals(TYPE_SELF);
    }

    public boolean isSelfRegistered() {
        return isSelf() && this.clientId != null && this.self != null;
    }

    public boolean isClient() {
        return this.contactType.equals(TYPE_CLIENT);
    }

    public boolean isGroup() {
        return this.contactType.equals(TYPE_GROUP);
    }

    private void ensureSelf() {
        if(!isSelf()) {
            throw new RuntimeException("Client is not of type self");
        }
    }

    private void ensureClient() {
        if(!isClient()) {
            throw new RuntimeException("Client is not of type client");
        }
    }

    private void ensureClientOrSelf() {
        if(!(isClient() || isSelf())) {
            throw new RuntimeException("Client is not of type client or self");
        }
    }

    private void ensureGroup() {
        if(!isGroup()) {
            throw new RuntimeException("Client is not of type group");
        }
    }


    public String getContactType() {
        return contactType;
    }

    public TalkClientSelf getSelf() {
        ensureSelf();
        return self;
    }


    public String getClientId() {
        ensureClientOrSelf();
        return clientId;
    }


    public TalkPresence getClientPresence() {
        ensureClientOrSelf();
        return clientPresence;
    }

    public TalkRelationship getClientRelationship() {
        ensureClient();
        return clientRelationship;
    }


    public String getGroupId() {
        ensureGroup();
        return groupId;
    }

    public TalkGroup getGroupPresence() {
        ensureGroup();
        return groupPresence;
    }


    public void updateSelf(String clientId, TalkClientSelf self) {
        this.clientId = clientId;
        if(this.self == null) {
            this.self = self;
        } else {
            this.self.update(self);
        }
    }

    public void updatePresence(TalkPresence presence) {
        ensureClientOrSelf();
        if(this.clientPresence == null) {
            this.clientPresence = presence;
            presence.setClientId(getClientId());
        } else {
            TalkPresence my = this.clientPresence;
            my.setClientName(presence.getClientName());
            my.setClientStatus(presence.getClientStatus());
            my.setConnectionStatus(presence.getConnectionStatus());
            my.setAvatarUrl(presence.getAvatarUrl());
            my.setKeyId(presence.getKeyId());
            my.setTimestamp(presence.getTimestamp());
        }
    }

    public void updateRelationship(TalkRelationship relationship) {
        ensureClient();
        if(this.clientRelationship == null) {
            this.clientRelationship = relationship;
        } else {
            TalkRelationship my = this.clientRelationship;
            my.setLastChanged(relationship.getLastChanged());
            my.setState(relationship.getState());
        }
    }

}
