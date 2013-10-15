package com.hoccer.talk.client;

public abstract class XoTransfer {

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

}
