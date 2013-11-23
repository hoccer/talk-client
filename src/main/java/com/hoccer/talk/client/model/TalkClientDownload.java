package com.hoccer.talk.client.model;

import com.google.appengine.api.blobstore.ByteRange;
import com.hoccer.talk.client.XoClientDatabase;
import com.hoccer.talk.client.XoTransfer;
import com.hoccer.talk.client.XoTransferAgent;
import com.hoccer.talk.content.ContentState;
import com.hoccer.talk.content.IContentObject;
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
public class TalkClientDownload extends XoTransfer implements IContentObject {

    private final static Logger LOG = Logger.getLogger(TalkClientDownload.class);

    private static final Detector MIME_DETECTOR = new DefaultDetector(
                            MimeTypes.getDefaultMimeTypes());

    public enum State {
        NEW, DOWNLOADING, PAUSED, DECRYPTING, DETECTING, COMPLETE, FAILED
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


    @DatabaseField
    private String dataFile;


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

    /* IContentObject implementation */
    @Override
    public boolean isContentAvailable() {
        return state == State.COMPLETE;
    }
    @Override
    public ContentState getContentState() {
        switch(state) {
            case NEW:
                return ContentState.DOWNLOAD_NEW;
            case PAUSED:
                return ContentState.DOWNLOAD_PAUSED;
            case DOWNLOADING:
            case DECRYPTING:
                return ContentState.DOWNLOAD_IN_PROGRESS;
            case COMPLETE:
                return ContentState.DOWNLOAD_COMPLETE;
            case FAILED:
                return ContentState.DOWNLOAD_FAILED;
            default:
                throw new RuntimeException("Unknown download state");
        }
    }
    @Override
    public int getTransferLength() {
        return contentLength;
    }
    @Override
    public int getTransferProgress() {
        return downloadProgress;
    }
    @Override
    public String getContentMediaType() {
        return mediaType;
    }
    @Override
    public double getContentAspectRatio() {
        return aspectRatio;
    }
    @Override
    public String getContentUrl() {
        return getDataFile();
    }

    /**
     * Initialize this download as an avatar download
     *
     * @param url to download
     * @param id for avatar, identifying what the avatar belongs to
     * @param timestamp for avatar, takes care of collisions over id
     */
    public void initializeAsAvatar(String url, String id, Date timestamp) {
        LOG.info("[new] initializeAsAvatar(" + url + ")");
        this.type = Type.AVATAR;
        this.downloadUrl = url;
        this.downloadFile = id + "-" + timestamp.getTime();
    }

    public void initializeAsAttachment(TalkAttachment attachment, String id, byte[] key) {
        LOG.info("[new] initializeAsAttachment(" + attachment.getUrl() + ")");

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
            // XXX should avoid collisions here
            this.decryptedFile = filename;
        }
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
        return dataFile;
    }

    public boolean isAvatar() {
        return type == Type.AVATAR;
    }

    public boolean isAttachment() {
        return type == Type.ATTACHMENT;
    }

    private String computeDecryptionDirectory(XoTransferAgent agent) {
        String directory = null;
        switch(this.type) {
            case ATTACHMENT:
                directory = agent.getClient().getAttachmentDirectory();
                break;
        }
        return directory;
    }

    private String computeDownloadDirectory(XoTransferAgent agent) {
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

    private String computeDecryptionFile(XoTransferAgent agent) {
        String file = null;
        String directory = computeDecryptionDirectory(agent);
        if(directory != null) {
            file = directory + File.separator + this.decryptedFile;
        }
        return file;
    }

    private String computeDownloadFile(XoTransferAgent agent) {
        String file = null;
        String directory = computeDownloadDirectory(agent);
        if(directory != null) {
            file = directory + File.separator + this.downloadFile;
        }
        return file;
    }

    public void performDownloadAttempt(XoTransferAgent agent) {
        XoClientDatabase database = agent.getDatabase();
        String downloadFilename = computeDownloadFile(agent);
        if(downloadFilename == null) {
            LOG.error("[" + clientDownloadId + "] could not determine download filename");
            return;
        }

        LOG.info("[" + clientDownloadId + "] download attempt starts in state " + state);

        boolean changed = false;
        if(state == State.COMPLETE) {
            LOG.warn("tried to perform completed download");
            return;
        }

        if(state == State.NEW) {
            switchState(agent, State.DOWNLOADING);
            changed = true;
        }

        if(changed) {
            try {
                database.saveClientDownload(this);
            } catch (SQLException e) {
                LOG.error("SQL error", e);
            }
        }

        if(state == State.DOWNLOADING) {
            int maxAttempts = 5;
            int attempt = 1;
            while(attempt <= maxAttempts) {
                LOG.info("[" + clientDownloadId + "] download attempt " + attempt + "/" + maxAttempts);
                boolean success = performOneRequest(agent, downloadFilename);
                if(success) {
                    LOG.info("[" + clientDownloadId + "] download succeeded");
                    break;
                }
                LOG.info("[" + clientDownloadId + "] download failed");
                attempt++;
            }
        }
        if(state == State.DECRYPTING) {
            String decryptedFilename = computeDecryptionFile(agent);
            if(decryptedFilename == null) {
                LOG.warn("could not determine decrypted filename for " + clientDownloadId);
                markFailed(agent);
                return;
            }
            LOG.info("[" + clientDownloadId + "] decrypting to " + decryptedFilename);
            if(!performDecryption(agent, downloadFilename, decryptedFilename)) {
                LOG.error("decryption failed");
                markFailed(agent);
                return;
            }
        }
        if(state == State.DETECTING) {
            LOG.info("[" + clientDownloadId + "] detecting media type");
            String detectionFile = null;
            if(this.decryptedFile != null) {
                detectionFile = computeDecryptionFile(agent);
            } else {
                detectionFile = computeDownloadFile(agent);
            }
            if(detectionFile != null) {
                performDetection(agent, detectionFile);
            }
        }

        LOG.info("[" + clientDownloadId + "] download attempt finished in state " + state);

        try {
            database.saveClientDownload(this);
        } catch (SQLException e) {
            LOG.error("SQL error", e);
        }
    }

    private void logGetDebug(String message) {
        LOG.debug("[" + clientDownloadId + "] GET " + message);
    }

    private void logGetTrace(String message) {
        LOG.trace("[" + clientDownloadId + "] GET " + message);
    }

    private void logGetWarning(String message) {
        LOG.warn("[" + clientDownloadId + "] GET " + message);
    }

    private void logGetError(String message) {
        LOG.error("[" + clientDownloadId + "] GET " + message);
    }

    private boolean performOneRequest(XoTransferAgent agent, String filename) {
        LOG.debug("performOneRequest(" + clientDownloadId + "," + filename + ")");
        HttpClient client = agent.getHttpClient();
        XoClientDatabase database = agent.getDatabase();
        RandomAccessFile raf = null;
        FileDescriptor fd = null;
        try {
            logGetDebug("downloading " + downloadUrl);
            // create the GET request
            HttpGet request = new HttpGet(downloadUrl);
            // determine the requested range
            String range = null;
            if(contentLength != -1) {
                long last = contentLength - 1;
                range = "bytes=" + downloadProgress + "-" + last;
                logGetDebug("requesting range " + range);
                request.addHeader("Range", range);
            }
            // start performing the request
            HttpResponse response = client.execute(request);
            // process status line
            StatusLine status = response.getStatusLine();
            int sc = status.getStatusCode();
            logGetDebug("got status " + sc + ": " + status.getReasonPhrase());
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
                logGetDebug("got content length " + contentLengthValue);
            }
            // parse content range from response
            ByteRange contentRange = null;
            Header contentRangeHeader = response.getFirstHeader("Content-Range");
            if(contentRangeHeader != null) {
                String contentRangeString = contentRangeHeader.getValue();
                logGetDebug("got range " + contentRangeString);
                contentRange = ByteRange.parseContentRange(contentRangeString);
            }
            // remember content type if we don't have one yet
            Header contentTypeHeader = response.getFirstHeader("Content-Type");
            if(contentTypeHeader != null) {
                String contentTypeValue = contentTypeHeader.getValue();
                if(contentType == null) {
                    logGetDebug("got content type " + contentTypeValue);
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
                    logGetError("server returned wrong offset");
                    markFailed(agent);
                    return false;
                }
                if(contentRange.hasEnd()) {
                    int rangeSize = (int)(contentRange.getEnd() - contentRange.getStart() + 1);
                    if(rangeSize != bytesToGo) {
                        logGetError("server returned range not corresponding to content length");
                        markFailed(agent);
                        return false;
                    }
                }
                if(contentRange.hasTotal()) {
                    if(contentLength == -1) {
                        long total = contentRange.getTotal();
                        logGetDebug("inferred content length " + total + " from range");
                        contentLength = (int)total;
                    }
                }
            }
            if(contentLength == -1) {
                logGetError("could not determine content length");
                markFailed(agent);
                return false;
            }
            // handle content
            HttpEntity entity = response.getEntity();
            InputStream is = entity.getContent();
            // create and open destination file
            File f = new File(filename);
            logGetDebug("destination " + f.toString());
            f.createNewFile();
            raf = new RandomAccessFile(f, "rw");
            fd = raf.getFD();
            // resize the file
            raf.setLength(contentLength);
            // log about what we are to do
            logGetDebug("will retrieve " + bytesToGo + " bytes");
            // seek to start of region
            raf.seek(bytesStart);
            // copy data
            while(bytesToGo > 0) {
                logGetTrace("bytesToGo: " + bytesToGo);
                logGetTrace("downloadProgress: " + downloadProgress);
                // determine how much to copy
                int bytesToRead = Math.min(buffer.length, bytesToGo);
                // perform the copy
                int bytesRead = is.read(buffer, 0, bytesToRead);
                logGetTrace("reading " + bytesToRead + " returned " + bytesRead);
                if(bytesRead == -1) {
                    logGetWarning("eof with " + bytesToGo + " bytes to go");
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
            if(downloadProgress == contentLength) {
                if(decryptionKey != null) {
                    switchState(agent, State.DECRYPTING);
                } else {
                    dataFile = "file://" + filename;
                    switchState(agent, State.DETECTING);
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

    private boolean performDecryption(XoTransferAgent agent, String sourceFile, String destinationFile) {
        LOG.debug("performDecryption(" + clientDownloadId + "," + sourceFile + "," + destinationFile + ")");

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

            dataFile = "file://" + destinationFile;
            switchState(agent, State.DETECTING);
        } catch (Exception e) {
            LOG.error("decryption error", e);
            return false;
        }

        return true;
    }

    private boolean performDetection(XoTransferAgent agent, String destinationFile) {
        LOG.debug("performDetection(" + clientDownloadId + "," + destinationFile + ")");
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
                                this.dataFile = "file://" + computeDecryptionFile(agent);
                            } else {
                                this.downloadFile = this.downloadFile + extension;
                                this.dataFile = "file://" + computeDownloadFile(agent);
                            }
                        } else {
                            LOG.warn("could not rename file");
                        }
                    }
                }
            }
            switchState(agent, State.COMPLETE);
        } catch (Exception e) {
            LOG.error("detection error", e);
            return false;
        }
        return true;
    }


    private void markFailed(XoTransferAgent agent) {
        switchState(agent, State.FAILED);
    }

    private void switchState(XoTransferAgent agent, State newState) {
        LOG.debug("[" + clientDownloadId + "] switching to state " + newState);
        state = newState;
        try {
            agent.getDatabase().saveClientDownload(this);
        } catch (SQLException e) {
            LOG.error("SQL error", e);
        }
        agent.onDownloadStateChanged(this);
    }

    private void notifyProgress(XoTransferAgent agent) {
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
