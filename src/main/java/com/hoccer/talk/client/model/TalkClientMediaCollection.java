package com.hoccer.talk.client.model;

import com.hoccer.talk.client.IXoMediaCollectionDatabase;
import com.hoccer.talk.client.IXoMediaCollectionListener;
import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;
import org.apache.log4j.Logger;

import java.sql.SQLException;
import java.util.*;

/**
 * Encapsulates a collection of media items with a specific order. The data is kept in sync with the database.
 */
@DatabaseTable(tableName = "mediaCollection")
public class TalkClientMediaCollection implements IXoMediaCollectionListener {

    private static final Logger LOG = Logger.getLogger(TalkClientMediaCollection.class);

    @DatabaseField(generatedId = true, columnName = "collectionId")
    private int mCollectionId;

    @DatabaseField(columnName = "name")
    private String mName;

    private IXoMediaCollectionDatabase mDatabase;

    private List<TalkClientDownload> mItemList;

    private Boolean mNeedsRefresh = true;

    // do not call constructor directly but create instances via IXoMediaCollectionDatabase.createMediaCollection()
    public TalkClientMediaCollection() {
        LOG.error("TalkClientMediaCollection default constructor should never be called!");
    }

    // do not call constructor directly but create instances via IXoMediaCollectionDatabase.createMediaCollection()
    public TalkClientMediaCollection(String collectionName) {
        mName = collectionName;
    }

    public void setDatabase(IXoMediaCollectionDatabase db) {
        mDatabase = db;
        mDatabase.registerMediaCollectionListener(this);
        refreshFromDatabase();
    }

    public void setName(String name) {
        mName = name;
        updateCollectionInDatabase();
    }

    public String getName() {
        return mName;
    }

    // Appends the given item to the collection
    public void add(TalkClientDownload item) {
        createRelation(item, mItemList.size());
        refreshFromDatabase();
    }

    // Inserts the given item into the collection
    public void add(int index, TalkClientDownload item) {
        if(index >= mItemList.size()) {
            add(item); // simply append
        } else {
            createRelation(item, index);
            refreshFromDatabase();
        }
    }

    // Removes the given item from the collection
    public void remove(TalkClientDownload item) {
        int index = mItemList.indexOf(item);
        if(index >= 0) {
            remove(index);
        }
    }

    // Removes the item at the given index from the collection
    public void remove(int index) {
        removeRelation(index);
        refreshFromDatabase();
    }

    // Returns the size of the collection array
    public int size() {
        refreshFromDatabase();
        return mItemList.size();
    }

    private void createRelation(TalkClientDownload item, int index) {
        try {
            mDatabase.createMediaCollectionRelation(mCollectionId, item.getClientDownloadId(), index);
        } catch(SQLException e) {
            LOG.error(e.getMessage());
            e.printStackTrace();
        }
    }

    private void removeRelation(int index) {
        try {
            mDatabase.removeMediaCollectionRelationAtIndex(mCollectionId, index);
        } catch(SQLException e) {
            LOG.error(e.getMessage());
            e.printStackTrace();
        }
    }

    private void updateCollectionInDatabase() {
        try {
            mDatabase.updateMediaCollection(this);
        } catch(SQLException e) {
            LOG.error(e.getMessage());
            e.printStackTrace();
            return;
        }
    }

    private void refreshFromDatabase() {
        if(mNeedsRefresh) {
            // we set the flag to false first and implicitly allow that it might be set to true while refreshing again
            mNeedsRefresh = false;

            try {
                // refresh collection name
                mDatabase.refreshMediaCollection(this);
                mItemList = mDatabase.findMediaCollectionItemsOrderedByIndex(mCollectionId);
            } catch(SQLException e) {
                LOG.error(e.getMessage());
                e.printStackTrace();
                return;
            }
            LOG.debug("MediaCollection instance for collection '" + mName + "' has been refreshed.");
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;

        TalkClientMediaCollection collection = (TalkClientMediaCollection) o;
        if(collection == null) {
            return false;
        }
        return mCollectionId == collection.mCollectionId;
    }

    @Override
    public void onMediaCollectionCreated(TalkClientMediaCollection collection) {
    }

    @Override
    public void onMediaCollectionRemoved(TalkClientMediaCollection collection) {
        if(collection.equals(this)) {
            mNeedsRefresh = true;
        }
    }

    @Override
    public void onMediaCollectionUpdated(TalkClientMediaCollection collection) {
        if(collection.equals(this)) {
            mNeedsRefresh = true;
        }
    }
}
