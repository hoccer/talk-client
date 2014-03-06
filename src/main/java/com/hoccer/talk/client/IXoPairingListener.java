package com.hoccer.talk.client;

public interface IXoPairingListener {

    void onTokenPairingSucceeded(String token);
    void onTokenPairingFailed(String token);
}
