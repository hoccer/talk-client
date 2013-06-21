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

}
