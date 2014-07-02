import com.hoccer.talk.client.IXoClientDatabaseBackend;
import com.hoccer.talk.client.XoClientDatabase;
import com.hoccer.talk.client.model.TalkClientDownload;
import com.hoccer.talk.client.model.TalkClientMediaCollection;
import com.hoccer.talk.client.model.TalkClientMediaCollectionRelation;
import com.j256.ormlite.dao.Dao;
import com.j256.ormlite.dao.DaoManager;
import com.j256.ormlite.jdbc.JdbcConnectionSource;
import com.j256.ormlite.support.ConnectionSource;
import junit.framework.Assert;
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
        final JdbcConnectionSource connectionSource = new JdbcConnectionSource("jdbc:h2:mem:account");
        mConnectionSource = connectionSource;
        mDatabase = new XoClientDatabase(new IXoClientDatabaseBackend() {

            @Override
            public ConnectionSource getConnectionSource() {
                return connectionSource;
            }

            @Override
            public <D extends Dao<T, ?>, T> D getDao(Class<T> clazz) throws SQLException {
                return DaoManager.createDao(connectionSource, clazz);
            }
        });

        mDatabase.createTables(connectionSource);
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

        assertNotNull(collection);
        assertEquals(collection.getName(), collectionName);
        assertEquals(collection.size(), 3);
        assertEquals(collection.getItem(0).getClientDownloadId(), item0.getClientDownloadId());
        assertEquals(collection.getItem(1).getClientDownloadId(), item1.getClientDownloadId());
        assertEquals(collection.getItem(2).getClientDownloadId(), item2.getClientDownloadId());

        // check database directly
        List<TalkClientDownload> items = findMediaCollectionItemsOrderedByIndex(collection.getId());
        assertEquals(items.size(), 3);
        assertEquals(items.get(0).getClientDownloadId(), item0.getClientDownloadId());
        assertEquals(items.get(1).getClientDownloadId(), item1.getClientDownloadId());
        assertEquals(items.get(2).getClientDownloadId(), item2.getClientDownloadId());
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

            // create some items and add to collection
            mDatabase.saveClientDownload(item0);
            mDatabase.saveClientDownload(item1);
            mDatabase.saveClientDownload(item2);
            mDatabase.saveClientDownload(item3);

            // should insert at [0]
            collection.add(5, item0);
            collection.add(1, item1);
            collection.add(0, item2);
            collection.add(1, item3);
        } catch (SQLException e) {
            LOG.error(e.getMessage());
            e.printStackTrace();
            fail();
        }

        assertNotNull(collection);
        assertEquals(collection.getName(), collectionName);
        assertEquals(collection.size(), 4);
        assertEquals(collection.getItem(0).getClientDownloadId(), item2.getClientDownloadId());
        assertEquals(collection.getItem(1).getClientDownloadId(), item3.getClientDownloadId());
        assertEquals(collection.getItem(2).getClientDownloadId(), item0.getClientDownloadId());
        assertEquals(collection.getItem(3).getClientDownloadId(), item1.getClientDownloadId());

        // check database directly
        List<TalkClientDownload> items = findMediaCollectionItemsOrderedByIndex(collection.getId());
        assertEquals(items.size(), 4);
        assertEquals(items.get(0).getClientDownloadId(), item2.getClientDownloadId());
        assertEquals(items.get(1).getClientDownloadId(), item3.getClientDownloadId());
        assertEquals(items.get(2).getClientDownloadId(), item0.getClientDownloadId());
        assertEquals(items.get(3).getClientDownloadId(), item1.getClientDownloadId());
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

        assertNotNull(collection);
        assertEquals(collection.getName(), collectionName);
        assertEquals(collection.size(), 4);
        assertEquals(collection.getItem(0).getClientDownloadId(), item0.getClientDownloadId());
        assertEquals(collection.getItem(1).getClientDownloadId(), item1.getClientDownloadId());
        assertEquals(collection.getItem(2).getClientDownloadId(), item2.getClientDownloadId());
        assertEquals(collection.getItem(3).getClientDownloadId(), item3.getClientDownloadId());

        // check database directly
        {
            List<TalkClientDownload> items = findMediaCollectionItemsOrderedByIndex(collection.getId());
            assertEquals(items.size(), 4);
            assertEquals(items.get(0).getClientDownloadId(), item0.getClientDownloadId());
            assertEquals(items.get(1).getClientDownloadId(), item1.getClientDownloadId());
            assertEquals(items.get(2).getClientDownloadId(), item2.getClientDownloadId());
            assertEquals(items.get(3).getClientDownloadId(), item3.getClientDownloadId());
        }

        collection.remove(1);

        assertEquals(collection.size(), 3);
        assertEquals(collection.getItem(0).getClientDownloadId(), item0.getClientDownloadId());
        assertEquals(collection.getItem(1).getClientDownloadId(), item2.getClientDownloadId());
        assertEquals(collection.getItem(2).getClientDownloadId(), item3.getClientDownloadId());

        // check database directly
        {
            List<TalkClientDownload> items = findMediaCollectionItemsOrderedByIndex(collection.getId());
            assertEquals(items.size(), 3);
            assertEquals(items.get(0).getClientDownloadId(), item0.getClientDownloadId());
            assertEquals(items.get(1).getClientDownloadId(), item2.getClientDownloadId());
            assertEquals(items.get(2).getClientDownloadId(), item3.getClientDownloadId());
        }

        collection.remove(item3);

        assertEquals(collection.size(), 2);
        assertEquals(collection.getItem(0).getClientDownloadId(), item0.getClientDownloadId());
        assertEquals(collection.getItem(1).getClientDownloadId(), item2.getClientDownloadId());

        // check database directly
        {
            List<TalkClientDownload> items = findMediaCollectionItemsOrderedByIndex(collection.getId());
            assertEquals(items.size(), 2);
            assertEquals(items.get(0).getClientDownloadId(), item0.getClientDownloadId());
            assertEquals(items.get(1).getClientDownloadId(), item2.getClientDownloadId());
        }

        // remove it again, nothing should change
        collection.remove(item3);

        assertEquals(collection.size(), 2);
        assertEquals(collection.getItem(0).getClientDownloadId(), item0.getClientDownloadId());
        assertEquals(collection.getItem(1).getClientDownloadId(), item2.getClientDownloadId());

        // check database directly
        {
            List<TalkClientDownload> items = findMediaCollectionItemsOrderedByIndex(collection.getId());
            assertEquals(items.size(), 2);
            assertEquals(items.get(0).getClientDownloadId(), item0.getClientDownloadId());
            assertEquals(items.get(1).getClientDownloadId(), item2.getClientDownloadId());
        }
    }

    private List<TalkClientDownload> findMediaCollectionItemsOrderedByIndex(int collectionId) {
        List<TalkClientDownload> items = new ArrayList<TalkClientDownload>();

        try {
            List<TalkClientMediaCollectionRelation> relations = mDatabase.getMediaCollectionRelationDao().queryBuilder()
                    .orderBy("index", true)
                    .where()
                    .eq("collection_id", collectionId)
                    .query();

            for(TalkClientMediaCollectionRelation relation : relations) {
                items.add(relation.getItem());
            }
        } catch(SQLException e) {
            e.printStackTrace();
        }

        return items;
    }
}
