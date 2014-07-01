import com.hoccer.talk.client.IXoClientDatabaseBackend;
import com.hoccer.talk.client.XoClientDatabase;
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

import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertNotNull;
import static junit.framework.TestCase.fail;

public class MediaCollectionTest {

    private static final Logger LOG = Logger.getLogger(MediaCollectionTest.class);

    private XoClientDatabase mDatabase;

    @Before
    public void testSetup() throws Exception {
        final JdbcConnectionSource connectionSource = new JdbcConnectionSource("jdbc:h2:mem:account");
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
        connectionSource.close();
    }

    @After
    public void testCleanup() throws SQLException {

    }

    @Test
    public void test1() {
        LOG.info("test1");

        TalkClientMediaCollection collection = null;
        try {
            collection = mDatabase.createMediaCollection("test1_collection");
        } catch(SQLException e) {
            LOG.error(e.getMessage());
            e.printStackTrace();
            fail();
        }

        assertNotNull(collection);
    }
}
