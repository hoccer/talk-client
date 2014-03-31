package com.hoccer.talk.util;

import org.apache.http.entity.InputStreamEntity;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

//public class ProgressOutputHttpEntity extends InputStreamEntity {
public class ProgressOutputHttpEntity extends InputStreamEntity {

    IProgressListener mProgressListener;

    ProgressOutputStream mProgressStream = null;

    public ProgressOutputHttpEntity(InputStream istream, int length, IProgressListener listener) {
        super(istream, length);
        mProgressListener = listener;
    }

    @Override
    public void writeTo(OutputStream outstream) throws IOException {
        mProgressStream = new ProgressOutputStream(outstream, mProgressListener);
        super.writeTo(mProgressStream);
    }

}
