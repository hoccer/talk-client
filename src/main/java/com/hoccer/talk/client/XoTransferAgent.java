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
        mExecutor = Executors.newSingleThreadScheduledExecutor();
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

    public void requestDownload(final TalkClientDownload download) {
        synchronized (mDownloadsById) {
            final int downloadId = download.getClientDownloadId();
            if(!mDownloadsById.containsKey(downloadId)) {
                TalkClientDownload.State state = download.getState();
                if(state == TalkClientDownload.State.COMPLETE) {
                    LOG.debug("no need to download " + downloadId);
                    return;
                }
                if(state == TalkClientDownload.State.FAILED) {
                    LOG.warn("can't resume failed download " + downloadId);
                    return;
                }

                LOG.debug("requesting download " + downloadId);
                download.noteRequested();

                try {
                    mDatabase.saveClientDownload(download);
                } catch (SQLException e) {
                    LOG.error("sql error", e);
                }

                mDownloadsById.put(downloadId, download);

                mExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        LOG.info("performing download " + downloadId + " in state " + download.getState());
                        onDownloadStarted(download);
                        download.performDownloadAttempt(XoTransferAgent.this);
                        synchronized (mDownloadsById) {
                            mDownloadsById.remove(downloadId);
                            onDownloadFinished(download);
                        }
                    }
                });
            }
        }
    }

    public void requestUpload(final TalkClientUpload upload) {
        synchronized (mUploadsById) {
            final int uploadId = upload.getClientUploadId();
            if(!mUploadsById.containsKey(uploadId)) {
                TalkClientUpload.State state = upload.getState();
                if(state == TalkClientUpload.State.COMPLETE) {
                    LOG.debug("no need to upload " + uploadId);
                }
                if(state == TalkClientUpload.State.FAILED) {
                    LOG.warn("can't resume failed upload " + uploadId);
                }

                LOG.info("requesting upload " + uploadId);

                mUploadsById.put(uploadId, upload);

                mExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        LOG.info("performing upload " + uploadId + " in state " + upload.getState());
                        onUploadStarted(upload);
                        upload.performUploadAttempt(XoTransferAgent.this);
                        synchronized (mUploadsById) {
                            mUploadsById.remove(uploadId);
                            onUploadFinished(upload);
                        }
                    }
                });
            }
        }
    }

    @Override
    public void onDownloadStarted(TalkClientDownload download) {
        for(IXoTransferListener listener: mListeners) {
            listener.onDownloadStarted(download);
        }
    }

    @Override
    public void onDownloadProgress(TalkClientDownload download) {
        for(IXoTransferListener listener: mListeners) {
            listener.onDownloadProgress(download);
        }
    }

    @Override
    public void onDownloadFinished(TalkClientDownload download) {
        for(IXoTransferListener listener: mListeners) {
            listener.onDownloadFinished(download);
        }
    }

    @Override
    public void onDownloadStateChanged(TalkClientDownload download) {
        for(IXoTransferListener listener: mListeners) {
            listener.onDownloadStateChanged(download);
        }
    }

    @Override
    public void onUploadStarted(TalkClientUpload upload) {
        for(IXoTransferListener listener: mListeners) {
            listener.onUploadStarted(upload);
        }
    }

    @Override
    public void onUploadProgress(TalkClientUpload upload) {
        for(IXoTransferListener listener: mListeners) {
            listener.onUploadProgress(upload);
        }
    }

    @Override
    public void onUploadFinished(TalkClientUpload upload) {
        for(IXoTransferListener listener: mListeners) {
            listener.onUploadFinished(upload);
        }
    }

    @Override
    public void onUploadStateChanged(TalkClientUpload upload) {
        for(IXoTransferListener listener: mListeners) {
            listener.onUploadStateChanged(upload);
        }
    }
}
