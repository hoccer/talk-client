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

    List<TalkClientMediaCollection> findAllMediaCollections() throws SQLException;

    TalkClientMediaCollection createMediaCollection(String collectionName) throws SQLException;

    void deleteMediaCollection(TalkClientMediaCollection collection) throws SQLException;

    void deleteMediaCollectionById(int collectionId) throws SQLException;

    List<TalkClientDownload> findMediaCollectionItemsOrderedByIndex(int collectionId) throws SQLException;

    void updateMediaCollection(TalkClientMediaCollection collection) throws SQLException;

    void createMediaCollectionRelation(int collectionId, int itemId, int index) throws SQLException;

    void removeMediaCollectionRelationAtIndex(int collectionId, int index) throws SQLException;

    void refreshMediaCollection(TalkClientMediaCollection collection) throws SQLException;
}
