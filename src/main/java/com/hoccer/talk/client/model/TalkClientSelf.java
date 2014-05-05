package com.hoccer.talk.client.model;

import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;

@DatabaseTable(tableName = "clientSelf")
public class TalkClientSelf {

    @DatabaseField(generatedId = true)
    private int selfId;

    @DatabaseField(canBeNull = true)
    private String registrationName;

    @DatabaseField
    private boolean registrationConfirmed;

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

    public boolean isRegistrationConfirmed() {
        return registrationConfirmed;
    }

    public String getSrpSalt() {
        return srpSalt;
    }

    public String getSrpSecret() {
        return srpSecret;
    }

    public String getRegistrationName() {
        return registrationName;
    }

    public void setRegistrationName(String registrationName) {
        this.registrationName = registrationName;
    }

    public void confirmRegistration() {
        registrationConfirmed = true;
    }

    public void provideCredentials(String salt, String secret) {
        this.srpSalt = salt;
        this.srpSecret = secret;
    }

}
