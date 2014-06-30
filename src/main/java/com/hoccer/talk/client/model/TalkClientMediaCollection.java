package com.hoccer.talk.client.model;

import com.hoccer.talk.client.IXoMediaCollectionDatabase;
import com.hoccer.talk.client.IXoMediaCollectionListener;
import com.j256.ormlite.field.DatabaseField;
import org.apache.log4j.Logger;

import java.sql.SQLException;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

/**
 * Encapsulates a collection of media items with a specific order. The data is kept in sync with the database.
 */
public class TalkClientMediaCollection implements IXoMediaCollectionListener {

    private static final Logger LOG = Logger.getLogger(TalkClientMediaCollection.class);

    @DatabaseField(generatedId = true, columnName = "collectionId")
    private int mCollectionId;

    @DatabaseField(columnName = "name")
    private String mName;

    @DatabaseField(columnName = "itemIdList")
    private List<Integer> mItemIdList;

    private final IXoMediaCollectionDatabase mDatabase;

    private List<TalkClientDownload> mItemList;

    private Boolean mNeedsUpdate = true;

    // do not call constructor directly but create instances via IXoMediaCollectionDatabase.create() instead
    public TalkClientMediaCollection(String name, IXoMediaCollectionDatabase db) {
        mName = name;
        mDatabase = db;
        mDatabase.registerMediaCollectionListener(this);
        refreshFromDatabase();
    }

    public String getName() {
        return mName;
    }

    // Appends the given item to the collection
    public void add(TalkClientDownload item) {
        mItemList.add(item);
        mItemIdList.add(item.getClientDownloadId());
        refreshFromDatabase();
        updateDatabase();
    }

    // Appends the given items to the collection
    public void addAll(Collection<TalkClientDownload> items) {
        mItemList.addAll(items);

        for (Iterator<TalkClientDownload> iterator = items.iterator();
             iterator.hasNext(); )
        {
            int downloadId = iterator.next().getClientDownloadId();
            mItemIdList.add(downloadId);
        }
        refreshFromDatabase();
        updateDatabase();
    }

    // Appends the given item to the collection
    public void add(int index, TalkClientDownload item) {
        mItemList.add(index, item);
        mItemIdList.add(index, item.getClientDownloadId());
        refreshFromDatabase();
        updateDatabase();
    }

    // Returns the size of the collection array
    public int size() {
        refreshFromDatabase();
        return mItemList.size();
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

    private void refreshFromDatabase() {
        if(mNeedsUpdate) {
            // we set the flag to false first and implicitly allow that it might be set to true while refreshing again
            mNeedsUpdate = false;

            try {
                mDatabase.refreshMediaCollection(this);
            } catch(SQLException e) {
                LOG.error(e.getMessage());
                e.printStackTrace();
                return;
            }

            refreshCache();
            LOG.debug("MediaCollection instance for collection '" + mName + "' has been refreshed.");
        }
    }

    private void refreshCache() {
        // buffer previously cached download instances for reuse
        List<TalkClientDownload> downloadBuffer = mItemList;
        mItemList.clear();

        // for every item id in collection check if the download is buffered
        for(int i = 0; i < mItemIdList.size(); i++) {
            Integer downloadId = mItemIdList.get(i);
            TalkClientDownload download = null;

            for(int bufferIndex = 0; bufferIndex < downloadBuffer.size(); bufferIndex++) {
                TalkClientDownload bufferedDownload = downloadBuffer.get(bufferIndex);
                if(bufferedDownload.getClientDownloadId() == downloadId) {
                    download = bufferedDownload;
                    break;
                }
            }

            // if we could not find the download in the buffer retrieve it from database
            try {
                download = mDatabase.findMediaCollectionItemById(downloadId);
            } catch(SQLException e) {
                LOG.error(e.getMessage());
                e.printStackTrace();
                continue;
            }
            mItemList.add(download);
        }
    }

    private void updateDatabase() {
        try {
            mDatabase.updateMediaCollection(this);
        } catch(SQLException e) {
            LOG.error(e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void onMediaCollectionCreated(TalkClientMediaCollection collection) {
    }

    @Override
    public void onMediaCollectionRemoved(TalkClientMediaCollection collection) {
        if(collection.equals(this)) {
            mNeedsUpdate = true;
        }
    }

    @Override
    public void onMediaCollectionUpdated(TalkClientMediaCollection collection) {
        if(collection.equals(this)) {
            mNeedsUpdate = true;
        }
    }
}
