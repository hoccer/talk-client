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
import org.apache.http.client.methods.HttpHead;
import org.apache.log4j.Logger;

import java.io.File;
import java.sql.SQLException;

@DatabaseTable(tableName = "clientUpload")
public class TalkClientUpload extends TalkTransfer {

    private final static Logger LOG = Logger.getLogger(TalkClientUpload.class);

    public enum State {
        NEW, REGISTERED, ENCRYPTING, STARTED, COMPLETE, FAILED
    }

    @DatabaseField(generatedId = true)
    private int clientUploadId;

    @DatabaseField
    private Type type;

    @DatabaseField
    private State state;


    /** Plain data file */
    @DatabaseField(width = 2000)
    private String dataFile;
    /** Plain data size */
    @DatabaseField
    private int dataLength;

    /** Size of upload */
    @DatabaseField
    private int uploadLength;
    /** URL for upload */
    @DatabaseField(width = 2000)
    private String uploadUrl;


    @DatabaseField(width = 2000)
    private String downloadUrl;


    @DatabaseField(width = 128)
    private String contentType;

    @DatabaseField
    private int progress;


    public TalkClientUpload() {
        super(Direction.UPLOAD);
        this.state = State.NEW;
        this.dataLength = -1;
        this.uploadLength = -1;
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

    public void initializeAsAvatar(String path) {
        this.type = Type.AVATAR;

        File file = new File(path);
        long fileSize = file.length();

        this.dataFile = path;
        this.dataLength = (int)fileSize;
    }

    public void performUploadRegistration(TalkTransferAgent agent) {
        TalkClientDatabase database = agent.getDatabase();
        if(state == State.NEW) {
            performRegistration(agent);
            try {
                database.saveClientUpload(this);
            } catch (SQLException e) {
                LOG.error("SQL error", e);
            }
        } else {
            LOG.warn("Tried to register already-registered upload");
        }
    }

    public void performUploadAttempt(TalkTransferAgent agent) {
        TalkClientDatabase database = agent.getDatabase();
        HoccerTalkClient talkClient = agent.getClient();

        boolean changed = false;
        if(state == State.NEW) {
            LOG.warn("Tried to perform unregistered upload");
            return;
        }
        if(state == State.COMPLETE) {
            LOG.warn("Tried to perform completed upload");
            return;
        }
        if(state == State.FAILED) {
            LOG.warn("Tried to perform failed upload");
            return;
        }

        if(state == State.REGISTERED) {
            switchState(agent, State.ENCRYPTING);
            changed = true;
        }

        if(state == State.ENCRYPTING) {
            performEncryption(agent);
            changed = true;
        }

        if(state == State.STARTED) {
            int failureCount = 0;
            while(failureCount < 5) {
                boolean success = performOneRequest(talkClient.getTransferAgent());
                if(!success) {
                    failureCount++;
                }
                if(state == State.COMPLETE) {
                    break;
                }
                if(state == State.FAILED) {
                    break;
                }
            }
        }

        if(changed) {
            try {
                database.saveClientUpload(this);
            } catch (SQLException e) {
                LOG.error("SQL error", e);
            }
        }
    }

    private void performRegistration(TalkTransferAgent agent) {
        HoccerTalkClient talkClient = agent.getClient();
        ITalkRpcServer.FileHandles handles = talkClient.getServerRpc().createFileForStorage(this.uploadLength);
        uploadUrl = handles.uploadUrl;
        downloadUrl = handles.downloadUrl;
        switchState(agent, State.REGISTERED);
    }

    private void performEncryption(TalkTransferAgent agent) {
        LOG.info("performing encryption of upload " + clientUploadId);

/*        File source = getAttachmentFile(new File(agent.getClient().getAttachmentDirectory()));

        File destinationDir = new File(agent.getClient().getFilesDirectory());

        File destination = new File(destinationDir, decryptedFile);
        if(destination.exists()) {
            destination.delete();
        }

        byte[] key = Hex.decode(decryptionKey);

        int bytesToDecrypt = (int)source.length();

        try {
            byte[] buffer = new byte[1 << 16];
            InputStream is = new FileInputStream(source);
            OutputStream os = new FileOutputStream(destination);
            OutputStream dos = AESCryptor.decryptingOutputStream(os, key, AESCryptor.NULL_SALT);

            int bytesToGo = bytesToDecrypt;
            while(bytesToGo > 0) {
                int bytesToCopy = Math.min(buffer.length, bytesToGo);
                int bytesRead = is.read(buffer, 0, bytesToCopy);
                dos.write(buffer, 0, bytesRead);
                bytesToGo -= bytesRead;
            }

            dos.flush();
            dos.close();
            os.flush();
            os.close();
            is.close();
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }*/
    }

    private boolean performOneRequest(TalkTransferAgent agent) {
        HttpClient client = agent.getHttpClient();
        try {
            HttpHead headRequest = new HttpHead(uploadUrl);
            HttpResponse headResponse = client.execute(headRequest);
            int headSc = headResponse.getStatusLine().getStatusCode();
            LOG.info("HEAD " + uploadUrl + " status " + headSc);
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
                LOG.info("HEAD " + uploadUrl + " header " + h.getName() + ": " + h.getValue());
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
        } catch (Exception e) {
            LOG.error("exception in upload", e);
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
