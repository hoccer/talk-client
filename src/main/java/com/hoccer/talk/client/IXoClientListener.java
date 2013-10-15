package com.hoccer.talk.client;

public interface IXoClientListener extends IXoContactListener, IXoMessageListener, IXoStateListener {;

    void onPushRegistrationRequested();

}
