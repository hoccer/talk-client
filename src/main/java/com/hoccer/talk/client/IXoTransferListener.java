package com.hoccer.talk.client;

import com.hoccer.talk.client.model.TalkClientDownload;
import com.hoccer.talk.client.model.TalkClientUpload;

public interface IXoTransferListener {

    public void onDownloadRegistered(TalkClientDownload download);
    public void onDownloadStarted(TalkClientDownload download);
    public void onDownloadProgress(TalkClientDownload download);
    public void onDownloadFinished(TalkClientDownload download);
    public void onDownloadStateChanged(TalkClientDownload download);

    public void onUploadStarted(TalkClientUpload upload);
    public void onUploadProgress(TalkClientUpload upload);
    public void onUploadFinished(TalkClientUpload upload);
    public void onUploadStateChanged(TalkClientUpload upload);

}
