package com.hoccer.talk.client.model;

import com.hoccer.talk.client.XoClient;
import com.hoccer.talk.content.IContentObject;
import com.hoccer.talk.crypto.AESCryptor;
import com.hoccer.talk.model.TalkGroup;
import com.hoccer.talk.model.TalkGroupMember;
import com.hoccer.talk.model.TalkKey;
import com.hoccer.talk.model.TalkPresence;
import com.hoccer.talk.model.TalkPrivateKey;
import com.hoccer.talk.model.TalkRelationship;
import com.j256.ormlite.dao.ForeignCollection;
import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.field.ForeignCollectionField;
import com.j256.ormlite.table.DatabaseTable;
import org.apache.commons.codec.binary.Base64;

import java.io.Serializable;
import java.nio.charset.Charset;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.*;

/**
 * These represent a target of communication
 *
 * This may currently be either a groupPresence or another user.
 */
@DatabaseTable(tableName = "clientContact")
public class TalkClientContact implements Serializable {

    public @interface ClientMethodOnly {};
    public @interface ClientOrGroupMethodOnly {};
    public @interface ClientOrSelfMethodOnly {};
    public @interface GroupMethodOnly {};
    public @interface SelfMethodOnly {};

    public static final String TYPE_SELF   = "self";
    public static final String TYPE_CLIENT = "client";
    public static final String TYPE_GROUP  = "group";

    @DatabaseField(generatedId = true)
    private int clientContactId;

    @DatabaseField
    private String contactType;

    @DatabaseField
    private boolean deleted;

    @DatabaseField
    private boolean everRelated;


    @DatabaseField(canBeNull = true, foreign = true, foreignAutoRefresh = true)
    private TalkKey publicKey;

    @DatabaseField(canBeNull = true, foreign = true, foreignAutoRefresh = true)
    private TalkPrivateKey privateKey;


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

    @DatabaseField(canBeNull = true)
    private String groupTag;

    @DatabaseField(canBeNull = true)
    private String groupKey;

    @DatabaseField(canBeNull = true, foreign = true, foreignAutoRefresh = true)
    private TalkGroup groupPresence;

    // when contact is a group, groupMember is the own member description
    @DatabaseField(canBeNull = true, foreign = true, foreignAutoRefresh = true)
    private TalkGroupMember groupMember;

    @ForeignCollectionField(eager = false, foreignFieldName = "groupContact")
    private ForeignCollection<TalkClientMembership> groupMemberships;


    @DatabaseField(canBeNull = true, foreign = true, foreignAutoRefresh = true)
    private TalkClientDownload avatarDownload;

    @DatabaseField(canBeNull = true, foreign = true, foreignAutoRefresh = true)
    private TalkClientUpload avatarUpload;

    @DatabaseField
    private boolean isNearby;

    @DatabaseField
    private String nickname;
    

    public TalkClientContact() {
        //System.out.println("TalkClientContact(): this="+this);
        //new Exception().printStackTrace(System.out);
    }

    public TalkClientContact(String contactType) {
        this.contactType = contactType;
        //System.out.println("TalkClientContact(): contactType="+contactType+" this="+this);
    }

    public TalkClientContact(String contactType, String id) {
        this(contactType);
        //System.out.println("TalkClientContact: contactType="+contactType+" id="+id);
        if(contactType.equals(TYPE_CLIENT) || contactType.equals(TYPE_SELF)) {
            this.clientId = id;
        }
        if(contactType.equals(TYPE_GROUP)) {
            this.groupId = id;
        }
    }

    @SelfMethodOnly
    public boolean isEditable() {
        return isSelf() || isGroupAdmin() || (isGroup() && !isGroupRegistered());
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void markAsDeleted() {
        this.deleted = true;
    }

    public boolean isEverRelated() {
        return everRelated;
    }

    public void markAsRelated() {
        this.everRelated = true;
    }

    public int getClientContactId() {
        return clientContactId;
    }

    public IContentObject getAvatar() {
        if(avatarDownload != null && avatarDownload.isContentAvailable()) {
            return avatarDownload;
        } else if(avatarUpload != null && avatarUpload.isContentAvailable()) {
            return avatarUpload;
        }
        return null;
    }

    public String getAvatarContentUrl() {
        IContentObject avatar = getAvatar();
        if(avatar != null) {
            return avatar.getContentDataUrl();
        }
        return null;
    }

    public boolean isSelf() {
        return this.contactType.equals(TYPE_SELF);
    }

    public boolean isSelfRegistered() {
        return isSelf() && this.clientId != null;
    }

    public boolean isClient() {
        return this.contactType.equals(TYPE_CLIENT);
    }

    public boolean isClientRelated() {
        return isClient() && this.clientRelationship != null && this.clientRelationship.isRelated();
    }

    public boolean isClientFriend() {
        return isClient() && this.clientRelationship != null && this.clientRelationship.isFriend();
    }

    public boolean isClientBlocked() {
        return isClient() && this.clientRelationship != null && this.clientRelationship.isBlocked();
    }

    public boolean isGroup() {
        return this.contactType.equals(TYPE_GROUP);
    }

    public boolean isGroupRegistered() {
        return isGroup() && this.groupId != null;
    }

    public boolean isGroupExisting() {
        return isGroup() && this.groupPresence != null && this.groupPresence.getState().equals(TalkGroup.STATE_EXISTS);
    }

    public boolean isGroupAdmin() {
        return isGroup() && this.groupMember != null && this.groupMember.isAdmin();
    }

    public boolean isGroupInvolved() {
        return isGroup() && this.groupMember != null && this.groupMember.isInvolved();
    }

    public boolean isGroupInvited() {
        return isGroup() && this.groupMember != null && this.groupMember.isInvited();
    }

    public boolean isGroupJoined() {
        return isGroup() && this.groupMember != null && this.groupMember.isJoined();
    }

    // returns true if there is actually a group key locally stored
    @GroupMethodOnly
    public boolean groupHasKey() {
        ensureGroup();
        return this.getGroupKey() != null &&
                Base64.decodeBase64(this.getGroupKey().getBytes(Charset.forName("UTF-8"))).length == AESCryptor.KEY_SIZE;
    }

    // return true if there is a group key and the stored shared key id matches the computed key id
    @GroupMethodOnly
    public boolean groupHasValidKey() {
        ensureGroup();
        if (groupHasKey() && getGroupPresence() != null) {
            byte [] sharedKey = Base64.decodeBase64(this.getGroupKey().getBytes(Charset.forName("UTF-8")));
            byte [] sharedKeyId = Base64.decodeBase64(this.groupPresence.getSharedKeyId().getBytes(Charset.forName("UTF-8")));
            byte [] sharedKeySalt = Base64.decodeBase64(this.groupPresence.getSharedKeyIdSalt().getBytes(Charset.forName("UTF-8")));
            try {
                byte [] actualSharedKeyId = AESCryptor.calcSymmetricKeyId(sharedKey,sharedKeySalt);
                return actualSharedKeyId.equals(sharedKey);
            } catch (NoSuchAlgorithmException e) {
                e.printStackTrace();
            }
        }
        return false;
    }

    // return true if the this contact is joined member of group
    public boolean isClientGroupJoined(TalkClientContact group) {
        if(!group.isGroupRegistered()) {
            return false;
        }
        if(!isClient()) {
            return false;
        }
        int myId = getClientContactId();
        ForeignCollection<TalkClientMembership> memberships = group.getGroupMemberships();
        if(memberships != null) {
            for(TalkClientMembership membership: memberships) {
                TalkGroupMember member = membership.getMember();
                if(member != null && member.isJoined()) {
                    if(membership.getClientContact().getClientContactId() == myId) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public boolean isClientGroupInvited(TalkClientContact group) {
        if(!group.isGroupRegistered()) {
            return false;
        }
        if(!isClient()) {
            return false;
        }
        int myId = getClientContactId();
        ForeignCollection<TalkClientMembership> memberships = group.getGroupMemberships();
        if(memberships != null) {
            for(TalkClientMembership membership: memberships) {
                TalkGroupMember member = membership.getMember();
                if(member != null && member.isInvited()) {
                    if(membership.getClientContact().getClientContactId() == myId) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public String getName() {
        if(isClient() || isSelf()) {
            if(clientPresence != null) {
                return clientPresence.getClientName();
            }
            if(isSelf()) {
                return "<self>";
            }
        }
        if(isGroup()) {
            if(groupPresence != null) {
                return groupPresence.getGroupName();
            }
        }
        return "<unknown>";
    }

    public String getStatus() {
        if(isClient()) {
            if(clientPresence != null) {
                return clientPresence.getClientStatus();
            }
        }
        return "";
    }

    public TalkClientDownload getAvatarDownload() {
        return avatarDownload;
    }

    @ClientOrGroupMethodOnly
    public void setAvatarDownload(TalkClientDownload avatarDownload) {
        ensureClientOrGroup();
        this.avatarDownload = avatarDownload;
    }

    public TalkClientUpload getAvatarUpload() {
        return avatarUpload;
    }

    @ClientOrSelfMethodOnly
    public void setAvatarUpload(TalkClientUpload avatarUpload) {
        ensureGroupOrSelf();
        this.avatarUpload = avatarUpload;
    }

    public void hackSetGroupPresence(TalkGroup group) {
        this.groupPresence = group;
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

    private void ensureClientOrGroup() {
        if(!(isClient() || isGroup())) {
            throw new RuntimeException("Client is not of type client or group");
        }
    }

    private void ensureGroup() {
        if(!isGroup()) {
            throw new RuntimeException("Client is not of type group");
        }
    }

    private void ensureGroupOrSelf() {
        if(!(isGroup() || isSelf())) {
            throw new RuntimeException("Client is not of type group or self");
        }
    }


    public String getContactType() {
        return contactType;
    }

    public TalkKey getPublicKey() {
        return publicKey;
    }

    public void setPublicKey(TalkKey publicKey) {
        this.publicKey = publicKey;
    }

    public TalkPrivateKey getPrivateKey() {
        return privateKey;
    }

    public void setPrivateKey(TalkPrivateKey privateKey) {
        this.privateKey = privateKey;
    }

    public String getNickname() {
        if(nickname == null || nickname.isEmpty()) {
            return getName();
        }
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    @SelfMethodOnly
    public TalkClientSelf getSelf() {
        ensureSelf();
        return self;
    }

    @ClientOrSelfMethodOnly
    public String getClientId() {
        ensureClientOrSelf();
        return clientId;
    }

    @ClientOrSelfMethodOnly
    public TalkPresence getClientPresence() {
        ensureClientOrSelf();
        return clientPresence;
    }

    @ClientMethodOnly
    public TalkRelationship getClientRelationship() {
        ensureClient();
        return clientRelationship;
    }

    @GroupMethodOnly
    public String getGroupId() {
        ensureGroup();
        return groupId;
    }

    @GroupMethodOnly
    public String getGroupTag() {
        ensureGroup();
        return groupTag;
    }

    @GroupMethodOnly
    public TalkGroup getGroupPresence() {
        ensureGroup();
        return groupPresence;
    }

    @GroupMethodOnly
    public TalkGroupMember getGroupMember() {
        ensureGroup();
        return groupMember;
    }

    // the actual group key, Base64-encoded
    @GroupMethodOnly
    public String getGroupKey() {
        ensureGroup();
        return groupKey;
    }

    // the actual group key, Base64-encoded
    @GroupMethodOnly
    public void setGroupKey(String groupKey) {
        ensureGroup();
        this.groupKey = groupKey;
    }

    @GroupMethodOnly
    public ForeignCollection<TalkClientMembership> getGroupMemberships() {
        ensureGroup();
        return groupMemberships;
    }

    @GroupMethodOnly
    public TalkClientMembership getSelfClientMembership(XoClient theClient) {
        ensureGroup();
        if(!this.isGroupRegistered()) {
            return null;
        }
        try {
            TalkClientContact contact = theClient.getSelfContact();
            if (contact != null) {
                TalkClientMembership membership = theClient.getDatabase().findMembershipByContacts(this.getClientContactId(),contact.getClientContactId(),false);
                return membership;
             }
        } catch (SQLException e) {
            e.printStackTrace();
        }
/*
        ensureGroup();
        ForeignCollection<TalkClientMembership> memberships = this.getGroupMemberships();
        if(memberships != null && memberships.size() > 0) {
            for(TalkClientMembership membership: memberships) {
                TalkClientContact contact = membership.getClientContact();
                if (contact != null && contact.isSelf()) {
                    return membership;
                }
            }
        }
        */
        return null;
    }

    public boolean isNearby() {
        return isNearby;
    }

    public void setNearby(boolean isNearby) {
        this.isNearby = isNearby;
    }

    @SelfMethodOnly
    public boolean initializeSelf() {
        boolean changed = false;
        ensureSelf();
        if(this.self == null) {
            this.self = new TalkClientSelf();
            changed = true;
        }
        if(this.clientPresence == null) {
            this.clientPresence = new TalkPresence();
            changed = true;
        }
        return changed;
    }

    @SelfMethodOnly
    public void updateSelfConfirmed() {
        ensureSelf();
        this.self.confirmRegistration();
    }

    @SelfMethodOnly
    public void updateSelfRegistered(String clientId) {
        ensureSelf();
        this.clientId = clientId;
    }

    @GroupMethodOnly
    public void updateGroupId(String groupId) {
        ensureGroup();
        this.groupId = groupId;
    }

    @GroupMethodOnly
    public void updateGroupTag(String groupTag) {
        ensureGroup();
        this.groupTag = groupTag;
    }

    @ClientOrSelfMethodOnly
    public void updatePresence(TalkPresence presence) {
        ensureClientOrSelf();
        if(this.clientPresence == null) {
            this.clientPresence = presence;
        } else {
            this.clientPresence.updateWith(presence);
        }
    }
    @ClientOrSelfMethodOnly
    public void modifyPresence(TalkPresence presence, Set<String> fields) {
        ensureClientOrSelf();
        if(this.clientPresence == null) {
            throw new RuntimeException("try to modify empty presence");
        } else {
            this.clientPresence.updateWith(presence,fields);
        }
    }
    @ClientMethodOnly
    public void updateRelationship(TalkRelationship relationship) {
        ensureClient();
        if(this.clientRelationship == null) {
            this.clientRelationship = relationship;
        } else {
            this.clientRelationship.updateWith(relationship);
        }
        if(this.clientRelationship.isRelated()) {
            markAsRelated();
        }
    }

    @GroupMethodOnly
    public void updateGroupPresence(TalkGroup group) {
        ensureGroup();
        if(this.groupPresence == null) {
            if(group.getGroupId() != null) {
                groupId = group.getGroupId();
            }
            if(group.getGroupTag() != null) {
                groupTag = group.getGroupTag();
            }
            this.groupPresence = group;
        } else {
            this.groupPresence.updateWith(group);
        }
    }

    @GroupMethodOnly
    public void updateGroupMember(TalkGroupMember member) {
        ensureGroup();
        if(this.groupMember == null) {
            this.groupMember = member;
        } else {
            this.groupMember.updateWith(member);
        }
        if(this.groupMember.isInvolved()) {
            markAsRelated();
        }
    }

    public static TalkClientContact createGroupContact() {
        String groupTag = UUID.randomUUID().toString();
        TalkClientContact groupContact = new TalkClientContact(TYPE_GROUP);
        groupContact.updateGroupTag(groupTag);
        return groupContact;
    }
}
