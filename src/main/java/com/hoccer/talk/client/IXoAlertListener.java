package com.hoccer.talk.client;


public interface IXoAlertListener {

    public void onInternalAlert(String title, String message);
    public void onAlertMessageReceived(String message);

}
