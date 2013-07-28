package com.hoccer.talk.client.model;

import com.google.appengine.api.blobstore.ByteRange;
import com.hoccer.talk.client.HoccerTalkClient;
import com.hoccer.talk.client.TalkClientDatabase;
import com.hoccer.talk.client.TalkTransfer;
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

    public void initializeAsAvatar(String url, String id, Date timestamp) throws MalformedURLException {
        this.type = Type.AVATAR;
        this.url = url;
        this.file = id + "-" + timestamp.getTime();
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

    private String computeFileName(HoccerTalkClient client) {
        if(this.file == null) {
            return null;
        }
        if(type == Type.AVATAR) {
            return client.getAvatarDirectory() + File.separator + this.file;
        }
        return null;
    }

    public void performDownloadAttempt(HttpClient client, TalkClientDatabase database, HoccerTalkClient talkClient) {
        String filename = computeFileName(talkClient);
        if(filename == null) {
            LOG.error("Could not determine filename for download " + clientDownloadId);
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
            switchState(State.STARTED);
            changed = true;
        }

        if(changed) {
            try {
                database.saveClientDownload(this);
            } catch (SQLException e) {
                LOG.error("SQL error", e);
            }
        }

        boolean success = performOneRequest(client, filename);
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

    private boolean performOneRequest(HttpClient client, String filename) {
        try {
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
                    markFailed();
                }
                return false;
            }
            // parse content length from response
            Header contentLengthHeader = response.getFirstHeader("Content-Length");
            if(contentLengthHeader != null) {
                String contentLengthString = contentLengthHeader.getValue();
                int contentLengthValue = Integer.valueOf(contentLengthString);
                if(contentLength == -1) {
                    LOG.info("GET " + url + " content-length " + contentLengthValue);
                    setContentLength(contentLengthValue);
                } else {
                    if(contentLength != contentLengthValue) {
                        LOG.error("Content length of file changed");
                        markFailed();
                        return false;
                    }
                }
            }
            // check we have a content length
            if(contentLength == -1) {
                LOG.error("Unknown content length");
                markFailed();
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
            RandomAccessFile raf = new RandomAccessFile(f, "rw");
            raf.setLength(contentLength);
            // get ourselves a buffer
            byte[] buffer = new byte[1<<16];
            // determine what to copy
            int bytesStart = progress;
            int bytesToGo = contentLength - progress;
            if(contentRange != null) {
                if(contentRange.getStart() != progress) {
                    LOG.error("Server did not start where we told it to");
                    markFailed();
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
                int bytesToRead = Math.min(buffer.length, bytesToGo);
                int bytesRead = is.read(buffer, 0, bytesToRead);
                raf.write(buffer, 0, bytesRead);
                bytesToGo -= bytesRead;
            }
            // final sync
            raf.getFD().sync();
            // close streams
            raf.close();
            is.close();
            // update state
            progress = progress + bytesToGo;
            if(progress == contentLength && state != State.COMPLETE) {
                switchState(State.COMPLETE);
            }
        } catch (ClientProtocolException e) {
            LOG.error("Download exception", e);
            return false;
        } catch (IOException e) {
            LOG.error("Download exception", e);
            return false;
        } catch (Throwable t) {
            LOG.error("Download throwable", t);
            return false;
        }
        return true;
    }

    private void markFailed() {
        switchState(State.FAILED);
    }

    private void switchState(State newState) {
        LOG.info("[" + clientDownloadId + "] switching to state " + newState);
        state = newState;
    }

}
