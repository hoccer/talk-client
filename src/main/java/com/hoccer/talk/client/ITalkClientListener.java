package com.hoccer.talk.client;

public interface ITalkClientListener {
    void onClientStateChange(HoccerTalkClient client, int state);
}
