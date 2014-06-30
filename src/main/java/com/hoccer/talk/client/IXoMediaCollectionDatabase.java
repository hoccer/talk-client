package com.hoccer.talk.client;

import com.hoccer.talk.client.model.TalkClientDownload;
import com.hoccer.talk.client.model.TalkClientMediaCollection;

import java.sql.SQLException;
import java.util.List;

/**
 * Defines the interface of a database implementation managing MediaCollection instances
 */
public interface IXoMediaCollectionDatabase {

    TalkClientMediaCollection findMediaCollectionById(Integer id) throws SQLException;

    List<TalkClientMediaCollection> findMediaCollectionsByName(String name) throws SQLException;

    TalkClientDownload findMediaCollectionItemById(Integer itemId) throws SQLException;

    void createMediaCollection(TalkClientMediaCollection collection) throws SQLException;

    void deleteMediaCollection(TalkClientMediaCollection collection) throws SQLException;

    void deleteMediaCollectionById(int collectionId) throws SQLException;

    void updateMediaCollection(TalkClientMediaCollection collection) throws SQLException;

    void refreshMediaCollection(TalkClientMediaCollection collection) throws SQLException;

    void registerMediaCollectionListener(IXoMediaCollectionListener listener);

    void unregisterMediaCollectionListener(IXoMediaCollectionListener listener);
}
