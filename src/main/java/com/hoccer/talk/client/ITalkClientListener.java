package com.hoccer.talk.client;

import com.hoccer.talk.client.model.TalkClientContact;

public interface ITalkClientListener {

    void onClientStateChange(HoccerTalkClient client, int state);

    void onContactPresenceChanged(TalkClientContact contact);
    void onContactRelationshipChanged(TalkClientContact contact);

}
