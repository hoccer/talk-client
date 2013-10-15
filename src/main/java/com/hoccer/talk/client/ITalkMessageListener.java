package com.hoccer.talk.client;

import com.hoccer.talk.client.model.TalkClientMessage;

public interface ITalkMessageListener {

    void onMessageAdded(TalkClientMessage message);
    void onMessageRemoved(TalkClientMessage message);
    void onMessageStateChanged(TalkClientMessage message);

}
