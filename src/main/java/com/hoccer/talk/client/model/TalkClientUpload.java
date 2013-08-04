package com.hoccer.talk.client.model;

import com.hoccer.talk.client.HoccerTalkClient;
import com.hoccer.talk.client.TalkClientDatabase;
import com.hoccer.talk.client.TalkTransfer;
import com.hoccer.talk.client.TalkTransferAgent;
import com.hoccer.talk.rpc.ITalkRpcServer;
import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpPut;
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
    private int clientUploadId;

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

    public int getClientUploadId() {
        return clientUploadId;
    }

    public State getState() {
        return state;
    }

    public Type getType() {
        return type;
    }

    public void initializeAsAvatar() {
        this.type = Type.AVATAR;
    }

    public void performUploadAttempt(TalkTransferAgent agent) {
        HttpClient client = agent.getHttpClient();
        TalkClientDatabase database = agent.getDatabase();
        HoccerTalkClient talkClient = agent.getClient();
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

        boolean success = performOneRequest(talkClient.getTransferAgent());
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

    private boolean performOneRequest(TalkTransferAgent agent) {
        HttpClient client = agent.getHttpClient();
        try {
            HttpHead headRequest = new HttpHead(uploadUrl);
            HttpResponse headResponse = client.execute(headRequest);
            int headSc = headResponse.getStatusLine().getStatusCode();
            LOG.info("HEAD returned " + headSc);
            if(headSc != HttpStatus.SC_OK) {
                // client error - mark as failed
                if(headSc >= 400 && headSc <= 499) {
                    markFailed(agent);
                }
                return false;
            }

            Header[] hdrs = headResponse.getAllHeaders();
            for(int i = 0; i < hdrs.length; i++) {
                Header h = hdrs[i];
                LOG.info("HEAD header: " + h.getName() + ": " + h.getValue());
            }

            return false;

/*            HttpPut putRequest = new HttpPut(uploadUrl);
            HttpResponse putResponse = client.execute(putRequest);
            int putSc = putResponse.getStatusLine().getStatusCode();
            LOG.info("PUT returned " + putSc);
            if(putSc != HttpStatus.SC_OK) {
                // client error - mark as failed
                if(putSc >= 400 && putSc <= 499) {
                    markFailed(agent);
                }
                return false;
            }*/
        } catch (IOException e) {
            e.printStackTrace();
        }
        return true;
    }

    private void markFailed(TalkTransferAgent agent) {
        switchState(agent, State.FAILED);
    }

    private void switchState(TalkTransferAgent agent, State newState) {
        LOG.info("[" + clientUploadId + "] switching to state " + newState);
        state = newState;
        agent.onUploadStateChanged(this);
    }

}
