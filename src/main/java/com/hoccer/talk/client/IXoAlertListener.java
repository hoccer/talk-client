package com.hoccer.talk.client;


/**
 * This interface is used by the client to handle alert messages which have been generated
 * internally or received from the server.
 */
public interface IXoAlertListener {

    /**
     * Client issued an internal alert message.
     *
     * @param title   The title of the alert message
     * @param message The text of the alert message
     */
    public void onInternalAlert(String title, String message);

    /**
     * Client received an alert message from the server.
     *
     * @param message The alert message as string
     */
    public void onAlertMessageReceived(String message);

}
