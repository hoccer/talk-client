package com.hoccer.talk.client;

public interface ITalkClientListener extends ITalkContactListener, ITalkMessageListener, ITalkStateListener {;

    void onPushRegistrationRequested();

}
