package com.hoccer.talk.client.model;

import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;

@DatabaseTable(tableName = "clientSelf")
public class TalkClientSelf {

    @DatabaseField(generatedId = true)
    private int selfId;

    @DatabaseField(width = 1024)
    private String srpSalt;

    @DatabaseField(width = 1024)
    private String srpSecret;

    public TalkClientSelf() {
    }

    public TalkClientSelf(String srpSalt, String srpSecret) {
        this.srpSalt   = srpSalt;
        this.srpSecret = srpSecret;
    }

    public String getSrpSalt() {
        return srpSalt;
    }

    public String getSrpSecret() {
        return srpSecret;
    }

    public void update(TalkClientSelf credentials) {
        this.srpSalt = credentials.srpSalt;
        this.srpSecret = credentials.srpSecret;
    }

}
