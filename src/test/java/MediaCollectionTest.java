import com.hoccer.talk.client.IXoClientDatabaseBackend;
import com.hoccer.talk.client.XoClientDatabase;
import com.hoccer.talk.client.model.TalkClientDownload;
import com.hoccer.talk.client.model.TalkClientMediaCollection;
import com.hoccer.talk.client.model.TalkClientMediaCollectionRelation;
import com.j256.ormlite.dao.Dao;
import com.j256.ormlite.dao.DaoManager;
import com.j256.ormlite.jdbc.JdbcConnectionSource;
import com.j256.ormlite.support.ConnectionSource;
import org.apache.log4j.Logger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertNotNull;
import static junit.framework.TestCase.fail;

public class MediaCollectionTest {

    private static final Logger LOG = Logger.getLogger(MediaCollectionTest.class);

    private XoClientDatabase mDatabase;

    private JdbcConnectionSource mConnectionSource;

    @Before
    public void testSetup() throws Exception {
        mConnectionSource = new JdbcConnectionSource("jdbc:h2:mem:account");
        mDatabase = new XoClientDatabase(new IXoClientDatabaseBackend() {

            @Override
            public ConnectionSource getConnectionSource() {
                return mConnectionSource;
            }

            @Override
            public <D extends Dao<T, ?>, T> D getDao(Class<T> clazz) throws SQLException {
                return DaoManager.createDao(mConnectionSource, clazz);
            }
        });

        mDatabase.createTables(mConnectionSource);
        mDatabase.initialize();
    }

    @After
    public void testCleanup() throws SQLException {
        mConnectionSource.close();
    }

    @Test
    public void testCreateCollection() {
        LOG.info("testCreateCollection");

        String collectionName = "testCreateCollection_collection";
        TalkClientMediaCollection collection = null;
        try {
            collection = mDatabase.createMediaCollection(collectionName);
        } catch(SQLException e) {
            e.printStackTrace();
            fail();
        }

        assertNotNull(collection);
        assertEquals(collection.getName(), collectionName);
        assertEquals(collection.size(), 0);

        TalkClientMediaCollection collectionCopy = null;
        try {
            collectionCopy = mDatabase.findMediaCollectionById(collection.getId());
        } catch(SQLException e) {
            e.printStackTrace();
            fail();
        }

        assertNotNull(collectionCopy);
        assertEquals(collectionCopy.getName(), collectionName);
        assertEquals(collectionCopy.size(), 0);

        // check database directly
        List<TalkClientMediaCollection> collections = null;
        try {
        collections = mDatabase.findAllMediaCollections();
        } catch (SQLException e) {
            e.printStackTrace();
            fail();
        }

        assertNotNull(collections);
        assertEquals(collections.size(), 1);
        assertEquals(collections.get(0).getId(), collection.getId());
    }

    @Test
    public void testDeleteCollectionByReference() {
        LOG.info("testDeleteCollectionByReference");

        String collectionName = "testDeleteCollectionByReference_collection";
        TalkClientMediaCollection collection = null;
        try {
            collection = mDatabase.createMediaCollection(collectionName);
        } catch(SQLException e) {
            e.printStackTrace();
            fail();
        }

        assertNotNull(collection);
        assertEquals(collection.getName(), collectionName);
        assertEquals(collection.size(), 0);

        // check database directly
        {
            List<TalkClientMediaCollection> collections = null;
            try {
                collections = mDatabase.findAllMediaCollections();
            } catch (SQLException e) {
                e.printStackTrace();
                fail();
            }

            assertNotNull(collections);
            assertEquals(collections.size(), 1);
            assertEquals(collections.get(0).getId(), collection.getId());
        }

        try {
            mDatabase.deleteMediaCollection(collection);
        } catch (SQLException e) {
            e.printStackTrace();
            fail();
        }

        // check database directly
        {
            List<TalkClientMediaCollection> collections = null;
            try {
                collections = mDatabase.findAllMediaCollections();
            } catch (SQLException e) {
                e.printStackTrace();
                fail();
            }

            assertNotNull(collections);
            assertEquals(collections.size(), 0);
        }
    }

    @Test
    public void testDeleteCollectionById() {
        LOG.info("testDeleteCollectionById");

        String collectionName = "testDeleteCollectionById_collection";
        TalkClientMediaCollection collection = null;
        try {
            collection = mDatabase.createMediaCollection(collectionName);
        } catch(SQLException e) {
            e.printStackTrace();
            fail();
        }

        assertNotNull(collection);
        assertEquals(collection.getName(), collectionName);
        assertEquals(collection.size(), 0);

        // check database directly
        {
            List<TalkClientMediaCollection> collections = null;
            try {
                collections = mDatabase.findAllMediaCollections();
            } catch (SQLException e) {
                e.printStackTrace();
                fail();
            }

            assertNotNull(collections);
            assertEquals(collections.size(), 1);
            assertEquals(collections.get(0).getId(), collection.getId());
        }

        try {
            mDatabase.deleteMediaCollectionById(collection.getId());
        } catch (SQLException e) {
            e.printStackTrace();
            fail();
        }

        // check database directly
        {
            List<TalkClientMediaCollection> collections = null;
            try {
                collections = mDatabase.findAllMediaCollections();
            } catch (SQLException e) {
                e.printStackTrace();
                fail();
            }

            assertNotNull(collections);
            assertEquals(collections.size(), 0);
        }
    }

    @Test
    public void testAddItems() {
        LOG.info("testAddItems");

        String collectionName = "testAddItems_collection";

        TalkClientMediaCollection collection = null;
        TalkClientDownload item0 = new TalkClientDownload();
        TalkClientDownload item1 = new TalkClientDownload();
        TalkClientDownload item2 = new TalkClientDownload();
        try {
            collection = mDatabase.createMediaCollection(collectionName);

            assertNotNull(collection);
            assertEquals(collection.getName(), collectionName);

            // create some items and add to collection
            mDatabase.saveClientDownload(item0);
            mDatabase.saveClientDownload(item1);
            mDatabase.saveClientDownload(item2);
            collection.add(item0);
            collection.add(item1);
            collection.add(item2);
        } catch (SQLException e) {
            LOG.error(e.getMessage());
            e.printStackTrace();
            fail();
        }

        assertEquals(collection.size(), 3);
        assertEquals(collection.getItem(0).getClientDownloadId(), item0.getClientDownloadId());
        assertEquals(collection.getItem(1).getClientDownloadId(), item1.getClientDownloadId());
        assertEquals(collection.getItem(2).getClientDownloadId(), item2.getClientDownloadId());

        // check database directly
        List<TalkClientMediaCollectionRelation> relations = findMediaCollectionRelationsOrderedByIndex(collection.getId());
        assertNotNull(relations);
        assertEquals(relations.size(), 3);
        assertEquals(relations.get(0).getIndex(), 0);
        assertEquals(relations.get(0).getItem().getClientDownloadId(), item0.getClientDownloadId());
        assertEquals(relations.get(1).getIndex(), 1);
        assertEquals(relations.get(1).getItem().getClientDownloadId(), item1.getClientDownloadId());
        assertEquals(relations.get(2).getIndex(), 2);
        assertEquals(relations.get(2).getItem().getClientDownloadId(), item2.getClientDownloadId());
    }

    @Test
    public void testInsertItems() {
        LOG.info("testInsertItems");

        String collectionName = "testInsertItems_collection";

        TalkClientMediaCollection collection = null;
        TalkClientDownload item0 = new TalkClientDownload();
        TalkClientDownload item1 = new TalkClientDownload();
        TalkClientDownload item2 = new TalkClientDownload();
        TalkClientDownload item3 = new TalkClientDownload();
        try {
            collection = mDatabase.createMediaCollection(collectionName);

            assertNotNull(collection);
            assertEquals(collection.getName(), collectionName);

            // create some items and add to collection
            mDatabase.saveClientDownload(item0);
            mDatabase.saveClientDownload(item1);
            mDatabase.saveClientDownload(item2);
            mDatabase.saveClientDownload(item3);

            // insert items at "random" positions
            collection.add(5, item0); // order: 0
            collection.add(1, item1); // order: 0 1
            collection.add(0, item2); // order: 2 0 1
            collection.add(1, item3); // order: 2 3 0 1
        } catch (SQLException e) {
            LOG.error(e.getMessage());
            e.printStackTrace();
            fail();
        }

        assertEquals(collection.size(), 4);
        assertEquals(collection.getItem(0).getClientDownloadId(), item2.getClientDownloadId());
        assertEquals(collection.getItem(1).getClientDownloadId(), item3.getClientDownloadId());
        assertEquals(collection.getItem(2).getClientDownloadId(), item0.getClientDownloadId());
        assertEquals(collection.getItem(3).getClientDownloadId(), item1.getClientDownloadId());

        // check database directly
        List<TalkClientMediaCollectionRelation> relations = findMediaCollectionRelationsOrderedByIndex(collection.getId());
        assertNotNull(relations);
        assertEquals(relations.size(), 4);
        assertEquals(relations.get(0).getIndex(), 0);
        assertEquals(relations.get(0).getItem().getClientDownloadId(), item2.getClientDownloadId());
        assertEquals(relations.get(1).getIndex(), 1);
        assertEquals(relations.get(1).getItem().getClientDownloadId(), item3.getClientDownloadId());
        assertEquals(relations.get(2).getIndex(), 2);
        assertEquals(relations.get(2).getItem().getClientDownloadId(), item0.getClientDownloadId());
        assertEquals(relations.get(3).getIndex(), 3);
        assertEquals(relations.get(3).getItem().getClientDownloadId(), item1.getClientDownloadId());
    }

    @Test
    public void testRemoveItems() {
        LOG.info("testRemoveItems");

        String collectionName = "testRemoveItems_collection";

        TalkClientMediaCollection collection = null;
        TalkClientDownload item0 = new TalkClientDownload();
        TalkClientDownload item1 = new TalkClientDownload();
        TalkClientDownload item2 = new TalkClientDownload();
        TalkClientDownload item3 = new TalkClientDownload();
        try {
            collection = mDatabase.createMediaCollection(collectionName);

            assertNotNull(collection);
            assertEquals(collection.getName(), collectionName);

            // create some items and add to collection
            mDatabase.saveClientDownload(item0);
            mDatabase.saveClientDownload(item1);
            mDatabase.saveClientDownload(item2);
            mDatabase.saveClientDownload(item3);

            collection.add(item0);
            collection.add(item1);
            collection.add(item2);
            collection.add(item3);
        } catch (SQLException e) {
            LOG.error(e.getMessage());
            e.printStackTrace();
            fail();
        }

        assertEquals(collection.size(), 4);
        assertEquals(collection.getItem(0).getClientDownloadId(), item0.getClientDownloadId());
        assertEquals(collection.getItem(1).getClientDownloadId(), item1.getClientDownloadId());
        assertEquals(collection.getItem(2).getClientDownloadId(), item2.getClientDownloadId());
        assertEquals(collection.getItem(3).getClientDownloadId(), item3.getClientDownloadId());

        // check database directly
        {
            List<TalkClientMediaCollectionRelation> relations = findMediaCollectionRelationsOrderedByIndex(collection.getId());
            assertNotNull(relations);
            assertEquals(relations.size(), 4);
            assertEquals(relations.get(0).getIndex(), 0);
            assertEquals(relations.get(0).getItem().getClientDownloadId(), item0.getClientDownloadId());
            assertEquals(relations.get(1).getIndex(), 1);
            assertEquals(relations.get(1).getItem().getClientDownloadId(), item1.getClientDownloadId());
            assertEquals(relations.get(2).getIndex(), 2);
            assertEquals(relations.get(2).getItem().getClientDownloadId(), item2.getClientDownloadId());
            assertEquals(relations.get(3).getIndex(), 3);
            assertEquals(relations.get(3).getItem().getClientDownloadId(), item3.getClientDownloadId());
        }

        collection.remove(1);

        assertEquals(collection.size(), 3);
        assertEquals(collection.getItem(0).getClientDownloadId(), item0.getClientDownloadId());
        assertEquals(collection.getItem(1).getClientDownloadId(), item2.getClientDownloadId());
        assertEquals(collection.getItem(2).getClientDownloadId(), item3.getClientDownloadId());

        // check database directly
        {
            List<TalkClientMediaCollectionRelation> relations = findMediaCollectionRelationsOrderedByIndex(collection.getId());
            assertNotNull(relations);
            assertEquals(relations.size(), 3);
            assertEquals(relations.get(0).getIndex(), 0);
            assertEquals(relations.get(0).getItem().getClientDownloadId(), item0.getClientDownloadId());
            assertEquals(relations.get(1).getIndex(), 1);
            assertEquals(relations.get(1).getItem().getClientDownloadId(), item2.getClientDownloadId());
            assertEquals(relations.get(2).getIndex(), 2);
            assertEquals(relations.get(2).getItem().getClientDownloadId(), item3.getClientDownloadId());
        }

        collection.remove(item3);

        assertEquals(collection.size(), 2);
        assertEquals(collection.getItem(0).getClientDownloadId(), item0.getClientDownloadId());
        assertEquals(collection.getItem(1).getClientDownloadId(), item2.getClientDownloadId());

        // check database directly
        {
            List<TalkClientMediaCollectionRelation> relations = findMediaCollectionRelationsOrderedByIndex(collection.getId());
            assertNotNull(relations);
            assertEquals(relations.size(), 2);
            assertEquals(relations.get(0).getIndex(), 0);
            assertEquals(relations.get(0).getItem().getClientDownloadId(), item0.getClientDownloadId());
            assertEquals(relations.get(1).getIndex(), 1);
            assertEquals(relations.get(1).getItem().getClientDownloadId(), item2.getClientDownloadId());
        }

        // remove it again, nothing should change
        collection.remove(item3);

        assertEquals(collection.size(), 2);
        assertEquals(collection.getItem(0).getClientDownloadId(), item0.getClientDownloadId());
        assertEquals(collection.getItem(1).getClientDownloadId(), item2.getClientDownloadId());

        // check database directly
        {
            List<TalkClientMediaCollectionRelation> relations = findMediaCollectionRelationsOrderedByIndex(collection.getId());
            assertNotNull(relations);
            assertEquals(relations.size(), 2);
            assertEquals(relations.get(0).getIndex(), 0);
            assertEquals(relations.get(0).getItem().getClientDownloadId(), item0.getClientDownloadId());
            assertEquals(relations.get(1).getIndex(), 1);
            assertEquals(relations.get(1).getItem().getClientDownloadId(), item2.getClientDownloadId());
        }
    }

    @Test
    public void testReorderItems() {
        LOG.info("testReorderItems");

        String collectionName = "testReorderItems_collection";

        TalkClientMediaCollection collection = null;
        TalkClientDownload item0 = new TalkClientDownload();
        TalkClientDownload item1 = new TalkClientDownload();
        TalkClientDownload item2 = new TalkClientDownload();
        TalkClientDownload item3 = new TalkClientDownload();
        TalkClientDownload item4 = new TalkClientDownload();
        try {
            collection = mDatabase.createMediaCollection(collectionName);

            assertNotNull(collection);
            assertEquals(collection.getName(), collectionName);

            // create some items and add to collection
            mDatabase.saveClientDownload(item0);
            mDatabase.saveClientDownload(item1);
            mDatabase.saveClientDownload(item2);
            mDatabase.saveClientDownload(item3);
            mDatabase.saveClientDownload(item4);

            collection.add(item0);
            collection.add(item1);
            collection.add(item2);
            collection.add(item3);
            collection.add(item4);
        } catch (SQLException e) {
            LOG.error(e.getMessage());
            e.printStackTrace();
            fail();
        }

        assertEquals(collection.size(), 4);
        assertEquals(collection.getItem(0).getClientDownloadId(), item0.getClientDownloadId());
        assertEquals(collection.getItem(1).getClientDownloadId(), item1.getClientDownloadId());
        assertEquals(collection.getItem(2).getClientDownloadId(), item2.getClientDownloadId());
        assertEquals(collection.getItem(3).getClientDownloadId(), item3.getClientDownloadId());
        assertEquals(collection.getItem(4).getClientDownloadId(), item4.getClientDownloadId());

        // check database directly
        {
            List<TalkClientMediaCollectionRelation> relations = findMediaCollectionRelationsOrderedByIndex(collection.getId());
            assertNotNull(relations);
            assertEquals(relations.size(), 4);
            assertEquals(relations.get(0).getIndex(), 0);
            assertEquals(relations.get(0).getItem().getClientDownloadId(), item0.getClientDownloadId());
            assertEquals(relations.get(1).getIndex(), 1);
            assertEquals(relations.get(1).getItem().getClientDownloadId(), item1.getClientDownloadId());
            assertEquals(relations.get(2).getIndex(), 2);
            assertEquals(relations.get(2).getItem().getClientDownloadId(), item2.getClientDownloadId());
            assertEquals(relations.get(3).getIndex(), 3);
            assertEquals(relations.get(3).getItem().getClientDownloadId(), item3.getClientDownloadId());
            assertEquals(relations.get(4).getIndex(), 4);
            assertEquals(relations.get(4).getItem().getClientDownloadId(), item4.getClientDownloadId());
        }

        collection.moveItemFromToIndex(4, 3);

        assertEquals(collection.size(), 4);
        assertEquals(collection.getItem(0).getClientDownloadId(), item0.getClientDownloadId());
        assertEquals(collection.getItem(1).getClientDownloadId(), item1.getClientDownloadId());
        assertEquals(collection.getItem(2).getClientDownloadId(), item2.getClientDownloadId());
        assertEquals(collection.getItem(3).getClientDownloadId(), item4.getClientDownloadId());
        assertEquals(collection.getItem(4).getClientDownloadId(), item3.getClientDownloadId());

        // check database directly
        {
            List<TalkClientMediaCollectionRelation> relations = findMediaCollectionRelationsOrderedByIndex(collection.getId());
            assertNotNull(relations);
            assertEquals(relations.size(), 4);
            assertEquals(relations.get(0).getIndex(), 0);
            assertEquals(relations.get(0).getItem().getClientDownloadId(), item0.getClientDownloadId());
            assertEquals(relations.get(1).getIndex(), 1);
            assertEquals(relations.get(1).getItem().getClientDownloadId(), item1.getClientDownloadId());
            assertEquals(relations.get(2).getIndex(), 2);
            assertEquals(relations.get(2).getItem().getClientDownloadId(), item2.getClientDownloadId());
            assertEquals(relations.get(3).getIndex(), 3);
            assertEquals(relations.get(3).getItem().getClientDownloadId(), item4.getClientDownloadId());
            assertEquals(relations.get(4).getIndex(), 4);
            assertEquals(relations.get(4).getItem().getClientDownloadId(), item3.getClientDownloadId());
        }

        collection.moveItemFromToIndex(0, 2);

        assertEquals(collection.size(), 4);
        assertEquals(collection.getItem(0).getClientDownloadId(), item1.getClientDownloadId());
        assertEquals(collection.getItem(1).getClientDownloadId(), item2.getClientDownloadId());
        assertEquals(collection.getItem(2).getClientDownloadId(), item0.getClientDownloadId());
        assertEquals(collection.getItem(3).getClientDownloadId(), item4.getClientDownloadId());
        assertEquals(collection.getItem(4).getClientDownloadId(), item3.getClientDownloadId());

        // check database directly
        {
            List<TalkClientMediaCollectionRelation> relations = findMediaCollectionRelationsOrderedByIndex(collection.getId());
            assertNotNull(relations);
            assertEquals(relations.size(), 4);
            assertEquals(relations.get(0).getIndex(), 0);
            assertEquals(relations.get(0).getItem().getClientDownloadId(), item1.getClientDownloadId());
            assertEquals(relations.get(1).getIndex(), 1);
            assertEquals(relations.get(1).getItem().getClientDownloadId(), item2.getClientDownloadId());
            assertEquals(relations.get(2).getIndex(), 2);
            assertEquals(relations.get(2).getItem().getClientDownloadId(), item0.getClientDownloadId());
            assertEquals(relations.get(3).getIndex(), 3);
            assertEquals(relations.get(3).getItem().getClientDownloadId(), item4.getClientDownloadId());
            assertEquals(relations.get(4).getIndex(), 4);
            assertEquals(relations.get(4).getItem().getClientDownloadId(), item3.getClientDownloadId());
        }

        // move to first index
        collection.moveItemFromToIndex(4, -3);

        assertEquals(collection.size(), 4);
        assertEquals(collection.getItem(0).getClientDownloadId(), item3.getClientDownloadId());
        assertEquals(collection.getItem(1).getClientDownloadId(), item1.getClientDownloadId());
        assertEquals(collection.getItem(2).getClientDownloadId(), item2.getClientDownloadId());
        assertEquals(collection.getItem(3).getClientDownloadId(), item0.getClientDownloadId());
        assertEquals(collection.getItem(4).getClientDownloadId(), item4.getClientDownloadId());

        // check database directly
        {
            List<TalkClientMediaCollectionRelation> relations = findMediaCollectionRelationsOrderedByIndex(collection.getId());
            assertNotNull(relations);
            assertEquals(relations.size(), 4);
            assertEquals(relations.get(0).getIndex(), 0);
            assertEquals(relations.get(0).getItem().getClientDownloadId(), item3.getClientDownloadId());
            assertEquals(relations.get(1).getIndex(), 1);
            assertEquals(relations.get(1).getItem().getClientDownloadId(), item1.getClientDownloadId());
            assertEquals(relations.get(2).getIndex(), 2);
            assertEquals(relations.get(2).getItem().getClientDownloadId(), item2.getClientDownloadId());
            assertEquals(relations.get(3).getIndex(), 3);
            assertEquals(relations.get(3).getItem().getClientDownloadId(), item0.getClientDownloadId());
            assertEquals(relations.get(4).getIndex(), 4);
            assertEquals(relations.get(4).getItem().getClientDownloadId(), item4.getClientDownloadId());
        }

        // move to last index
        collection.moveItemFromToIndex(3, 5);

        assertEquals(collection.size(), 4);
        assertEquals(collection.getItem(0).getClientDownloadId(), item3.getClientDownloadId());
        assertEquals(collection.getItem(1).getClientDownloadId(), item1.getClientDownloadId());
        assertEquals(collection.getItem(2).getClientDownloadId(), item2.getClientDownloadId());
        assertEquals(collection.getItem(3).getClientDownloadId(), item4.getClientDownloadId());
        assertEquals(collection.getItem(4).getClientDownloadId(), item0.getClientDownloadId());

        // check database directly
        {
            List<TalkClientMediaCollectionRelation> relations = findMediaCollectionRelationsOrderedByIndex(collection.getId());
            assertNotNull(relations);
            assertEquals(relations.size(), 4);
            assertEquals(relations.get(0).getIndex(), 0);
            assertEquals(relations.get(0).getItem().getClientDownloadId(), item3.getClientDownloadId());
            assertEquals(relations.get(1).getIndex(), 1);
            assertEquals(relations.get(1).getItem().getClientDownloadId(), item1.getClientDownloadId());
            assertEquals(relations.get(2).getIndex(), 2);
            assertEquals(relations.get(2).getItem().getClientDownloadId(), item2.getClientDownloadId());
            assertEquals(relations.get(3).getIndex(), 3);
            assertEquals(relations.get(3).getItem().getClientDownloadId(), item4.getClientDownloadId());
            assertEquals(relations.get(4).getIndex(), 4);
            assertEquals(relations.get(4).getItem().getClientDownloadId(), item0.getClientDownloadId());
        }

        // move to same index
        collection.moveItemFromToIndex(2, 2);

        assertEquals(collection.size(), 4);
        assertEquals(collection.getItem(0).getClientDownloadId(), item3.getClientDownloadId());
        assertEquals(collection.getItem(1).getClientDownloadId(), item1.getClientDownloadId());
        assertEquals(collection.getItem(2).getClientDownloadId(), item2.getClientDownloadId());
        assertEquals(collection.getItem(3).getClientDownloadId(), item4.getClientDownloadId());
        assertEquals(collection.getItem(4).getClientDownloadId(), item0.getClientDownloadId());

        // check database directly
        {
            List<TalkClientMediaCollectionRelation> relations = findMediaCollectionRelationsOrderedByIndex(collection.getId());
            assertNotNull(relations);
            assertEquals(relations.size(), 4);
            assertEquals(relations.get(0).getIndex(), 0);
            assertEquals(relations.get(0).getItem().getClientDownloadId(), item3.getClientDownloadId());
            assertEquals(relations.get(1).getIndex(), 1);
            assertEquals(relations.get(1).getItem().getClientDownloadId(), item1.getClientDownloadId());
            assertEquals(relations.get(2).getIndex(), 2);
            assertEquals(relations.get(2).getItem().getClientDownloadId(), item2.getClientDownloadId());
            assertEquals(relations.get(3).getIndex(), 3);
            assertEquals(relations.get(3).getItem().getClientDownloadId(), item4.getClientDownloadId());
            assertEquals(relations.get(4).getIndex(), 4);
            assertEquals(relations.get(4).getItem().getClientDownloadId(), item0.getClientDownloadId());
        }
    }

    private List<TalkClientMediaCollectionRelation> findMediaCollectionRelationsOrderedByIndex(int collectionId) {

        List<TalkClientMediaCollectionRelation> relations = null;
        try {
             relations = mDatabase.getMediaCollectionRelationDao().queryBuilder()
                    .orderBy("index", true)
                    .where()
                    .eq("collection_id", collectionId)
                    .query();
        } catch(SQLException e) {
            e.printStackTrace();
        }

        return relations;
    }
}
