package com.hoccer.talk.client;

import com.hoccer.talk.client.model.TalkClientMessage;

public interface IXoMessageListener {

    void onMessageAdded(TalkClientMessage message);
    void onMessageRemoved(TalkClientMessage message);
    void onMessageStateChanged(TalkClientMessage message);

}
