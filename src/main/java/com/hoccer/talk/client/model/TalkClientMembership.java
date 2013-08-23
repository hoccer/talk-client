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
            TalkGroupMember my = this.member;
            my.setEncryptedGroupKey(member.getEncryptedGroupKey());
            my.setRole(member.getRole());
            my.setState(member.getState());
            my.setMemberKeyId(member.getMemberKeyId());
            my.setLastChanged(member.getLastChanged());
        }
    }

}
