package com.hoccer.talk.client;

import com.hoccer.talk.client.model.TalkClientContact;
import com.hoccer.talk.client.model.TalkClientMessage;

public interface ITalkClientListener {

    void onClientStateChange(HoccerTalkClient client, int state);

    void onPushRegistrationRequested();

    void onContactAdded(TalkClientContact contact);
    void onContactRemoved(TalkClientContact contact);
    void onClientPresenceChanged(TalkClientContact contact);
    void onClientRelationshipChanged(TalkClientContact contact);
    void onGroupPresenceChanged(TalkClientContact contact);
    void onGroupMembershipChanged(TalkClientContact contact);

    void onMessageAdded(TalkClientMessage message);
    void onMessageRemoved(TalkClientMessage message);
    void onMessageStateChanged(TalkClientMessage message);

}
