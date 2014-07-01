package com.hoccer.talk.client.model;

import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;
import org.apache.log4j.Logger;

/**
 * Encapsulates one relation between a media collection and one item.
 */
@DatabaseTable(tableName = "mediaCollectionRelation")
public class TalkClientMediaCollectionRelation {

    private static final Logger LOG = Logger.getLogger(TalkClientMediaCollection.class);

    @DatabaseField(generatedId = true, columnName = "relationId")
    private int mRelationId;

    @DatabaseField(columnName = "collection_id")
    private int mMediaCollectionId;

    @DatabaseField(columnName = "item_id")
    private int mItemId;

    @DatabaseField(columnName = "index")
    private int mIndex;

    // do not call constructor directly but create instances via IXoMediaCollectionDatabase.createMediaCollectionRelation()
    public TalkClientMediaCollectionRelation() {
        LOG.error("TalkClientMediaCollection default constructor should never be called!");
    }

    public TalkClientMediaCollectionRelation(int collectionId, int itemId, int index) {
        mMediaCollectionId = collectionId;
        mItemId = itemId;
        mIndex = index;
    }

    public int getRelationId() {
        return mRelationId;
    }

    public int getMediaCollectionId() {
        return mMediaCollectionId;
    }

    public int getItemId() {
        return mMediaCollectionId;
    }

    // This setter updates the index locally only and does not update database fields automatically.
    public void setIndex(int index) {
        mIndex = index;
    }

    public int getIndex() {
        return mIndex;
    }
}
