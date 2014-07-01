import com.hoccer.talk.client.IXoClientDatabaseBackend;
import com.hoccer.talk.client.XoClientDatabase;
import com.hoccer.talk.client.model.TalkClientDownload;
import com.hoccer.talk.client.model.TalkClientMediaCollection;
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
    public void testCreateEmptyCollection() {
        LOG.info("testCreateEmptyCollection");

        String collectionName = "testCreateEmptyCollection_collection";
        TalkClientMediaCollection collection = null;
        try {
            collection = mDatabase.createMediaCollection(collectionName);
        } catch(SQLException e) {
            LOG.error(e.getMessage());
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
            LOG.error(e.getMessage());
            e.printStackTrace();
            fail();
        }

        assertNotNull(collectionCopy);
        assertEquals(collectionCopy.getName(), collectionName);
        assertEquals(collectionCopy.size(), 0);
    }

    @Test
    public void testAddItems() {
        LOG.info("testAddItems");

        String collectionName = "testAddItems_ollection";
        TalkClientMediaCollection collection = null;
        TalkClientDownload item0 = new TalkClientDownload();
        TalkClientDownload item1 = new TalkClientDownload();
        TalkClientDownload item2 = new TalkClientDownload();
        try {
            mDatabase.saveClientDownload(item0);
            mDatabase.saveClientDownload(item1);
            mDatabase.saveClientDownload(item2);

            collection = mDatabase.createMediaCollection(collectionName);
            collection.add(item0);
            collection.add(item1);
            collection.add(item2);
        } catch(SQLException e) {
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

        TalkClientMediaCollection collectionCopy = null;
        try {
            collectionCopy = mDatabase.findMediaCollectionById(collection.getId());
        } catch(SQLException e) {
            LOG.error(e.getMessage());
            e.printStackTrace();
            fail();
        }

        assertNotNull(collectionCopy);
        assertEquals(collectionCopy.getName(), collectionName);
        assertEquals(collectionCopy.size(), 3);
        assertEquals(collectionCopy.getItem(0).getClientDownloadId(), item0.getClientDownloadId());
        assertEquals(collectionCopy.getItem(1).getClientDownloadId(), item1.getClientDownloadId());
        assertEquals(collectionCopy.getItem(2).getClientDownloadId(), item2.getClientDownloadId());
    }

    @Test
    public void testDynamicAddItems() {
        LOG.info("testDynamicAddItems");

        String collectionName = "testDynamicAddItems_collection";
        TalkClientMediaCollection collection = null;
        TalkClientDownload item0 = new TalkClientDownload();
        TalkClientDownload item1 = new TalkClientDownload();
        try {
            mDatabase.saveClientDownload(item0);
            mDatabase.saveClientDownload(item1);

            collection = mDatabase.createMediaCollection(collectionName);
            collection.add(item0);
        } catch(SQLException e) {
            LOG.error(e.getMessage());
            e.printStackTrace();
            fail();
        }

        assertNotNull(collection);
        assertEquals(collection.getName(), collectionName);
        assertEquals(collection.size(), 1);
        assertEquals(collection.getItem(0).getClientDownloadId(), item0.getClientDownloadId());

        TalkClientMediaCollection collectionCopy = null;
        try {
            collectionCopy = mDatabase.findMediaCollectionById(collection.getId());
        } catch(SQLException e) {
            LOG.error(e.getMessage());
            e.printStackTrace();
            fail();
        }

        assertNotNull(collectionCopy);
        assertEquals(collectionCopy.getName(), collectionName);
        assertEquals(collectionCopy.size(), 1);
        assertEquals(collectionCopy.getItem(0).getClientDownloadId(), item0.getClientDownloadId());

        // add additional item now
        collection.add(item1);
        assertEquals(collection.size(), 2);
        assertEquals(collectionCopy.size(), 2);
    }
}
