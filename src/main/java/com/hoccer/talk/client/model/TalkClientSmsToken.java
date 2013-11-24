package com.hoccer.talk.client.model;

import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;

@DatabaseTable(tableName = "clientSmsToken")
public class TalkClientSmsToken {

    @DatabaseField(generatedId = true)
    int smsTokenId;

    @DatabaseField(width = 64)
    String sender;

    @DatabaseField(width = 200)
    String token;

    @DatabaseField(width = 200)
    String body;

    public TalkClientSmsToken() {
    }

    public int getSmsTokenId() {
        return smsTokenId;
    }

    public String getSender() {
        return sender;
    }

    public void setSender(String sender) {
        this.sender = sender;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }

}
