package com.hoccer.talk.client.model;

import com.hoccer.talk.model.TalkDelivery;
import com.hoccer.talk.model.TalkMessage;
import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;
import org.apache.log4j.Logger;

import java.util.Date;
import java.util.Set;

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
    private TalkClientContact conversationContact;

    @DatabaseField(canBeNull = true, foreign = true, foreignAutoRefresh = true)
    private TalkClientContact senderContact;

    @DatabaseField(canBeNull = true, foreign = true, foreignAutoRefresh = true)
    private TalkMessage message;

    @DatabaseField(canBeNull = true, foreign = true, foreignAutoRefresh = true)
    private TalkDelivery incomingDelivery;

    @DatabaseField(canBeNull = true, foreign = true, foreignAutoRefresh = true)
    private TalkDelivery outgoingDelivery;

    @DatabaseField(canBeNull = true, foreign = true, foreignAutoRefresh = true)
    private TalkClientUpload attachmentUpload;

    @DatabaseField(canBeNull = true, foreign = true, foreignAutoRefresh = true)
    private TalkClientDownload attachmentDownload;

    @DatabaseField(width = 2048)
    private String text;

    @DatabaseField
    private boolean deleted;

    @DatabaseField
    private boolean seen;

    @DatabaseField
    private Date timestamp;

    @DatabaseField(width = 128)
    private String hmac;

    @DatabaseField(width = 1024)
    private String signature;


    @DatabaseField
    private boolean inProgress;

    public TalkClientMessage() {
        this.timestamp = new Date();
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

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getMessageTag() {
        return messageTag;
    }

    public void setMessageTag(String messageTag) {
        this.messageTag = messageTag;
    }

    public TalkClientContact getConversationContact() {
        return conversationContact;
    }

    public void setConversationContact(TalkClientContact conversationContact) {
        this.conversationContact = conversationContact;
    }

    public TalkClientContact getSenderContact() {
        return senderContact;
    }

    public void setSenderContact(TalkClientContact senderContact) {
        this.senderContact = senderContact;
    }

    public TalkMessage getMessage() {
        return message;
    }

    public void setMessage(TalkMessage message) {
        this.message = message;
    }

    public TalkDelivery getIncomingDelivery() {
        return incomingDelivery;
    }

    public void setIncomingDelivery(TalkDelivery incomingDelivery) {
        this.incomingDelivery = incomingDelivery;
    }

    public TalkDelivery getOutgoingDelivery() {
        return outgoingDelivery;
    }

    public void setOutgoingDelivery(TalkDelivery outgoingDelivery) {
        this.outgoingDelivery = outgoingDelivery;
    }

    public TalkClientUpload getAttachmentUpload() {
        return attachmentUpload;
    }

    public void setAttachmentUpload(TalkClientUpload attachmentUpload) {
        this.attachmentUpload = attachmentUpload;
    }

    public TalkClientDownload getAttachmentDownload() {
        return attachmentDownload;
    }

    public void setAttachmentDownload(TalkClientDownload attachmentDownload) {
        this.attachmentDownload = attachmentDownload;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public boolean isSeen() {
        return seen;
    }

    public void markAsSeen() {
        this.seen = true;
    }

    public void setProgressState(boolean state) {
        this.inProgress = state;
    }

    public boolean isInProgress() {
        return inProgress;
    }

    public boolean isDeleted() {
        return deleted;
    }

    public void markAsDeleted() {
        this.deleted = true;
    }

    public String getHmac() {
        return hmac;
    }

    public void setHmac(String hmac) {
        this.hmac = hmac;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }

    public Date getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Date timestamp) {
        this.timestamp = timestamp;
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
            Set<String> fields = delivery.nonNullFields();
            incomingDelivery.updateWith(delivery, fields);
            if(message.getTimeSent() != null) {
                this.timestamp = message.getTimeSent();
            }
        }
    }

    public void updateIncoming(TalkDelivery delivery) {
        if(outgoingDelivery != null) {
            LOG.error("incoming incremental update for outgoing message");
            return;
        }

        if(incomingDelivery == null) {
            LOG.error("incremental update for not yet received incoming delivery");
            return;
        }

        Set<String> fields = delivery.nonNullFields();
        incomingDelivery.updateWith(delivery, fields);
    }

    public void updateOutgoing(TalkDelivery delivery) {
        if(incomingDelivery != null) {
            LOG.warn("outgoing update for incoming message");
            return;
        }
        if(outgoingDelivery == null) {
            outgoingDelivery = delivery;
        } else {
            Set<String> fields = delivery.nonNullFields();
            outgoingDelivery.updateWith(delivery, fields);
        }
    }
 /*
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
    */

}
