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

    @DatabaseField
    private String messageTag;

    @DatabaseField(canBeNull = true, foreign = true, foreignAutoRefresh = true)
    private TalkMessage message;

    @DatabaseField(canBeNull = true, foreign = true, foreignAutoRefresh = true)
    private TalkDelivery incomingDelivery;

    @DatabaseField(canBeNull = true, foreign = true, foreignAutoRefresh = true)
    private TalkDelivery outgoingDelivery;

    public TalkClientMessage() {

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

    }

    public void updateOutgoing(TalkDelivery delivery) {

    }

}
