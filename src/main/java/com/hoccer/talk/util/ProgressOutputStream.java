package com.hoccer.talk.util;

import java.io.IOException;
import java.io.OutputStream;

public class ProgressOutputStream extends OutputStream {

    int mProgress;

    OutputStream mWrapped;

    IProgressListener mListener;

    public ProgressOutputStream(OutputStream wrapped, IProgressListener listener) {
        mWrapped = wrapped;
        mListener = listener;
    }

    public int getProgress() {
        return mProgress;
    }

    @Override
    public void write(int b) throws IOException {
        mWrapped.write(b);
        mProgress += 1;
        callListener();
    }

    @Override
    public void write(byte[] b) throws IOException {
        mWrapped.write(b);
        mProgress += b.length;
        callListener();
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        mWrapped.write(b, off, len);
        mProgress += len;
        callListener();
    }

    private void callListener() {
        if(mListener != null) {
            mListener.onProgress(mProgress);
        }
    }

    @Override
    public void flush() throws IOException {
        mWrapped.flush();
    }

    @Override
    public void close() throws IOException {
        mWrapped.close();
    }

}
