package com.hoccer.talk.client.model;

import com.google.appengine.api.blobstore.ByteRange;
import com.hoccer.talk.client.HoccerTalkClient;
import com.hoccer.talk.client.TalkClientDatabase;
import com.hoccer.talk.client.TalkTransfer;
import com.hoccer.talk.client.TalkTransferAgent;
import com.hoccer.talk.crypto.AESCryptor;
import com.hoccer.talk.model.TalkAttachment;
import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;
import org.apache.http.*;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.log4j.Logger;
import org.bouncycastle.util.encoders.Hex;

import java.io.*;
import java.sql.SQLException;
import java.util.Date;
import java.util.UUID;

@DatabaseTable(tableName = "clientDownload")
public class TalkClientDownload extends TalkTransfer {

    private final static Logger LOG = Logger.getLogger(TalkClientDownload.class);

    public enum State {
        NEW, REQUESTED, STARTED, DECRYPTING, COMPLETE, FAILED
    }

    @DatabaseField(generatedId = true)
    private int clientDownloadId;

    @DatabaseField
    private Type type;

    @DatabaseField
    private State state;


    @DatabaseField
    private int contentLength;

    @DatabaseField(width = 128)
    private String contentType;

    @DatabaseField(width = 64)
    private String mediaType;


    /** URL to download */
    @DatabaseField(width = 2000)
    private String downloadUrl;

    @DatabaseField(width = 2000)
    private String downloadFile;

    @DatabaseField
    private int downloadProgress;


    @DatabaseField(width = 128)
    private String decryptionKey;

    @DatabaseField(width = 2000)
    private String decryptedFile;


    public TalkClientDownload() {
        super(Direction.DOWNLOAD);
        this.state = State.NEW;
        this.downloadProgress = 0;
        this.contentLength = -1;
    }

    /**
     * Initialize this download as an avatar download
     *
     * @param url to download
     * @param id for avatar, identifying what the avatar belongs to
     * @param timestamp for avatar, takes care of collisions over id
     */
    public void initializeAsAvatar(String url, String id, Date timestamp) {
        LOG.info("initializeAsAvatar(" + url + ")");
        this.type = Type.AVATAR;
        this.downloadUrl = url;
        this.downloadFile = id + "-" + timestamp.getTime() + ".png";
    }

    public void initializeAsAttachment(TalkAttachment attachment, byte[] key) {
        LOG.info("initializeAsAttachment(" + attachment.getUrl() + ")");

        this.type = Type.ATTACHMENT;

        this.contentLength = Integer.valueOf(attachment.getContentSize());
        this.contentType = attachment.getMimeType();
        this.mediaType = attachment.getMediaType();

        this.downloadUrl = attachment.getUrl();
        this.downloadFile = attachment.getFilename();
        if(this.downloadFile == null) {
            this.downloadFile = UUID.randomUUID().toString();
        }
        if(key != null) {
            this.decryptionKey = bytesToHex(key);
            this.decryptedFile = this.downloadFile;
        }

        LOG.info("attachment filename " + this.downloadFile);
    }


    public int getClientDownloadId() {
        return clientDownloadId;
    }

    public Type getType() {
        return type;
    }

    public State getState() {
        return state;
    }


    public String getDownloadUrl() {
        return downloadUrl;
    }

    public String getDownloadFile() {
        return downloadFile;
    }

    public long getDownloadProgress() {
        return downloadProgress;
    }


    public int getContentLength() {
        return contentLength;
    }

    public String getContentType() {
        return contentType;
    }

    public String getMediaType() {
        return this.mediaType;
    }


    public File getAvatarFile(File avatarDirectory) {
        return new File(avatarDirectory, this.downloadFile);
    }

    public File getAttachmentFile(File attachmentDirectory) {
        return new File(attachmentDirectory, this.downloadFile);
    }

    public File getAttachmentDecryptedFile(File filesDirectory) {
        return new File(filesDirectory, this.decryptedFile);
    }

    private String computeDownloadDestination(HoccerTalkClient client) {
        if(this.downloadFile == null) {
            LOG.warn("file without filename");
            return null;
        }
        if(type == Type.AVATAR) {
            return client.getAvatarDirectory() + File.separator + this.downloadFile;
        }
        if(type == Type.ATTACHMENT) {
            return client.getAttachmentDirectory() + File.separator + this.downloadFile;
        }
        LOG.info("file of unhandled type");
        return null;
    }

    public void performDownloadAttempt(TalkTransferAgent agent) {
        TalkClientDatabase database = agent.getDatabase();
        HoccerTalkClient talkClient = agent.getClient();
        String filename = computeDownloadDestination(talkClient);
        if(filename == null) {
            LOG.error("could not determine filename for download " + clientDownloadId);
            return;
        }
        LOG.info("filename is " + filename);

        boolean changed = false;
        if(state == State.COMPLETE) {
            LOG.warn("Tried to perform completed download");
            return;
        }
        if(state == State.FAILED) {
            LOG.warn("Tried to perform failed download");
        }
        if(state == State.NEW) {
            switchState(agent, State.STARTED);
            changed = true;
        }

        if(changed) {
            try {
                database.saveClientDownload(this);
            } catch (SQLException e) {
                LOG.error("SQL error", e);
            }
        }

        int failureCount = 0;
        while(failureCount < 3) {
            boolean success = performOneRequest(agent, filename);
            if(!success) {
                LOG.info("download attempt failed");
                failureCount++;
            }
            if(state == State.FAILED) {
                LOG.error("download finally failed");
                break;
            }
            if(state == State.DECRYPTING) {
                LOG.info("download will now be decrypted");
                try {
                    performDecryption(agent);
                } catch (Exception e) {
                    LOG.error("error decrypting", e);
                    markFailed(agent);
                    break;
                }
                switchState(agent, State.COMPLETE);
                break;
            }
            if(state == State.COMPLETE) {
                LOG.info("download is complete");
                break;
            }
        }

        LOG.info("download attempt finished");

        try {
            database.saveClientDownload(this);
        } catch (SQLException e) {
            LOG.error("SQL error", e);
        }
    }

    private boolean performOneRequest(TalkTransferAgent agent, String filename) {
        HttpClient client = agent.getHttpClient();
        TalkClientDatabase database = agent.getDatabase();
        RandomAccessFile raf = null;
        FileDescriptor fd = null;
        try {
            LOG.info("GET " + downloadUrl + " starting");
            // create the GET request
            HttpGet request = new HttpGet(downloadUrl);
            // if we have a content length then we can do range requests
            if(contentLength != -1) {
                long last = contentLength - 1;
                request.addHeader("Range", "bytes=" + downloadProgress + "-" + last);
            }
            // start performing the request
            HttpResponse response = client.execute(request);
            // process status code
            int sc = response.getStatusLine().getStatusCode();
            LOG.info("GET " + downloadUrl + " returned status " + sc);
            if(sc != HttpStatus.SC_OK && sc != HttpStatus.SC_PARTIAL_CONTENT) {
                // client error - mark as failed
                if(sc >= 400 && sc <= 499) {
                    markFailed(agent);
                }
                return false;
            }
            // parse content length from response
            Header contentLengthHeader = response.getFirstHeader("Content-Length");
            int contentLengthValue = this.contentLength;
            if(contentLengthHeader != null) {
                String contentLengthString = contentLengthHeader.getValue();
                contentLengthValue = Integer.valueOf(contentLengthString);
                LOG.info("GET " + downloadUrl + " content length " + contentLengthValue);
                if(contentLength == -1) {
                    contentLength = contentLengthValue;
                }
            }
            // check we have a content length
            if(contentLength == -1) {
                LOG.error("GET " + downloadUrl + " unknown content length");
                markFailed(agent);
                return false;
            }
            // parse content range from response
            ByteRange contentRange = null;
            Header contentRangeHeader = response.getFirstHeader("Content-Range");
            if(contentRangeHeader != null) {
                String contentRangeString = contentRangeHeader.getValue();
                LOG.info("GET " + downloadUrl + " returned range " + contentRangeString);
                contentRange = ByteRange.parseContentRange(contentRangeString);
            }
            // remember content type if we don't have one yet
            Header contentTypeHeader = response.getFirstHeader("Content-Type");
            if(contentTypeHeader != null) {
                String contentTypeValue = contentTypeHeader.getValue();
                if(contentType == null) {
                    LOG.info("GET " + downloadUrl + " content type " + contentTypeValue);
                    contentType = contentTypeValue;
                }
            }
            // handle content
            HttpEntity entity = response.getEntity();
            InputStream is = entity.getContent();
            // create and open destination file
            File f = new File(filename);
            f.createNewFile();
            raf = new RandomAccessFile(f, "rw");
            raf.setLength(contentLength);
            fd = raf.getFD();
            // get ourselves a buffer
            byte[] buffer = new byte[1<<16];
            // determine what to copy
            int bytesStart = downloadProgress;
            int bytesToGo = contentLength - downloadProgress;
            if(contentRange != null) {
                if(contentRange.getStart() != downloadProgress) {
                    LOG.error("GET " + downloadUrl + " server returned wrong offset");
                    markFailed(agent);
                    return false;
                }
                if(contentRange.hasEnd()) {
                    if(contentRange.getEnd() != (contentLength - 1)) {
                        LOG.error("GET " + downloadUrl + " server returned wrong end");
                        markFailed(agent);
                        return false;
                    }
                    bytesToGo = (int)(contentRange.getEnd() - contentRange.getStart() + 1);
                }
            }
            if(contentLengthValue != bytesToGo) {
                LOG.error("GET " + downloadUrl + " returned bad content length");
                markFailed(agent);
                return false;
            }
            LOG.info("GET " + downloadUrl + " still needs " + bytesToGo + " bytes");
            // seek to start of region
            raf.seek(bytesStart);
            // copy data
            while(bytesToGo > 0) {
                LOG.trace("to go: " + bytesToGo);
                LOG.trace("at progress: " + downloadProgress);
                // determine how much to copy
                int bytesToRead = Math.min(buffer.length, bytesToGo);
                // perform the copy
                int bytesRead = is.read(buffer, 0, bytesToRead);
                LOG.trace("reading " + bytesToRead + " returned " + bytesRead);
                if(bytesRead == -1) {
                    LOG.warn("eof!?");
                    return false;
                }
                raf.write(buffer, 0, bytesRead);
                // sync the file
                fd.sync();
                // update state
                downloadProgress += bytesRead;
                bytesToGo -= bytesRead;
                // update db
                database.saveClientDownload(this);
                // call listeners
                agent.onDownloadProgress(this);
            }
            // update state
            if(downloadProgress == contentLength && state != State.COMPLETE) {
                if(decryptionKey != null) {
                    switchState(agent, State.DECRYPTING);
                } else {
                    switchState(agent, State.COMPLETE);
                }
            }
            // update db
            database.saveClientDownload(this);
            // close streams
            fd = null;
            raf.close();
            is.close();
        } catch (Exception e) {
            LOG.error("download exception", e);
            return false;
        } finally {
            if(fd != null) {
                try {
                    fd.sync();
                } catch (SyncFailedException sfe) {
                    LOG.warn("sync failed while handling download exception", sfe);
                }
            }
            try {
                database.saveClientDownload(this);
            } catch (SQLException sqle) {
                LOG.warn("save failed while handling download exception", sqle);
            }
        }

        return true;
    }

    private void performDecryption(TalkTransferAgent agent) {
        LOG.info("performing decryption of download " + clientDownloadId);

        File source = getAttachmentFile(new File(agent.getClient().getAttachmentDirectory()));

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
        }
    }

    private void markFailed(TalkTransferAgent agent) {
        switchState(agent, State.FAILED);
    }

    private void switchState(TalkTransferAgent agent, State newState) {
        LOG.info("[" + clientDownloadId + "] switching to state " + newState);
        state = newState;
        agent.onDownloadStateChanged(this);
    }

    /* XXX junk */
    private static String bytesToHex(byte[] bytes) {
        final char[] hexArray = {'0','1','2','3','4','5','6','7','8','9','A','B','C','D','E','F'};
        char[] hexChars = new char[bytes.length * 2];
        int v;
        for ( int j = 0; j < bytes.length; j++ ) {
            v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }

}
