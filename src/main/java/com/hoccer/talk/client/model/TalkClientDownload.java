package com.hoccer.talk.client.model;

import com.google.appengine.api.blobstore.ByteRange;
import com.hoccer.talk.client.HoccerTalkClient;
import com.hoccer.talk.client.TalkClientDatabase;
import com.hoccer.talk.client.TalkTransfer;
import com.hoccer.talk.client.TalkTransferAgent;
import com.hoccer.talk.model.TalkAttachment;
import com.hoccer.talk.model.TalkPresence;
import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;
import org.apache.http.*;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.log4j.Logger;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.sql.SQLException;
import java.util.Date;

@DatabaseTable(tableName = "clientDownload")
public class TalkClientDownload extends TalkTransfer {

    private final static Logger LOG = Logger.getLogger(TalkClientDownload.class);

    public enum State {
        NEW, STARTED, COMPLETE, FAILED
    }

    @DatabaseField(generatedId = true)
    private int clientDownloadId;

    @DatabaseField
    private Type type;

    @DatabaseField
    private State state;

    @DatabaseField(width = 2000)
    private String url;

    @DatabaseField(width = 2000)
    private String file;

    @DatabaseField
    private int contentLength;

    @DatabaseField(width = 128)
    private String contentType;

    @DatabaseField
    private int progress;

    @DatabaseField
    private long failedAttempts;

    public TalkClientDownload() {
        super(Direction.DOWNLOAD);
        this.state = State.NEW;
        this.progress = 0;
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
        this.type = Type.AVATAR;
        this.url = url;
        this.file = id + "-" + timestamp.getTime() + ".png";
    }

    public void initializeAsAttachment(TalkAttachment attachment) {
        type = Type.ATTACHMENT;
        // XXX
        LOG.error("Unimplemented");
    }

    public int getClientDownloadId() {
        return clientDownloadId;
    }

    public String getUrl() {
        return url;
    }

    private void setUrl(String url) {
        this.url = url;
    }

    public int getContentLength() {
        return contentLength;
    }

    private void setContentLength(int contentLength) {
        this.contentLength = contentLength;
    }

    public String getContentType() {
        return contentType;
    }

    private void setContentType(String contentType) {
        this.contentType = contentType;
    }


    public Type getType() {
        return type;
    }

    public State getState() {
        return state;
    }

    public String getFile() {
        return file;
    }

    public long getProgress() {
        return progress;
    }

    public File getAvatarFile(File avatarDirectory) {
        return new File(avatarDirectory, this.file);
    }

    private String computeFileName(HoccerTalkClient client) {
        if(this.file == null) {
            LOG.warn("file without filename");
            return null;
        }
        if(type == Type.AVATAR) {
            return client.getAvatarDirectory() + File.separator + this.file;
        }
        LOG.info("file of unhandled type");
        return null;
    }

    public void performDownloadAttempt(TalkTransferAgent agent) {
        TalkClientDatabase database = agent.getDatabase();
        HoccerTalkClient talkClient = agent.getClient();
        String filename = computeFileName(talkClient);
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

        boolean success = performOneRequest(agent, filename);
        if(!success) {
            LOG.info("download attempt failed");
            failedAttempts++;
        }

        LOG.info("download attempt succeeded");

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
            LOG.info("GET " + url + " starting");
            // create the GET request
            HttpGet request = new HttpGet(url);
            // if we have a content length then we can do range requests
            if(contentLength != -1) {
                long last = contentLength - 1;
                request.addHeader("Range", "bytes=" + progress + "-" + last);
            }
            // start performing the request
            HttpResponse response = client.execute(request);
            // process status code
            int sc = response.getStatusLine().getStatusCode();
            LOG.info("GET " + url + " returned status " + sc);
            if(sc != HttpStatus.SC_OK && sc != HttpStatus.SC_PARTIAL_CONTENT) {
                // client error - mark as failed
                if(sc >= 400 && sc <= 499) {
                    markFailed(agent);
                }
                return false;
            }
            // parse content length from response
            Header contentLengthHeader = response.getFirstHeader("Content-Length");
            if(contentLengthHeader != null) {
                String contentLengthString = contentLengthHeader.getValue();
                int contentLengthValue = Integer.valueOf(contentLengthString);
                if(contentLength == -1) {
                    LOG.info("GET " + url + " content length " + contentLengthValue);
                    setContentLength(contentLengthValue);
                } else {
                    if(contentLength != contentLengthValue) {
                        LOG.error("Content length of file changed");
                        markFailed(agent);
                        return false;
                    }
                }
            }
            // check we have a content length
            if(contentLength == -1) {
                LOG.error("GET " + url + " unknown content length");
                markFailed(agent);
                return false;
            }
            // parse content range from response
            ByteRange contentRange = null;
            Header contentRangeHeader = response.getFirstHeader("Content-Range");
            if(contentRangeHeader != null) {
                String contentRangeString = contentRangeHeader.getValue();
                LOG.info("GET " + url + " returned range " + contentRangeString);
                contentRange = ByteRange.parseContentRange(contentRangeString);
            }
            // remember content type if we don't have one yet
            Header contentTypeHeader = response.getFirstHeader("Content-Type");
            if(contentTypeHeader != null) {
                String contentTypeValue = contentTypeHeader.getValue();
                if(contentType == null) {
                    LOG.info("GET " + url + " content type " + contentTypeValue);
                    setContentType(contentTypeValue);
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
            int bytesStart = progress;
            int bytesToGo = contentLength - progress;
            if(contentRange != null) {
                if(contentRange.getStart() != progress) {
                    LOG.error("GET " + url + " server returned wrong offset");
                    markFailed(agent);
                    return false;
                }
                if(contentRange.hasEnd()) {
                    bytesToGo = (int)(contentRange.getEnd() - contentRange.getStart() - 1);
                }
            }
            // seek to start of region
            raf.seek(bytesStart);
            // copy data
            while(bytesToGo > 0) {
                // determine how much to copy
                int bytesToRead = Math.min(buffer.length, bytesToGo);
                // perform the copy
                int bytesRead = is.read(buffer, 0, bytesToRead);
                raf.write(buffer, 0, bytesRead);
                // sync the file
                fd.sync();
                // update state
                progress += bytesRead;
                bytesToGo -= bytesRead;
                // update db
                database.saveClientDownload(this);
                // call listeners
                agent.onDownloadProgress(this);
            }
            // update state
            if(progress == contentLength && state != State.COMPLETE) {
                switchState(agent, State.COMPLETE);
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

        switchState(agent, State.COMPLETE);

        return true;
    }

    private void markFailed(TalkTransferAgent agent) {
        switchState(agent, State.FAILED);
    }

    private void switchState(TalkTransferAgent agent, State newState) {
        LOG.info("[" + clientDownloadId + "] switching to state " + newState);
        state = newState;
        agent.onDownloadStateChanged(this);
    }

}
