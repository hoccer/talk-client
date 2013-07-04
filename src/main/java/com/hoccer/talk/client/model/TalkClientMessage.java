package com.hoccer.talk.client.model;

import com.hoccer.talk.model.TalkDelivery;
import com.hoccer.talk.model.TalkMessage;
import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;
import org.apache.log4j.Logger;

@DatabaseTable(tableName = "clientMessage")
public class TalkClientMessage {

    private static final Logger LOG = Logger.getLogger(TalkClientMessage.class);

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

    public int getClientMessageId() {
        return clientMessageId;
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
        if(outgoingDelivery != null) {
            LOG.warn("incoming update for outgoing message");
            return;
        }
        this.message = message;
        if(incomingDelivery == null) {
            incomingDelivery = delivery;
        } else {
            updateDelivery(incomingDelivery, delivery);
        }
    }

    public void updateOutgoing(TalkDelivery delivery) {
        if(incomingDelivery != null) {
            LOG.warn("outgoing update for incoming message");
            return;
        }
        if(outgoingDelivery == null) {
            outgoingDelivery = delivery;
        } else {
            updateDelivery(outgoingDelivery, delivery);
        }
    }

    private void updateDelivery(TalkDelivery currentDelivery, TalkDelivery newDelivery) {
        currentDelivery.setState(newDelivery.getState());
        currentDelivery.setSenderId(newDelivery.getSenderId());
        currentDelivery.setGroupId(newDelivery.getGroupId());
        currentDelivery.setReceiverId(newDelivery.getReceiverId());
        currentDelivery.setKeyId(newDelivery.getKeyId());
        currentDelivery.setKeyCiphertext(newDelivery.getKeyCiphertext());
        currentDelivery.setTimeAccepted(newDelivery.getTimeAccepted());
        currentDelivery.setTimeChanged(newDelivery.getTimeChanged());
        currentDelivery.setTimeUpdatedIn(newDelivery.getTimeUpdatedIn());
        currentDelivery.setTimeUpdatedOut(newDelivery.getTimeUpdatedOut());
    }

}
