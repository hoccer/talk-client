package com.hoccer.talk.client;

import com.hoccer.talk.client.model.TalkClientContact;

public interface ITalkClientListener {

    void onClientStateChange(HoccerTalkClient client, int state);

    void onClientPresenceChanged(TalkClientContact contact);
    void onClientRelationshipChanged(TalkClientContact contact);

    void onGroupPresenceChanged(TalkClientContact contact);
    void onGroupMembershipChanged(TalkClientContact contact);

}
