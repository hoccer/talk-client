package com.hoccer.talk.client;

import com.hoccer.talk.client.model.TalkClientMediaCollection;

/**
 * Defines the interface for database changes of MediaCollection instances.
 */
public interface IXoMediaCollectionListener {

    void onMediaCollectionCreated(TalkClientMediaCollection collection);

    void onMediaCollectionRemoved(TalkClientMediaCollection collection);

    void onMediaCollectionUpdated(TalkClientMediaCollection collection);
}
