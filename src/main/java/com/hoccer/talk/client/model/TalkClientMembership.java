package com.hoccer.talk.client.model;

import com.hoccer.talk.model.TalkGroupMember;
import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;

@DatabaseTable(tableName = "clientMembership")
public class TalkClientMembership {

    @DatabaseField(generatedId = true)
    private int clientMembershipId;

    @DatabaseField(foreign = true, foreignAutoRefresh = false)
    private TalkClientContact groupContact;

    @DatabaseField(foreign = true, foreignAutoRefresh = false)
    private TalkClientContact clientContact;

    @DatabaseField(foreign = true, foreignAutoRefresh = true, canBeNull = true)
    private TalkGroupMember member;

    public TalkClientMembership() {
    }

    public int getClientMembershipId() {
        return clientMembershipId;
    }

    public TalkClientContact getGroupContact() {
        return groupContact;
    }

    public void setGroupContact(TalkClientContact groupContact) {
        this.groupContact = groupContact;
    }

    public TalkClientContact getClientContact() {
        return clientContact;
    }

    public void setClientContact(TalkClientContact clientContact) {
        this.clientContact = clientContact;
    }

    public TalkGroupMember getMember() {
        return member;
    }

    public void updateGroupMember(TalkGroupMember member) {
        if(this.member == null) {
            this.member = member;
        } else {
            this.member.updateWith(member);
        }
    }

    public boolean hasLatestGroupKey() {
        if (member != null && member.getSharedKeyId() != null && groupContact != null && groupContact.getGroupPresence() != null) {
            if (member.getSharedKeyId().equals(groupContact.getGroupPresence().getSharedKeyId())) {
                return true;
            } else {
                if (member.getSharedKeyDate().getTime() >= groupContact.getGroupPresence().getKeyDate().getTime()) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean hasGroupKeyCryptedWithLatestPublicKey() {
        if (member != null && clientContact != null && clientContact.getPublicKey() != null) {
            String clientKeyId = clientContact.getPublicKey().getKeyId();
            if (clientKeyId != null) {
                return member.getMemberKeyId() != null && clientKeyId.equals(member.getMemberKeyId());
            }
        }
        return false;
    }

}
