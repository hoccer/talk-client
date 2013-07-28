package com.hoccer.talk.client.model;

import com.hoccer.talk.client.HoccerTalkClient;
import com.hoccer.talk.client.TalkClientDatabase;
import com.hoccer.talk.client.TalkTransfer;
import com.hoccer.talk.rpc.ITalkRpcServer;
import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpHead;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.sql.SQLException;

@DatabaseTable(tableName = "clientUpload")
public class TalkClientUpload extends TalkTransfer {

    private final static Logger LOG = Logger.getLogger(TalkClientUpload.class);

    public enum State {
        NEW, REGISTERED, STARTED, COMPLETE, FAILED
    }

    @DatabaseField(generatedId = true)
    private int clientDownloadId;

    @DatabaseField
    private Type type;

    @DatabaseField
    private State state;

    @DatabaseField(width = 2000)
    private String uploadUrl;

    @DatabaseField(width = 2000)
    private String downloadUrl;

    @DatabaseField
    private int contentLength;

    @DatabaseField(width = 128)
    private String contentType;

    @DatabaseField
    private int progress;

    @DatabaseField
    private int failedAttempts;

    public TalkClientUpload() {
        super(Direction.UPLOAD);
    }

    public void performUploadAttempt(HttpClient client, TalkClientDatabase database, HoccerTalkClient talkClient) {
        boolean changed = false;
        if(state == State.COMPLETE) {
            LOG.warn("Tried to perform completed upload");
            return;
        }
        if(state == State.FAILED) {
            LOG.warn("Tried to perform failed upload");
        }
        if(state == State.NEW) {
            performRegistration(talkClient);
            changed = true;
        }
        if(state == State.REGISTERED) {
            state = State.STARTED;
            changed = true;
        }

        if(changed) {
            try {
                database.saveClientUpload(this);
            } catch (SQLException e) {
                LOG.error("SQL error", e);
            }
        }

        boolean success = performOneRequest(client);
        if(!success) {
            failedAttempts++;
        }

        try {
            database.saveClientUpload(this);
        } catch (SQLException e) {
            LOG.error("SQL error", e);
        }
    }

    private void performRegistration(HoccerTalkClient talkClient) {
        ITalkRpcServer.FileHandles handles = talkClient.getServerRpc().createFileForStorage(contentLength);
        uploadUrl = handles.uploadUrl;
        downloadUrl = handles.downloadUrl;
        state = State.REGISTERED;
    }

    private boolean performOneRequest(HttpClient client) {
        try {
            HttpHead headRequest = new HttpHead(uploadUrl);
            HttpResponse headResponse = client.execute(headRequest);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return true;
    }

}
