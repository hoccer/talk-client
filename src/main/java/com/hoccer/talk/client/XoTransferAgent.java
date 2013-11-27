package com.hoccer.talk.client;

import com.hoccer.talk.client.model.TalkClientDownload;
import com.hoccer.talk.client.model.TalkClientUpload;
import org.apache.http.client.HttpClient;
import org.apache.log4j.Logger;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class XoTransferAgent implements IXoTransferListener {

    private static final Logger LOG = Logger.getLogger(XoTransferAgent.class);

    XoClient mClient;
    XoClientDatabase mDatabase;

    ScheduledExecutorService mExecutor;

    List<IXoTransferListener> mListeners;

    HttpClient mHttpClient;

    Map<Integer, TalkClientDownload> mDownloadsById;
    Map<Integer, TalkClientUpload> mUploadsById;

    public XoTransferAgent(XoClient client) {
        mClient = client;
        mDatabase = mClient.getDatabase();
        mExecutor = Executors.newScheduledThreadPool(8);
        mListeners = new ArrayList<IXoTransferListener>();
        mDownloadsById = new HashMap<Integer, TalkClientDownload>();
        mUploadsById = new HashMap<Integer, TalkClientUpload>();
        initializeHttpClient();
    }

    private void initializeHttpClient() {
        mHttpClient = new HttpClientWithKeystore();
    }

    public XoClient getClient() {
        return mClient;
    }

    public XoClientDatabase getDatabase() {
        return mDatabase;
    }

    public HttpClient getHttpClient() {
        return mHttpClient;
    }

    public void registerListener(IXoTransferListener listener) {
        mListeners.add(listener);
    }

    public void unregisterListener(IXoTransferListener listener) {
        mListeners.remove(listener);
    }

    public boolean isDownloadActive(TalkClientDownload download) {
        synchronized (mDownloadsById) {
            return mDownloadsById.containsKey(download.getClientDownloadId());
        }
    }

    public void registerDownload(final TalkClientDownload download) {
        try {
            mDatabase.saveClientDownload(download);
        } catch (SQLException e) {
            LOG.error("sql error", e);
        }
        if(download.getState() == TalkClientDownload.State.INITIALIZING) {
            LOG.info("registerDownload(" + download.getClientDownloadId() + ")");
            download.switchState(this, TalkClientDownload.State.NEW);
            onDownloadRegistered(download);
        }
    }

    public void requestDownload(final TalkClientDownload download) {
        LOG.info("requestDownload()");

        registerDownload(download);

        synchronized (mDownloadsById) {
            final int downloadId = download.getClientDownloadId();
            if(!mDownloadsById.containsKey(downloadId)) {
                TalkClientDownload.State state = download.getState();
                if(state == TalkClientDownload.State.COMPLETE) {
                    LOG.debug("no need to download " + downloadId);
                    return;
                }

                LOG.info("requesting download " + downloadId);

                mDownloadsById.put(downloadId, download);

                mExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        LOG.info("performing download " + downloadId + " in state " + download.getState());
                        onDownloadStarted(download);
                        try {
                            download.performDownloadAttempt(XoTransferAgent.this);
                        } catch (Exception e) {
                            LOG.error("error performing download", e);
                        }
                        synchronized (mDownloadsById) {
                            mDownloadsById.remove(downloadId);
                        }
                        onDownloadFinished(download);
                    }
                });
            } else {
                LOG.info("download " + download.getClientDownloadId() + " already active");
            }
        }
    }

    public void cancelDownload(TalkClientDownload download) {
        LOG.info("cancelDownload(" + download.getClientDownloadId() + ")");
        synchronized (mDownloadsById) {
            mDownloadsById.remove(download.getClientDownloadId());
        }
    }

    public boolean isUploadActive(TalkClientUpload upload) {
        synchronized (mUploadsById) {
            return mUploadsById.containsKey(upload.getClientUploadId());
        }
    }

    public void requestUpload(final TalkClientUpload upload) {
        LOG.info("requestUpload()");

        try {
            mDatabase.saveClientUpload(upload);
        } catch (SQLException e) {
            LOG.error("sql error", e);
        }

        synchronized (mUploadsById) {
            final int uploadId = upload.getClientUploadId();
            if(!mUploadsById.containsKey(uploadId)) {
                TalkClientUpload.State state = upload.getState();
                if(state == TalkClientUpload.State.COMPLETE) {
                    LOG.debug("no need to upload " + uploadId);
                }

                LOG.info("requesting upload " + uploadId);

                mUploadsById.put(uploadId, upload);

                mExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        LOG.info("performing upload " + uploadId + " in state " + upload.getState());
                        onUploadStarted(upload);
                        try {
                            upload.performUploadAttempt(XoTransferAgent.this);
                        } catch (Exception e) {
                            LOG.error("error performing upload", e);
                        }
                        synchronized (mUploadsById) {
                            mUploadsById.remove(uploadId);
                        }
                        onUploadFinished(upload);
                    }
                });
            } else {
                LOG.info("upload " + upload.getClientUploadId() + " already active");
            }
        }
    }

    public void cancelUpload(TalkClientUpload upload) {
        LOG.info("cancelUpload(" + upload.getClientUploadId() + ")");
        synchronized (mUploadsById) {
            mUploadsById.remove(upload.getClientUploadId());
        }
    }

    @Override
    public void onDownloadRegistered(TalkClientDownload download) {
        LOG.info("onDownloadRegistered(" + download.getClientDownloadId() + ")");
        for(IXoTransferListener listener: mListeners) {
            listener.onDownloadRegistered(download);
        }
    }

    @Override
    public void onDownloadStarted(TalkClientDownload download) {
        LOG.info("onDownloadStarted(" + download.getClientDownloadId() + ")");
        for(IXoTransferListener listener: mListeners) {
            listener.onDownloadStarted(download);
        }
    }

    @Override
    public void onDownloadProgress(TalkClientDownload download) {
        LOG.trace("onDownloadProgress(" + download.getClientDownloadId() + ")");
        for(IXoTransferListener listener: mListeners) {
            listener.onDownloadProgress(download);
        }
    }

    @Override
    public void onDownloadFinished(TalkClientDownload download) {
        LOG.info("onDownloadFinished(" + download.getClientDownloadId() + ")");
        for(IXoTransferListener listener: mListeners) {
            listener.onDownloadFinished(download);
        }
    }

    @Override
    public void onDownloadStateChanged(TalkClientDownload download) {
        LOG.trace("onDownloadStateChanged(" + download.getClientDownloadId() + ")");
        for(IXoTransferListener listener: mListeners) {
            listener.onDownloadStateChanged(download);
        }
    }

    @Override
    public void onUploadStarted(TalkClientUpload upload) {
        LOG.info("onUploadStarted(" + upload.getClientUploadId() + ")");
        for(IXoTransferListener listener: mListeners) {
            listener.onUploadStarted(upload);
        }
    }

    @Override
    public void onUploadProgress(TalkClientUpload upload) {
        LOG.trace("onUploadProgress(" + upload.getClientUploadId() + ")");
        for(IXoTransferListener listener: mListeners) {
            listener.onUploadProgress(upload);
        }
    }

    @Override
    public void onUploadFinished(TalkClientUpload upload) {
        LOG.info("onUploadFinished(" + upload.getClientUploadId() + ")");
        for(IXoTransferListener listener: mListeners) {
            listener.onUploadFinished(upload);
        }
    }

    @Override
    public void onUploadStateChanged(TalkClientUpload upload) {
        LOG.trace("onUploadStateChanged(" + upload.getClientUploadId() + ")");
        for(IXoTransferListener listener: mListeners) {
            listener.onUploadStateChanged(upload);
        }
    }
}
