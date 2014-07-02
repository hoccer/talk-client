package com.hoccer.talk.client.model;

import com.j256.ormlite.field.DatabaseField;
import com.j256.ormlite.table.DatabaseTable;
import org.apache.log4j.Logger;

/**
 * Encapsulates one relation between a media collection and one item.
 */
@DatabaseTable(tableName = "mediaCollectionRelation")
public class TalkClientMediaCollectionRelation {

    private static final Logger LOG = Logger.getLogger(TalkClientMediaCollectionRelation.class);

    @DatabaseField(generatedId = true, columnName = "relationId")
    private int mRelationId;

    @DatabaseField(columnName = "collection_id")
    private int mMediaCollectionId;

    @DatabaseField(columnName = "item", foreign = true)
    private TalkClientDownload mItem;

    @DatabaseField(columnName = "index")
    private int mIndex;

    // do not call constructor directly but create instances via IXoMediaCollectionDatabase.createMediaCollectionRelation()
    public TalkClientMediaCollectionRelation() {
    }

    public TalkClientMediaCollectionRelation(int collectionId, TalkClientDownload item, int index) {
        mMediaCollectionId = collectionId;
        mItem = item;
        mIndex = index;
    }

    public int getRelationId() {
        return mRelationId;
    }

    public int getMediaCollectionId() {
        return mMediaCollectionId;
    }

    public TalkClientDownload getItem() {
        return mItem;
    }

    // This setter updates the index locally only and does not update database fields automatically.
    public void setIndex(int index) {
        mIndex = index;
    }

    public int getIndex() {
        return mIndex;
    }
}
