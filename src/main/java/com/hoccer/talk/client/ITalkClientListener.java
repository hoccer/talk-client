package com.hoccer.talk.client;

public interface ITalkClientListener extends ITalkContactsListener, ITalkMessagesListener, ITalkStateListener {;

    void onPushRegistrationRequested();

}
