package com.hoccer.talk.client;

import com.hoccer.talk.client.model.TalkClientContact;

public interface ITalkContactsListener {

    void onContactAdded(TalkClientContact contact);
    void onContactRemoved(TalkClientContact contact);

    void onClientPresenceChanged(TalkClientContact contact);
    void onClientRelationshipChanged(TalkClientContact contact);

    void onGroupPresenceChanged(TalkClientContact contact);
    void onGroupMembershipChanged(TalkClientContact contact);

}
