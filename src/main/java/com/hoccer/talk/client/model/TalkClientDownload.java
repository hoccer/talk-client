package com.hoccer.talk.client.model;

import com.google.appengine.api.blobstore.ByteRange;

import com.hoccer.talk.client.XoClientDatabase;
import com.hoccer.talk.client.XoTransfer;
import com.hoccer.talk.client.XoTransferAgent;
import com.hoccer.talk.content.ContentDisposition;
import com.hoccer.talk.content.ContentState;
import com.hoccer.talk.content.IContentObject;
import com.hoccer.talk.crypto.AESCryptor;
import com.hoccer.talk.model.TalkAttachment;
import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.binary.Hex;
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

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.SyncFailedException;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

@DatabaseTable(tableName = "clientDownload")
public class TalkClientDownload extends XoTransfer implements IContentObject {

    /** Maximum amount of retry attempts when downloading an attachment */
    public static final int MAX_DOWNLOAD_RETRY = 16;

    /**
     * Minimum amount of progress to justify a db update
     *
     * This amount of data can be lost in case of crashes.
     * In all other cases progress will be saved.
     */
    private final static int PROGRESS_SAVE_MINIMUM = 1 << 16;

    private final static Logger LOG = Logger.getLogger(TalkClientDownload.class);

    private static final Detector MIME_DETECTOR = new DefaultDetector(
            MimeTypes.getDefaultMimeTypes());

    @DatabaseField(generatedId = true)
    private int clientDownloadId;

    @DatabaseField
    private Type type;

    @DatabaseField
    private State state;

    @DatabaseField
    private String fileName;

    @DatabaseField
    private String contentUrl;

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

    @DatabaseField
    private String fileId;

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

    @DatabaseField
    private int transferFailures;

    @DatabaseField(width = 128)
    private String contentHmac;

    private transient long progressRateLimit;

    private Timer mTimer;

    public TalkClientDownload() {
        super(Direction.DOWNLOAD);
        this.state = State.INITIALIZING;
        this.aspectRatio = 1.0;
        this.downloadProgress = 0;
        this.contentLength = -1;
    }

    /* XoTransfer implementation */
    @Override
    public Type getTransferType() {
        return type;
    }

    /* IContentObject implementation */
    @Override
    public boolean isContentAvailable() {
        return state == State.COMPLETE;
    }

    @Override
    public ContentState getContentState() {
        switch (state) {
            case INITIALIZING:
            case NEW:
                return ContentState.DOWNLOAD_NEW;
            case COMPLETE:
                return ContentState.DOWNLOAD_COMPLETE;
            case FAILED:
                return ContentState.DOWNLOAD_FAILED;
            case DOWNLOADING:
                return ContentState.DOWNLOAD_DOWNLOADING;
            case DECRYPTING:
                return ContentState.DOWNLOAD_DECRYPTING;
            case DETECTING:
                return ContentState.DOWNLOAD_DETECTING;
            /* old states */
            case REQUESTED:
            case STARTED:
                return ContentState.DOWNLOAD_DOWNLOADING;
            default:
                throw new RuntimeException("Unknown download state '" + state + "'");
        }
    }

    @Override
    public ContentDisposition getContentDisposition() {
        return ContentDisposition.DOWNLOAD;
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
        return contentUrl;
    }

    @Override
    public String getFileName() {
        return fileName;
    }

    @Override
    public String getContentDataUrl() {
        // TODO fix up this field on db upgrade
        if (dataFile != null) {
            if (dataFile.startsWith("file://")) {
                return dataFile;
            } else {
                return "file://" + dataFile;
            }
        }
        return null;
    }

    /**
     * Initialize this download as an avatar download
     *
     * @param url       to download
     * @param id        for avatar, identifying what the avatar belongs to
     * @param timestamp for avatar, takes care of collisions over id
     */
    public void initializeAsAvatar(String url, String id, Date timestamp) {
        LOG.info("[new] initializeAsAvatar(url: '" + url + "')");
        this.type = Type.AVATAR;

        url = checkFilecacheUrl(url); // TODO: ToBeDeleted

        this.downloadUrl = url;
        this.downloadFile = id + "-" + timestamp.getTime();
    }

    public void initializeAsAttachment(TalkAttachment attachment, String id, byte[] key) {
        LOG.info("[new] initializeAsAttachment(url: '" + attachment.getUrl() + "')");

        this.type = Type.ATTACHMENT;

        this.contentType = attachment.getMimeType();
        this.mediaType = attachment.getMediaType();

        this.aspectRatio = attachment.getAspectRatio();

        String filecacheUrl = checkFilecacheUrl(attachment.getUrl()); // TODO: ToBeDeleted
        attachment.setUrl(filecacheUrl);

        this.downloadUrl = attachment.getUrl();
        this.downloadFile = id;
        this.decryptedFile = UUID.randomUUID().toString();

        String fileName = attachment.getFileName();
        if (fileName != null) {
            this.fileName = fileName;
        }

        this.decryptionKey = new String(Hex.encodeHex(key));
        this.contentHmac = attachment.getHmac();
    }
    // TODO: DELETE THIS PIECE OF ****
    private String checkFilecacheUrl(String url) {
        String migratedUrl = url.substring(url.indexOf("/", 8));
        migratedUrl = "https://filecache.talk.hoccer.de:8444" + migratedUrl;
        return migratedUrl;
    }

    public void provideContentUrl(XoTransferAgent agent, String url) {
        if (url.startsWith("file://")) {
            return;
        }
        if (url.startsWith("content://media/external/file")) {
            return;
        }
        this.contentUrl = url;
        saveProgress(agent);
        agent.onDownloadStateChanged(this);
    }

    public void fixupVersion7(XoTransferAgent agent) {
        LOG.debug("fixup download with id '" + clientDownloadId + "' in state '" + state + "'");
        boolean changed = false;
        if (state == State.REQUESTED) {
            LOG.debug("state fixed to '" + State.DOWNLOADING + "'");
            changed = true;
            state = State.DOWNLOADING;
        } else if (state == State.STARTED) {
            LOG.debug("state fixed to '" + State.DOWNLOADING + "'");
            changed = true;
            state = State.DOWNLOADING;
        }
        if (state == State.DETECTING || state == State.COMPLETE) {
            if (dataFile == null) {
                LOG.debug("attempting to determine dataFile");
                if (decryptedFile != null) {
                    LOG.debug("using decrypted file");
                    changed = true;
                    dataFile = computeDecryptionFile(agent);
                } else if (downloadFile != null) {
                    LOG.debug("using download file");
                    changed = true;
                    dataFile = computeDownloadFile(agent);
                }
            }
        }
        if (dataFile != null && dataFile.startsWith("file://")) {
            LOG.debug("fixing data file");
            changed = true;
            dataFile = dataFile.substring(7);
        }
        if (type == Type.AVATAR && mediaType == null) {
            LOG.debug("fixing avatar media type");
            changed = true;
            mediaType = "image";
        }
        if (changed) {
            LOG.debug("download with id '" + clientDownloadId + "' fixed");
            saveProgress(agent);
            agent.onDownloadStateChanged(this);
        }
        if (state == State.COMPLETE) {
            // retrigger android media scanner
            if (type == Type.ATTACHMENT && contentUrl == null) {
                LOG.debug("triggering media scanner");
                agent.onDownloadFinished(this);
            }
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

    public String getFileId() {
        return fileId;
    }

    /**
     * Only used for migrating existing filecache Uris to new host. Delete this Method once
     * the migration is done!
     *
     * @param url
     */
    @Deprecated
    public void setDownloadUrl(String downloadUrl) {
        this.downloadUrl = downloadUrl;
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

    public String getContentHmac() {
        return contentHmac;
    }

    public void setContentHmac(String hmac) {
        this.contentHmac = hmac;
    }

    public String getDataFile() {
        // TODO fix up this field on db upgrade
        if (dataFile != null) {
            if (dataFile.startsWith("file://")) {
                return dataFile.substring(7);
            } else {
                return dataFile;
            }
        }
        return null;
    }

    public int getTransferFailures() {
        return transferFailures;
    }

    public void setTransferFailures(int transferFailures) {
        this.transferFailures = transferFailures;
        if(transferFailures > 16) {
            // max retries reached. stop download and reset retries
            LOG.debug("cancel Downloads. No more retries.");
            mTimer.cancel();
            this.transferFailures = 0;
        }
    }

    public boolean isAvatar() {
        return type == Type.AVATAR;
    }

    public boolean isAttachment() {
        return type == Type.ATTACHMENT;
    }

    private String computeDecryptionDirectory(XoTransferAgent agent) {
        String directory = null;
        switch (this.type) {
            case ATTACHMENT:
                directory = agent.getClient().getAttachmentDirectory();
                break;
        }
        return directory;
    }

    private String computeDownloadDirectory(XoTransferAgent agent) {
        String directory = null;
        switch (this.type) {
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
        if (directory != null) {
            file = directory + File.separator + this.decryptedFile;
        }
        return file;
    }

    private String computeDownloadFile(XoTransferAgent agent) {
        String file = null;
        String directory = computeDownloadDirectory(agent);
        if (directory != null) {
            file = directory + File.separator + this.downloadFile;
        }
        return file;
    }

    public void performDownloadAttempt(XoTransferAgent agent) {
        XoClientDatabase database = agent.getDatabase();
        String downloadFilename = computeDownloadFile(agent);
        if (downloadFilename == null) {
            LOG.error("[downloadId: '" + clientDownloadId + "'] could not determine download filename");
            return;
        }

        fixupVersion7(agent);

        LOG.info("[downloadId: '" + clientDownloadId + "'] download attempt starts in state '" + state + "'");

        boolean changed = false;
        if (state == State.COMPLETE) {
            LOG.warn("tried to perform completed download");
            return;
        }

        if (state == State.NEW) {
            switchState(agent, State.DOWNLOADING);
            changed = true;
        }

        if (changed) {
            try {
                database.saveClientDownload(this);
            } catch (SQLException e) {
                LOG.error("SQL error", e);
            }
        }

        if (state == State.DOWNLOADING) {
            mTimer = new Timer();
            DownloadTask downloadTask = new DownloadTask(this, agent, downloadFilename);
            mTimer.scheduleAtFixedRate(downloadTask, 0, 5 * 1000);
            try {
                synchronized (this) {
                    this.wait();
                }
            } catch (InterruptedException e) {
                LOG.error("Error while performing download attempt: ", e);
            }

        }
        if (state == State.DECRYPTING) {
            String decryptedFilename = computeDecryptionFile(agent);
            if (decryptedFilename == null) {
                LOG.warn("could not determine decrypted filename for downloadId '" + clientDownloadId + "'");
                markFailed(agent);
                return;
            }
            LOG.info("[downloadId: '" + clientDownloadId + "'] decrypting to '" + decryptedFilename + "'");
            if (!performDecryption(agent, downloadFilename, decryptedFilename)) {
                LOG.error("decryption failed");
                markFailed(agent);
                return;
            }
        }
        if (state == State.DETECTING) {
            LOG.info("[downloadId: '" + clientDownloadId + "'] detecting media type");
            String detectionFile = null;
            if (this.decryptedFile != null) {
                detectionFile = computeDecryptionFile(agent);
            } else {
                detectionFile = computeDownloadFile(agent);
            }
            if (detectionFile != null) {
                performDetection(agent, detectionFile);
            }
        }

        LOG.info("[downloadId: '" + clientDownloadId + "'] download attempt finished in state '" + state + "'");

        try {
            database.saveClientDownload(this);
        } catch (SQLException e) {
            LOG.error("SQL error", e);
        }
    }

    private void logGetDebug(String message) {
        LOG.debug("[downloadId: '" + clientDownloadId + "'] GET " + message);
    }

    private void logGetTrace(String message) {
        LOG.trace("[downloadId: '" + clientDownloadId + "'] GET " + message);
    }

    private void logGetWarning(String message) {
        LOG.warn("[downloadId: '" + clientDownloadId + "'] GET " + message);
    }

    private void logGetError(String message) {
        LOG.error("[downloadId: '" + clientDownloadId + "'] GET " + message);
    }

    private boolean performOneRequest(XoTransferAgent agent, String filename) {
        LOG.debug("performOneRequest(downloadId: '" + clientDownloadId + "', filename: '" + filename + "')");
        HttpClient client = agent.getHttpClient();
        XoClientDatabase database = agent.getDatabase();
        RandomAccessFile raf = null;
        FileDescriptor fd = null;
        try {
            logGetDebug("downloading '" + downloadUrl + "'");
            // create the GET request
            HttpGet request = new HttpGet(downloadUrl);
            // determine the requested range
            String range = null;
            if (contentLength != -1) {
                long last = contentLength - 1;
                range = "bytes=" + downloadProgress + "-" + last;
                logGetDebug("requesting range '" + range + "'");
                request.addHeader("Range", range);
            }
            // start performing the request
            HttpResponse response = client.execute(request);
            // process status line
            StatusLine status = response.getStatusLine();
            int sc = status.getStatusCode();
            logGetDebug("got status '" + sc + "': " + status.getReasonPhrase());
            if (sc != HttpStatus.SC_OK && sc != HttpStatus.SC_PARTIAL_CONTENT) {
                // client error - mark as failed
                if (sc >= 400 && sc <= 499) {
                    markFailed(agent);
                }
                return false;
            }
            // parse content length from response
            Header contentLengthHeader = response.getFirstHeader("Content-Length");
            int contentLengthValue = this.contentLength;
            if (contentLengthHeader != null) {
                String contentLengthString = contentLengthHeader.getValue();
                contentLengthValue = Integer.valueOf(contentLengthString);
                logGetDebug("got content length '" + contentLengthValue + "'");
            }
            // parse content range from response
            ByteRange contentRange = null;
            Header contentRangeHeader = response.getFirstHeader("Content-Range");
            if (contentRangeHeader != null) {
                String contentRangeString = contentRangeHeader.getValue();
                logGetDebug("got range '" + contentRangeString + "'");
                contentRange = ByteRange.parseContentRange(contentRangeString);
            }
            // remember content type if we don't have one yet
            Header contentTypeHeader = response.getFirstHeader("Content-Type");
            if (contentTypeHeader != null) {
                String contentTypeValue = contentTypeHeader.getValue();
                if (contentType == null) {
                    logGetDebug("got content type '" + contentTypeValue + "'");
                    contentType = contentTypeValue;
                }
            }
            // get ourselves a buffer
            byte[] buffer = new byte[1 << 12];
            // determine what to copy
            int bytesStart = downloadProgress;
            int bytesToGo = contentLengthValue;
            if (contentRange != null) {
                if (contentRange.getStart() != downloadProgress) {
                    logGetError("server returned wrong offset");
                    markFailed(agent);
                    return false;
                }
                if (contentRange.hasEnd()) {
                    int rangeSize = (int) (contentRange.getEnd() - contentRange.getStart() + 1);
                    if (rangeSize != bytesToGo) {
                        logGetError("server returned range not corresponding to content length");
                        markFailed(agent);
                        return false;
                    }
                }
                if (contentRange.hasTotal()) {
                    if (contentLength == -1) {
                        long total = contentRange.getTotal();
                        logGetDebug("inferred content length '" + total + "' from range");
                        contentLength = (int) total;
                    }
                }
            }
            if (contentLength == -1) {
                logGetError("could not determine content length");
                markFailed(agent);
                return false;
            }
            // handle content
            HttpEntity entity = response.getEntity();
            InputStream is = entity.getContent();
            // create and open destination file
            File f = new File(filename);
            logGetDebug("destination: '" + f.toString() + "'");
            f.createNewFile();
            raf = new RandomAccessFile(f, "rw");
            fd = raf.getFD();
            // resize the file
            raf.setLength(contentLength);
            // log about what we are to do
            logGetDebug("will retrieve '" + bytesToGo + "' bytes");
            // seek to start of region
            raf.seek(bytesStart);
            // copy data
            int savedProgress = downloadProgress;
            while (bytesToGo > 0) {
                logGetTrace("bytesToGo: '" + bytesToGo + "'");
                logGetTrace("downloadProgress: '" + downloadProgress + "'");
                // determine how much to copy
                int bytesToRead = Math.min(buffer.length, bytesToGo);
                // perform the copy
                int bytesRead = is.read(buffer, 0, bytesToRead);
                logGetTrace("reading: '" + bytesToRead + "' bytes, returned: '" + bytesRead + "' bytes");
                if (bytesRead == -1) {
                    logGetWarning("eof with '" + bytesToGo + "' bytes to go");
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
                savedProgress = maybeSaveProgress(agent, savedProgress);
                // call listeners
                notifyProgress(agent);
                if (!agent.isDownloadActive(this)) {
                    return true;
                }
            }
            // update db
            saveProgress(agent);
            // update state
            if (downloadProgress == contentLength) {
                if (decryptionKey != null) {
                    switchState(agent, State.DECRYPTING);
                } else {
                    dataFile = filename;
                    switchState(agent, State.DETECTING);
                }
            }
            // close streams
            fd = null;
            raf.close();
            is.close();
        } catch (Exception e) {
            LOG.error("download exception", e);
            return false;
        } finally {
            if (fd != null) {
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
        LOG.debug("performDecryption(downloadId: '" + clientDownloadId + "', sourceFile: '" + sourceFile + "', " +
                  "destinationFile: '" + destinationFile + "')");

        File source = new File(sourceFile);

        File destination = new File(destinationFile);
        if (destination.exists()) {
            destination.delete();
        }

        try {
            byte[] key = Hex.decodeHex(decryptionKey.toCharArray());
            int bytesToDecrypt = (int) source.length();
            byte[] buffer = new byte[1 << 16];
            InputStream is = new FileInputStream(source);
            OutputStream ofs = new FileOutputStream(destination);
            MessageDigest digest = MessageDigest.getInstance("SHA256");
            OutputStream os = new DigestOutputStream(ofs, digest);
            OutputStream dos = AESCryptor.decryptingOutputStream(os, key, AESCryptor.NULL_SALT);

            int bytesToGo = bytesToDecrypt;
            while (bytesToGo > 0) {
                int bytesToCopy = Math.min(buffer.length, bytesToGo);
                int bytesRead = is.read(buffer, 0, bytesToCopy);
                dos.write(buffer, 0, bytesRead);
                bytesToGo -= bytesRead;
                if (!agent.isDownloadActive(this)) {
                    return true;
                }
            }

            dos.flush();
            dos.close();
            os.flush();
            os.close();
            is.close();

            String computedHMac = new String(Base64.encodeBase64(digest.digest()));
            if (this.contentHmac != null) {
                if (this.contentHmac.equals(computedHMac)) {
                    LOG.info("download hmac ok");
                } else {
                    LOG.error("download hmac mismatch, computed hmac: '" + computedHMac + "', should be: '" + this.contentHmac + "'");
                }
            }

            dataFile = destinationFile;
            switchState(agent, State.DETECTING);
        } catch (Exception e) {
            LOG.error("decryption error", e);
            markFailed(agent);
            return false;
        }

        return true;
    }

    private boolean performDetection(XoTransferAgent agent, String destinationFilePath) {
        LOG.debug("performDetection(downloadId: '" + clientDownloadId + "', destinationFile: '" + destinationFilePath + "')");
        File destination = new File(destinationFilePath);

        try {
            InputStream tis = new FileInputStream(destination);
            BufferedInputStream btis = new BufferedInputStream(tis);

            Metadata metadata = new Metadata();
            if (contentType != null && !contentType.equals("application/octet-stream")) {
                metadata.add(Metadata.CONTENT_TYPE, contentType);
            }
            if (decryptedFile != null) {
                metadata.add(Metadata.RESOURCE_NAME_KEY, decryptedFile);
            }

            MediaType mt = MIME_DETECTOR.detect(btis, metadata);

            tis.close();

            if (mt != null) {
                String mimeType = mt.toString();
                LOG.info("[downloadId: '" + clientDownloadId + "'] detected mime-type '" + mimeType + "'");
                this.contentType = mimeType;
                MimeType mimet = MimeTypes.getDefaultMimeTypes().getRegisteredMimeType(mimeType);
                if (mimet != null) {
                    String extension = mimet.getExtension();
                    if (extension != null) {
                        LOG.info("[downloadId: '" + clientDownloadId + "'] renaming to extension '" + mimet.getExtension() + "'");

                        String destinationDirectory = computeDecryptionDirectory(agent);
                        String destinationFileName = createUniqueFileNameInDirectory(this.fileName, extension, destinationDirectory);
                        String destinationPath = destinationDirectory + File.separator + destinationFileName;

                        File newName = new File(destinationPath);
                        if (destination.renameTo(newName)) {
                            if (decryptedFile != null) {
                                this.decryptedFile = destinationFileName;
                                this.dataFile = destinationPath;
                            } else {
                                this.downloadFile = destinationFileName;
                                this.dataFile = destinationPath;
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
            markFailed(agent);
            return false;
        }
        return true;
    }

    /**
     * Creates a unique file name by checking whether a file already exists in a given directory.
     * In case a file with the same name already exists the given file name will be expanded by an underscore and
     * a running number (foo_1.bar) to prevent the existing file from being overwritten.
     *
     * @param file The given file name
     * @param extension The given file extension
     * @param directory The directory to check
     * @return The file name including running number and extension (foo_1.bar)
     */
    private String createUniqueFileNameInDirectory(String file, String extension, String directory) {
        String newFileName = file;
        String path;
        File f;
        int i = 0;
        while (true) {
            path = directory + File.separator + newFileName + extension;
            f = new File(path);
            if (f.exists()) {
                i++;
                newFileName = file + "_" + i;
            } else {
                break;
            }
        }
        return newFileName + extension;
    }

    private void markFailed(XoTransferAgent agent) {
        switchState(agent, State.FAILED);
        agent.onDownloadFailed(this);
    }

    public void switchState(XoTransferAgent agent, State newState) {
        LOG.debug("[downloadId: '" + clientDownloadId + "'] switching to state '" + newState + "'");
        state = newState;
        saveProgress(agent);
        agent.onDownloadStateChanged(this);
    }

    private void notifyProgress(XoTransferAgent agent) {
        agent.onDownloadProgress(this);
    }

    private void saveProgress(XoTransferAgent agent) {
        try {
            agent.getDatabase().saveClientDownload(this);
        } catch (SQLException e) {
            LOG.error("sql error", e);
        }
    }

    private int maybeSaveProgress(XoTransferAgent agent, int previousProgress) {
        int delta = downloadProgress - previousProgress;
        if (delta > PROGRESS_SAVE_MINIMUM) {
            saveProgress(agent);
            return downloadProgress;
        }
        return previousProgress;
    }

    public enum State {
        INITIALIZING, NEW, DOWNLOADING, PAUSED, DECRYPTING, DETECTING, COMPLETE, FAILED,
        /* old states from before db version 7 */
        REQUESTED, STARTED
    }

    private class DownloadTask extends TimerTask {

        private final XoTransferAgent mAgent;

        private final String mFilename;

        private final TalkClientDownload mDownload;

        public DownloadTask(TalkClientDownload download, XoTransferAgent agent, String filename) {
            mAgent = agent;
            mFilename = filename;
            mDownload = download;
        }

        @Override
        public void run() {
            LOG.info("[downloadId: '" + clientDownloadId + "'] download attempt " + transferFailures + "/"
                    + MAX_DOWNLOAD_RETRY);
            boolean success = performOneRequest(mAgent, mFilename);
            if (success) {
                LOG.info("[downloadId: '" + clientDownloadId + "'] download succeeded");
                synchronized (mDownload) {
                    mDownload.notify();
                }
                this.cancel();
            }
            LOG.info("[downloadId: '" + clientDownloadId + "'] download failed");
            setTransferFailures(transferFailures + 1);
        }
    }

}
