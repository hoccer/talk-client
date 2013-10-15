package com.hoccer.talk.client;

import com.hoccer.talk.client.model.TalkClientMessage;

import java.util.List;

public interface IXoUnseenListener {

    public void onUnseenMessages(List<TalkClientMessage> unseenMessages, boolean notify);

}
