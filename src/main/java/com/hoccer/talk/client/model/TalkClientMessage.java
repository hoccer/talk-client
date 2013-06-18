package com.hoccer.talk.client.model;

import com.hoccer.talk.model.TalkDelivery;
import com.hoccer.talk.model.TalkMessage;
import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;

@DatabaseTable(tableName = "clientMessage")
public class TalkClientMessage {

    @DatabaseField(generatedId = true)
    private int clientMessageId;

    @DatabaseField
    private String messageId;

    @DatabaseField(canBeNull = true)
    private String messageTag;

    @DatabaseField(canBeNull = true, foreign = true, foreignAutoRefresh = true)
    private TalkMessage message;

    @DatabaseField(canBeNull = true, foreign = true, foreignAutoRefresh = true)
    private TalkDelivery incomingDelivery;

    @DatabaseField(canBeNull = true, foreign = true, foreignAutoRefresh = true)
    private TalkDelivery outgoingDelivery;

    public TalkClientMessage() {
    }

    public boolean isIncoming() {
        return incomingDelivery != null;
    }

    public boolean isOutgoing() {
        return outgoingDelivery != null;
    }

    public String getMessageId() {
        return messageId;
    }

    public String getMessageTag() {
        return messageTag;
    }

    public TalkMessage getMessage() {
        return message;
    }

    public TalkDelivery getIncomingDelivery() {
        return incomingDelivery;
    }

    public TalkDelivery getOutgoingDelivery() {
        return outgoingDelivery;
    }

    public void updateIncoming(TalkDelivery delivery, TalkMessage message) {
        if(incomingDelivery == null) {
            incomingDelivery = delivery;
        } else {
            incomingDelivery.setState(delivery.getState());
            // XXX
        }
    }

    public void updateOutgoing(TalkDelivery delivery) {
        if(outgoingDelivery == null) {
            outgoingDelivery = delivery;
        } else {
            outgoingDelivery.setState(delivery.getState());
            // XXX
        }
    }

}
