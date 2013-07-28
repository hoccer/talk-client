package com.hoccer.talk.client;

public abstract class TalkTransfer {

    public enum Direction {
        UPLOAD, DOWNLOAD
    }

    public enum Type {
        AVATAR, ATTACHMENT
    }

    private Direction mDirection;

    protected TalkTransfer(Direction direction) {
        mDirection = direction;
    }

    public Direction getDirection() {
        return mDirection;
    }

}
