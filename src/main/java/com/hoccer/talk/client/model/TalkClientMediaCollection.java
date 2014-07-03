package com.hoccer.talk.client.model;

import com.hoccer.talk.client.IXoDatabaseRefreshListener;
import com.hoccer.talk.client.IXoMediaCollectionDatabase;
import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;
import org.apache.log4j.Logger;

import java.sql.SQLException;
import java.util.*;

/**
 * Encapsulates a collection of media items with a specific order. The data is kept in sync with the database.
 */
@DatabaseTable(tableName = "mediaCollection")
public class TalkClientMediaCollection {

    private static final Logger LOG = Logger.getLogger(TalkClientMediaCollection.class);

    @DatabaseField(generatedId = true, columnName = "collectionId")
    private int mCollectionId;

    @DatabaseField(columnName = "name")
    private String mName;

    private IXoMediaCollectionDatabase mDatabase;

    private List<TalkClientDownload> mItemList = new ArrayList<TalkClientDownload>();

    // do not call constructor directly but create instances via IXoMediaCollectionDatabase.createMediaCollection()
    public TalkClientMediaCollection() {
    }

    // do not call constructor directly but create instances via IXoMediaCollectionDatabase.createMediaCollection()
    public TalkClientMediaCollection(String collectionName) {
        mName = collectionName;
    }

    public Integer getId() {
        return mCollectionId;
    }

    // this method should only by called by the database on instantiation
    public void setDatabase(IXoMediaCollectionDatabase db) {
        mDatabase = db;
        mItemList = findMediaCollectionItemsOrderedByIndex();
    }

    public void setName(String name) {
        mName = name;
        updateMediaCollection();
    }

    public String getName() {
        return mName;
    }

    // Appends the given item to the collection
    public void add(TalkClientDownload item) {
        if(createRelation(item, mItemList.size())) {
            mItemList.add(item);
        }
    }

    // Inserts the given item into the collection
    public void add(int index, TalkClientDownload item) {
        if(index >= mItemList.size()) {
            add(item); // simply append
        } else {
            if(createRelation(item, index)) {
                mItemList.add(index, item);
            }
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
        if(removeRelation(index)) {
            mItemList.remove(index);
        }
    }

    // Moves the item at index 'from' to index 'to'.
    // Throws an IndexOutOfBoundsException if 'from' or 'to' is out of bounds.
    public void moveItemFromToIndex(int from, int to) {
    }

    // Returns the size of the collection array
    public int size() {
        return mItemList.size();
    }

    public TalkClientDownload getItem(int index) {
        return mItemList.get(index);
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

    private List<TalkClientDownload> findMediaCollectionItemsOrderedByIndex() {
        List<TalkClientDownload> items = new ArrayList<TalkClientDownload>();

        try {
            List<TalkClientMediaCollectionRelation> relations = mDatabase.getMediaCollectionRelationDao().queryBuilder()
                    .orderBy("index", true)
                    .where()
                    .eq("collection_id", mCollectionId)
                    .query();

            for(TalkClientMediaCollectionRelation relation : relations) {
                items.add(relation.getItem());
            }
        } catch(SQLException e) {
            e.printStackTrace();
        }

        return items;
    }

    private boolean createRelation(TalkClientDownload item, int index) {
        try {
            // increment index of all items with same or higher index
            List<TalkClientMediaCollectionRelation> relations = mDatabase.getMediaCollectionRelationDao().queryBuilder()
                    .where()
                    .not()
                    .lt("index", index)
                    .and()
                    .eq("collection_id", mCollectionId)
                    .query();

            for(int i = 0; i < relations.size(); i++) {
                TalkClientMediaCollectionRelation relation = relations.get(i);
                relation.setIndex(relation.getIndex() + 1);
                mDatabase.getMediaCollectionRelationDao().update(relation);
            }

            TalkClientMediaCollectionRelation newRelation = new TalkClientMediaCollectionRelation(mCollectionId, item, index);
            mDatabase.getMediaCollectionRelationDao().create(newRelation);
        } catch(SQLException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    private boolean removeRelation(int index) {
        try {
            TalkClientMediaCollectionRelation relationToDelete = mDatabase.getMediaCollectionRelationDao().queryBuilder()
                    .where()
                    .eq("collection_id", mCollectionId)
                    .and()
                    .eq("index", index)
                    .queryForFirst();

            if(relationToDelete != null) {
                mDatabase.getMediaCollectionRelationDao().delete(relationToDelete);

                // decrement index of all items with higher index
                List<TalkClientMediaCollectionRelation> relations = mDatabase.getMediaCollectionRelationDao().queryBuilder()
                        .where()
                        .not()
                        .le("index", index)
                        .and()
                        .eq("collection_id", mCollectionId)
                        .query();

                for (int i = 0; i < relations.size(); i++) {
                    TalkClientMediaCollectionRelation relation = relations.get(i);
                    relation.setIndex(relation.getIndex() - 1);
                    mDatabase.getMediaCollectionRelationDao().update(relation);
                }
            }
        } catch(SQLException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }

    private boolean updateMediaCollection() {
        try {
            mDatabase.getMediaCollectionDao().update(this);
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
        return true;
    }
}
