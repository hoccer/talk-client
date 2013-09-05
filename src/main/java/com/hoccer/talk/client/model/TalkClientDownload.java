package com.hoccer.talk.client.model;

import com.google.appengine.api.blobstore.ByteRange;
import com.hoccer.talk.client.TalkClientDatabase;
import com.hoccer.talk.client.TalkTransfer;
import com.hoccer.talk.client.TalkTransferAgent;
import com.hoccer.talk.crypto.AESCryptor;
import com.hoccer.talk.model.TalkAttachment;
import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.StatusLine;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.log4j.Logger;
import org.apache.tika.detect.DefaultDetector;
import org.apache.tika.detect.Detector;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MediaType;
import org.apache.tika.mime.MimeType;
import org.apache.tika.mime.MimeTypes;
import org.bouncycastle.util.encoders.Hex;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.SyncFailedException;
import java.sql.SQLException;
import java.util.Date;
import java.util.UUID;

@DatabaseTable(tableName = "clientDownload")
public class TalkClientDownload extends TalkTransfer {

    private final static Logger LOG = Logger.getLogger(TalkClientDownload.class);

    private static final Detector MIME_DETECTOR = new DefaultDetector(
                            MimeTypes.getDefaultMimeTypes());

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

    /**
     * Name of the file the download itself will go to
     *
     * This is relative to the result of computeDownloadDirectory().
     */
    @DatabaseField(width = 2000)
    private String downloadFile;

    @DatabaseField
    private int downloadProgress;


    @DatabaseField(width = 128)
    private String decryptionKey;

    @DatabaseField(width = 2000)
    private String decryptedFile;

    @DatabaseField
    private double aspectRatio;

    private transient long progressRateLimit;


    public TalkClientDownload() {
        super(Direction.DOWNLOAD);
        this.state = State.NEW;
        this.aspectRatio = 1.0;
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
        this.downloadFile = id + "-" + timestamp.getTime();
    }

    public void initializeAsAttachment(TalkAttachment attachment, String id, byte[] key) {
        LOG.info("initializeAsAttachment(" + attachment.getUrl() + ")");

        this.type = Type.ATTACHMENT;

        this.contentType = attachment.getMimeType();
        this.mediaType = attachment.getMediaType();

        this.aspectRatio = attachment.getAspectRatio();

        this.downloadUrl = attachment.getUrl();
        this.downloadFile = id;

        this.decryptionKey = bytesToHex(key);
        this.decryptedFile = UUID.randomUUID().toString();
        String filename = attachment.getFilename();
        if(filename != null) {
            this.decryptedFile = filename;
        }

        LOG.info("attachment filename " + this.decryptedFile);
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

    public double getAspectRatio() {
        return aspectRatio;
    }

    public String getDataFile() {
        switch(this.type) {
            case AVATAR:
                return this.downloadFile;
            case ATTACHMENT:
                return this.decryptedFile;
        }
        return null;
    }

    private String computeDecryptionDirectory(TalkTransferAgent agent) {
        String directory = null;
        switch(this.type) {
            case ATTACHMENT:
                directory = agent.getClient().getAttachmentDirectory();
                break;
        }
        return directory;
    }

    private String computeDownloadDirectory(TalkTransferAgent agent) {
        String directory = null;
        switch(this.type) {
            case AVATAR:
                directory = agent.getClient().getAvatarDirectory();
                break;
            case ATTACHMENT:
                directory = agent.getClient().getEncryptedDownloadDirectory();
                break;
        }
        return directory;
    }

    private String computeDecryptionFile(TalkTransferAgent agent) {
        String file = null;
        String directory = computeDecryptionDirectory(agent);
        if(directory != null) {
            file = directory + File.separator + this.decryptedFile;
        }
        return file;
    }

    private String computeDownloadFile(TalkTransferAgent agent) {
        String file = null;
        String directory = computeDownloadDirectory(agent);
        if(directory != null) {
            file = directory + File.separator + this.downloadFile;
        }
        return file;
    }

    public void noteRequested() {
        if(state == State.NEW) {
            state = State.REQUESTED;
        }
    }

    public void performDownloadAttempt(TalkTransferAgent agent) {
        TalkClientDatabase database = agent.getDatabase();
        String downloadFilename = computeDownloadFile(agent);
        if(downloadFilename == null) {
            LOG.warn("could not determine download filename for " + clientDownloadId);
            return;
        }
        LOG.info("filename is " + downloadFilename);

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
            boolean success = performOneRequest(agent, downloadFilename);

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
                String decryptedFilename = computeDecryptionFile(agent);
                if(decryptedFilename == null) {
                    LOG.warn("could not determine decrypted filename for " + clientDownloadId);
                    markFailed(agent);
                    break;
                }
                LOG.info("decrypting to " + decryptedFilename);
                if(!performDecryption(agent, downloadFilename, decryptedFilename)) {
                    LOG.error("decryption failed");
                    markFailed(agent);
                    break;
                }
                break;
            }
            if(state == State.COMPLETE) {
                LOG.info("download is complete");
                String detectionFile = null;
                if(this.decryptedFile != null) {
                    detectionFile = computeDecryptionFile(agent);
                } else {
                    detectionFile = computeDownloadFile(agent);
                }
                if(detectionFile != null) {
                    performDetection(agent, detectionFile);
                }
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
            // determine the requested range
            String range = null;
            if(contentLength == -1) {
                range = "bytes=" + downloadProgress + "-";
            } else {
                long last = contentLength - 1;
                range = "bytes=" + downloadProgress + "-" + last;
            }
            LOG.info("GET " + downloadUrl + " requesting range " + range);
            request.addHeader("Range", range);
            // start performing the request
            HttpResponse response = client.execute(request);
            // process status line
            StatusLine status = response.getStatusLine();
            int sc = status.getStatusCode();
            LOG.info("GET " + downloadUrl + " status " + sc + ": " + status.getReasonPhrase());
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
            // get ourselves a buffer
            byte[] buffer = new byte[1<<16];
            // determine what to copy
            int bytesStart = downloadProgress;
            int bytesToGo = contentLengthValue;
            if(contentRange != null) {
                if(contentRange.getStart() != downloadProgress) {
                    LOG.error("GET " + downloadUrl + " server returned wrong offset");
                    markFailed(agent);
                    return false;
                }
                if(contentRange.hasEnd()) {
                    int rangeSize = (int)(contentRange.getEnd() - contentRange.getStart() + 1);
                    if(rangeSize != bytesToGo) {
                        LOG.error("GET " + downloadUrl + " server returned range not corresponding to content length");
                        markFailed(agent);
                        return false;
                    }
                }
                if(contentRange.hasTotal()) {
                    if(contentLength == -1) {
                        long total = contentRange.getTotal();
                        LOG.info("GET " + downloadUrl + " length determined to be " + total);
                        contentLength = (int)total;
                    }
                }
            }
            if(contentLength == -1) {
                LOG.error("GET " + downloadUrl + " has no content length");
                markFailed(agent);
                return false;
            }
            LOG.info("GET " + downloadUrl + " real file size " + contentLength);
            // handle content
            HttpEntity entity = response.getEntity();
            InputStream is = entity.getContent();
            // create and open destination file
            File f = new File(filename);
            LOG.info("GET " + downloadUrl + " destination " + f.toString());
            f.createNewFile();
            raf = new RandomAccessFile(f, "rw");
            fd = raf.getFD();
            // resize the file
            raf.setLength(contentLength);
            // log about what we are to do
            LOG.info("GET " + downloadUrl + " will retrieve " + bytesToGo + " bytes");
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
                //raf.getFD().sync();
                // sync the file
                fd.sync();
                // update state
                downloadProgress += bytesRead;
                bytesToGo -= bytesRead;
                // update db
                database.saveClientDownload(this);
                // call listeners
                notifyProgress(agent);
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

    private boolean performDecryption(TalkTransferAgent agent, String sourceFile, String destinationFile) {
        LOG.info("performing decryption of download " + clientDownloadId);

        File source = new File(sourceFile);

        File destination = new File(destinationFile);
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

            switchState(agent, State.COMPLETE);
        } catch (Exception e) {
            LOG.error("decryption error", e);
            return false;
        }

        return true;
    }

    private boolean performDetection(TalkTransferAgent agent, String destinationFile) {
        LOG.info("performDetection(" + destinationFile + ")");
        File destination = new File(destinationFile);

        try {
            InputStream tis = new FileInputStream(destination);
            BufferedInputStream btis = new BufferedInputStream(tis);

            Metadata metadata = new Metadata();
            if(contentType != null && !contentType.equals("application/octet-stream")) {
                metadata.add(Metadata.CONTENT_TYPE, contentType);
            }
            if(decryptedFile != null) {
                metadata.add(Metadata.RESOURCE_NAME_KEY, decryptedFile);
            }

            MediaType mt = MIME_DETECTOR.detect(btis, metadata);

            tis.close();

            if(mt != null) {
                String mimeType = mt.toString();
                LOG.info("detected type " + mimeType);
                this.contentType = mimeType;
                MimeType mimet = MimeTypes.getDefaultMimeTypes().getRegisteredMimeType(mimeType);
                if(mimet != null) {
                    String extension = mimet.getExtension();
                    if(extension != null) {
                        LOG.info("renaming to extension " + mimet.getExtension());
                        File newName = new File(destinationFile + extension);
                        if(destination.renameTo(newName)) {
                            if(decryptedFile != null) {
                                this.decryptedFile = this.decryptedFile + extension;
                            } else {
                                this.downloadFile = this.downloadFile + extension;
                            }
                        } else {
                            LOG.warn("could not rename file");
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOG.error("detection error", e);
            return false;
        }
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

    private void notifyProgress(TalkTransferAgent agent) {
        long now = System.currentTimeMillis();
        long delta = now - progressRateLimit;
        if(delta > 250) {
            agent.onDownloadProgress(this);
        }
        progressRateLimit = now;
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
