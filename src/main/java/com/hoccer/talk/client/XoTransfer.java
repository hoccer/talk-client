package com.hoccer.talk.client;

import com.hoccer.talk.content.IContentObject;

public abstract class XoTransfer implements IContentObject {

    public enum Direction {
        UPLOAD, DOWNLOAD
    }

    public enum Type {
        AVATAR, ATTACHMENT
    }

    private Direction mDirection;

    protected XoTransfer(Direction direction) {
        mDirection = direction;
    }


    public Direction getDirection() {
        return mDirection;
    }

    public boolean isDownload() {
        return mDirection == Direction.DOWNLOAD;
    }

    public boolean isUpload() {
        return mDirection == Direction.UPLOAD;
    }


    public abstract Type getTransferType();

    public boolean isAvatar() {
        return getTransferType() == Type.AVATAR;
    }

    public boolean isAttachment() {
        return getTransferType() == Type.ATTACHMENT;
    }

}
