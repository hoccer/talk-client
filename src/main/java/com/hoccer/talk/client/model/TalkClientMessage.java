package com.hoccer.talk.client.model;

import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;

@DatabaseTable(tableName = "clientMessage")
public class TalkClientMessage {

    @DatabaseField(generatedId = true)
    int clientMessageId;

}
