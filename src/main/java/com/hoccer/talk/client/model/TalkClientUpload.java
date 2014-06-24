package com.hoccer.talk.client.model;

import com.google.appengine.api.blobstore.ByteRange;
import com.hoccer.talk.client.XoClient;
import com.hoccer.talk.client.XoTransfer;
import com.hoccer.talk.client.XoTransferAgent;
import com.hoccer.talk.content.ContentDisposition;
import com.hoccer.talk.content.ContentState;
import com.hoccer.talk.content.IContentObject;
import com.hoccer.talk.crypto.AESCryptor;
import com.hoccer.talk.rpc.ITalkRpcServer;
import com.hoccer.talk.util.IProgressListener;
import com.hoccer.talk.util.ProgressOutputHttpEntity;
import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;
import org.apache.http.*;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPut;
import org.apache.log4j.Logger;
import org.bouncycastle.util.encoders.Hex;

import java.io.*;
import java.sql.SQLException;

@DatabaseTable(tableName = "clientUpload")
public class TalkClientUpload extends XoTransfer implements IContentObject {

    /**
     * Minimum amount of progress to justify a db update
     *
     * Can be large since this only concerns display.
     * For real uploads the check request will fix the progress.
     */
    private final static int PROGRESS_SAVE_MINIMUM = 1 << 16;

    private final static Logger LOG = Logger.getLogger(TalkClientUpload.class);

    private HttpPut mUploadRequest = null;

    public enum State {
        NEW, REGISTERING, UPLOADING, PAUSED, COMPLETE, FAILED,
        /* old states */
        REGISTERED, STARTED
    }

    @DatabaseField(generatedId = true)
    private int clientUploadId;

    @DatabaseField
    private Type type;

    @DatabaseField
    private State state;

    @DatabaseField
    private String contentUrl;

    @DatabaseField
    private String fileName;

    /** Plain data file */
    @DatabaseField(width = 2000)
    private String dataFile;
    /** Plain data size */
    @DatabaseField
    private int dataLength;

    @DatabaseField(width = 2000)
    private String encryptedFile;
    @DatabaseField
    private int encryptedLength = -1;
    @DatabaseField
    private String encryptionKey;

    /** Size of upload */
    @DatabaseField
    private int uploadLength;
    /** URL for upload */
    @DatabaseField(width = 2000)
    private String uploadUrl;
    /** URL for download */
    @DatabaseField(width = 2000)
    private String downloadUrl;
    /** Id for file transfer */
    @DatabaseField
    private String fileId;

    @DatabaseField(width = 128)
    private String contentType;

    @DatabaseField(width = 128)
    private String mediaType;

    @DatabaseField(width = 128)
    private String contentHmac;

    @DatabaseField
    private double aspectRatio;

    @DatabaseField
    private int progress;


    public TalkClientUpload() {
        super(Direction.UPLOAD);
        this.state = State.NEW;
        this.aspectRatio = 1.0;
        this.dataLength = -1;
        this.uploadLength = -1;
        this.encryptedLength = -1;
    }

    public void fixupVersion7(XoTransferAgent agent) {
        LOG.debug("fixup upload with id: '" + clientUploadId + "'");
        boolean changed = false;
        if(state == State.REGISTERED || state == State.STARTED) {
            LOG.debug("state '" + state + "' fixed");
            changed = true;
            state = State.UPLOADING;
        }
        if(type == Type.AVATAR && mediaType == null) {
            LOG.debug("fixing avatar media type");
            changed = true;
            mediaType = "image";
        }
        if(dataFile != null && dataFile.startsWith("/")) {
            LOG.debug("fixing data file");
            changed = true;
            dataFile = "file://" + dataFile;
        }
        if(changed) {
            LOG.debug("upload with id '" + clientUploadId + "' fixed");
            saveProgress(agent);
            agent.onUploadStateChanged(this);
        }
    }

    /* XoTransfer implementation */
    @Override
    public Type getTransferType() {
        return type;
    }

    /* IContentObject implementation */
    @Override
    public boolean isContentAvailable() {
        // uploaded content is always available
        return true;
    }
    @Override
    public ContentState getContentState() {
        switch(state) {
            case NEW:
                return ContentState.UPLOAD_NEW;
            case COMPLETE:
                return ContentState.UPLOAD_COMPLETE;
            case FAILED:
                return ContentState.UPLOAD_FAILED;
            case REGISTERING:
                return ContentState.UPLOAD_REGISTERING;
            case UPLOADING:
                return ContentState.UPLOAD_UPLOADING;
            case PAUSED:
                return ContentState.UPLOAD_PAUSED;
            /* old states */
            case REGISTERED:
            case STARTED:
                return ContentState.UPLOAD_UPLOADING;
            default:
                throw new RuntimeException("Unknown upload state '" + state + "'");
        }
    }
    @Override
    public ContentDisposition getContentDisposition() {
        return ContentDisposition.UPLOAD;
    }
    @Override
    public int getTransferLength() {
        return encryptedLength;
    }
    @Override
    public int getTransferProgress() {
        return progress;
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
    public String getContentDataUrl() {
        if(dataFile != null) {
            if(dataFile.startsWith("/")) {
                return "file://" + dataFile;
            } else {
                return dataFile;
            }
        }
        return null;
    }
    @Override
    public int getContentLength() {
        return dataLength;
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

    @Override
    public String getFileName() {
        return fileName;
    }

    public String getDataFile() {
        return dataFile;
    }

    public String getDownloadUrl() {
        return downloadUrl;
    }

    public String getFileId() {
        return fileId;
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    public String getMediaType() {
        return mediaType;
    }

    public int getEncryptedLength() {
        return encryptedLength;
    }

    public int getDataLength() {
        return dataLength;
    }

    public double getAspectRatio() {
        return aspectRatio;
    }

    public int getProgress() {
        return progress;
    }

    @Override
    public String getContentHmac() {
        return contentHmac;
    }

    public void setcontentHmac(String hmac) {
        this.contentHmac = hmac;
    }

    public boolean isAvatar() {
        return type == Type.AVATAR;
    }

    public boolean isAttachment() {
        return type == Type.ATTACHMENT;
    }

    /**
     * Only used for migrating existing filecache Uris to new host. Delete this Method once
     * the migration is done!
     *
     * @param url
     */
    @Deprecated
    public void setUploadUrl(String url) {
        this.uploadUrl = url;
    }

    public String getUploadUrl() {
        return this.uploadUrl;
    }

    public void provideEncryptionKey(String key) {
        this.encryptionKey = key;
    }

    public void initializeAsAvatar(String contentUrl, String url, String contentType, int contentLength) {
        LOG.info("[new] initializing as avatar: " + url + " length " + contentLength);

        this.type = Type.AVATAR;

        this.contentUrl = contentUrl;

        this.dataFile = url;
        this.dataLength = contentLength;

        this.contentType = contentType;
        this.mediaType = "image";
    }

    public void initializeAsAttachment(String fileName, String contentUrl, String url, String contentType, String mediaType, double aspectRatio, int contentLength, String hmac) {
        LOG.info("[new] initializing as attachment: " + url + " length " + contentLength);

        this.type = Type.ATTACHMENT;

        this.contentUrl = contentUrl;
        this.fileName = fileName;

        this.dataFile = url;
        this.dataLength = contentLength;
        this.contentHmac = hmac;

        this.contentType = contentType;
        this.mediaType = mediaType;
        this.aspectRatio = aspectRatio;

        //this.encryptedFile = UUID.randomUUID().toString();
    }

    public void performUploadAttempt(XoTransferAgent agent) {

        fixupVersion7(agent);

        LOG.info("performing upload attempt in state '" + this.state + "'");

        String uploadFile = this.dataFile;
        if(uploadFile == null) {
            LOG.error("could not compute upload location for clientUploadId '" + clientUploadId + "'");
            return;
        }

        LOG.trace("upload from file '" + uploadFile + "'");

        if(state == State.COMPLETE) {
            LOG.warn("tried to perform completed upload");
            return;
        }

        if(state == State.NEW) {
            LOG.warn("tried to perform new upload, need to register first");
            return;
        }

        if(state == State.REGISTERING) {
            LOG.warn("tried to perform registering upload, need to register first");
            return;
        }

        if (state == State.PAUSED) {
            LOG.info("upload currently paused.");
            mUploadRequest = null;
            return;
        }

        if(!agent.isUploadActive(this)) {
            return;
        }

        if(state == State.UPLOADING) {
            LOG.info("upload is uploading");
            try {
                if(performCheckRequest(agent)) {
                    if(!agent.isUploadActive(this)) {
                        return;
                    }
                    performUploadRequest(agent, uploadFile);
                }
            } catch (IOException e) {
                LOG.error("problem during upload", e);
            }
        }

        if(!agent.isUploadActive(this)) {
            return;
        }

        saveProgress(agent);

        LOG.info("upload attempt finished in state '" + this.state + "'");
    }

    public boolean performRegistration(XoTransferAgent agent, boolean needEncryption) {
        LOG.info("performRegistration(), state: ‘" + state + "'");
        XoClient talkClient = agent.getClient();
        if(this.state == State.NEW || state == State.REGISTERING) {
            LOG.info("[uploadId: '" + clientUploadId + "'] performing registration");
            try {
                ITalkRpcServer.FileHandles handles;
                if(!needEncryption) {
                    this.uploadLength = dataLength;
                    handles = talkClient.getServerRpc().createFileForStorage(this.uploadLength);
                } else {
                    this.encryptedLength = AESCryptor.calcEncryptedSize(getContentLength(),AESCryptor.NULL_SALT,AESCryptor.NULL_SALT);
                    this.uploadLength = encryptedLength;
                    handles = talkClient.getServerRpc().createFileForTransfer(this.encryptedLength);
                }
                fileId = handles.fileId;
                uploadUrl = handles.uploadUrl;
                downloadUrl = handles.downloadUrl;
                LOG.info("[uploadId: '" + clientUploadId + "'] registered as fileId: '" + handles.fileId + "'");

                switchState(agent, State.UPLOADING);
            } catch (Exception e) {
                LOG.error("error registering", e);
                return false;
            }
        }
        return true;
    }

    private void logRequestHeaders(HttpMessage theMessage, String logPrefix) {
        Header[] hdrs = theMessage.getAllHeaders();
        for(int i = 0; i < hdrs.length; i++) {
            Header h = hdrs[i];
            LOG.trace(logPrefix + h.getName() + ": " + h.getValue());
        }
    }
    private boolean performCheckRequest(XoTransferAgent agent) throws IOException {
        HttpClient client = agent.getHttpClient();

        LOG.info("[uploadId: '" + clientUploadId + "'] performing check request");

        int last = uploadLength - 1;
        //int confirmedProgress = 0;
        // perform a check request to ensure correct progress
        HttpPut checkRequest = new HttpPut(uploadUrl);
        String contentRangeValue = "bytes */" + uploadLength;
        LOG.trace("PUT-check range '" + contentRangeValue + "'");
        //checkRequest.addHeader("Content-Range", contentRangeValue);
        //checkRequest.setHeader("Content-Length","0");
        LOG.trace("PUT-check '" + uploadUrl + "' commencing");
        logRequestHeaders(checkRequest,"PUT-check request header ");

        HttpResponse checkResponse = client.execute(checkRequest);
        StatusLine checkStatus = checkResponse.getStatusLine();
        int checkSc = checkStatus.getStatusCode();
        LOG.trace("PUT-check '" + uploadUrl + "' with status '" + checkSc + "': " + checkStatus.getReasonPhrase());
        if(checkSc != HttpStatus.SC_OK && checkSc != 308 /* resume incomplete */) {
            // client error - mark as failed
            if(checkSc >= 400 && checkSc <= 499) {
                markFailed(agent);
            }
            checkResponse.getEntity().consumeContent();
            return false;
        }
        logRequestHeaders(checkResponse,"PUT-check response header ");

        // process range header from check request
        Header checkRangeHeader = checkResponse.getFirstHeader("Range");
        if(checkRangeHeader != null) {
            checkCompletion(agent, checkRangeHeader);
        } else {
            LOG.warn("[uploadId: '" + clientUploadId + "'] no range header in check response");
            this.progress = 0;
        }
        checkResponse.getEntity().consumeContent();
        return true;
    }

    private boolean performUploadRequest(final XoTransferAgent agent, String filename) throws IOException {
        try {
            HttpClient client = agent.getHttpClient();

            LOG.info("[uploadId: '" + clientUploadId + "'] performing upload request");

            int last = uploadLength - 1;

            int bytesToGo = uploadLength - this.progress;
            LOG.trace("PUT-upload '" + uploadUrl + "' '" + bytesToGo + "' bytes to go ");

            String uploadRange = "bytes " + this.progress + "-" + last + "/" + uploadLength;
            LOG.trace("PUT-upload '" + uploadUrl + "' with range '" + uploadRange + "'");

            mUploadRequest = new HttpPut(uploadUrl);
            if (this.progress > 0) {
                mUploadRequest.addHeader("Content-Range", uploadRange);
            }

            InputStream clearIs = agent.getClient().getHost().openInputStreamForUrl(filename);

            InputStream is = null;

            if (isAttachment()) {
                byte[] key = Hex.decode(encryptionKey);
                is = AESCryptor.encryptingInputStream(clearIs, key, AESCryptor.NULL_SALT);
            } else {
                is = clearIs;
            }

            is.skip(this.progress);

            final int startProgress = this.progress;
            IProgressListener progressListener = new IProgressListener() {
                int savedProgress = startProgress;
                @Override
                public void onProgress(int progress) {
                    // XXX this is the only place where we abort the request,
                    //     so we actually need to make progress to cancel. duh.
                    if(!agent.isUploadActive(TalkClientUpload.this)) {
                        mUploadRequest.abort();
                    }
                    LOG.trace("onProgress " + progress);

                    TalkClientUpload.this.progress = startProgress + progress;
                    savedProgress = maybeSaveProgress(agent, savedProgress);
                    agent.onUploadProgress(TalkClientUpload.this);
                }
            };
            mUploadRequest.setEntity(new ProgressOutputHttpEntity(is, bytesToGo, progressListener));
            LOG.trace("PUT-upload '" + uploadUrl + "' commencing");
            logRequestHeaders(mUploadRequest, "PUT-upload response header ");
            HttpResponse uploadResponse = client.execute(mUploadRequest);

            agent.onUploadStarted(this);

            this.progress = uploadLength;
            saveProgress(agent);
            StatusLine uploadStatus = uploadResponse.getStatusLine();
            int uploadSc = uploadStatus.getStatusCode();
            LOG.trace("PUT-upload '" + uploadUrl + "' with status '" + uploadSc + "': " + uploadStatus.getReasonPhrase());
            if(uploadSc != HttpStatus.SC_OK && uploadSc != 308 /* resume incomplete */) {
                // client error - mark as failed
                if(uploadSc >= 400 && uploadSc <= 499) {
                    markFailed(agent);
                }
                uploadResponse.getEntity().consumeContent();
                return false;
            }

            // dump headers
            logRequestHeaders(uploadResponse,"PUT-upload response header ");

            // process range header from upload request
            Header checkRangeHeader = uploadResponse.getFirstHeader("Range");
            if(checkRangeHeader != null) {
                checkCompletion(agent, checkRangeHeader);
            } else {
                LOG.warn("[uploadId: '" + clientUploadId + "'] no range header in upload response");
            }
            uploadResponse.getEntity().consumeContent();
        } catch (Exception e) {
            LOG.error("Exception while performing upload request: ", e);
        }

        return true;
    }

    private boolean checkCompletion(XoTransferAgent agent, Header checkRangeHeader) {
        int last = uploadLength - 1;
        int confirmedProgress = 0;

        ByteRange uploadedRange = ByteRange.parseContentRange(checkRangeHeader.getValue());

        LOG.info("probe returned uploaded range '" + uploadedRange.toContentRangeString() + "'");

        if(uploadedRange.hasTotal()) {
            if(uploadedRange.getTotal() != uploadLength) {
                LOG.error("server returned wrong upload length");
                markFailed(agent);
                return false;
            }
        }

        if(uploadedRange.hasStart()) {
            if(uploadedRange.getStart() != 0) {
                LOG.error("server returned non-zero start");
                markFailed(agent);
                return false;
            }
        }

        if(uploadedRange.hasEnd()) {
            confirmedProgress = (int)uploadedRange.getEnd() + 1;
        }

        LOG.info("progress believed " + progress + " confirmed " + confirmedProgress);
        this.progress = confirmedProgress;
        agent.onUploadProgress(this);

        if(uploadedRange.hasStart() && uploadedRange.hasEnd()) {
            if(uploadedRange.getStart() == 0 && uploadedRange.getEnd() == last) {
                LOG.info("upload complete");
                switchState(agent, State.COMPLETE);
                return true;
            }
        }

        return false;
    }

    private void markFailed(XoTransferAgent agent) {
        switchState(agent, State.FAILED);
        agent.onUploadFailed(this);
    }

    public void pauseUpload(XoTransferAgent agent) {
        if(mUploadRequest != null) {
            mUploadRequest.abort();
        }
        switchState(agent, State.PAUSED);
    }

    public void resumeUpload(XoTransferAgent agent) {
        switchState(agent, State.UPLOADING);
        performUploadAttempt(agent);
    }

    private void switchState(XoTransferAgent agent, State newState) {
        LOG.info("[upload " + clientUploadId + "] switching to state " + newState);

        state = newState;

        if(state == State.COMPLETE || state == State.FAILED) {
            if(encryptedFile != null) {
                String path = agent.getClient().getEncryptedUploadDirectory()
                        + File.separator + encryptedFile;
                File file = new File(path);
                file.delete();
            }
        }

        saveProgress(agent);

        agent.onUploadStateChanged(this);
    }

    private void saveProgress(XoTransferAgent agent) {
        try {
            agent.getDatabase().saveClientUpload(this);
        } catch (SQLException e) {
            LOG.error("sql error", e);
        }
    }

    private int maybeSaveProgress(XoTransferAgent agent, int previousProgress) {
        int delta = progress - previousProgress;
        if(delta > PROGRESS_SAVE_MINIMUM) {
            saveProgress(agent);
            return progress;
        }
        return previousProgress;
    }

}
