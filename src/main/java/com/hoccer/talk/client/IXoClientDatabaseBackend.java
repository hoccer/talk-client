package com.hoccer.talk.client;

import com.j256.ormlite.dao.Dao;
import com.j256.ormlite.support.ConnectionSource;

import java.sql.SQLException;

public interface IXoClientDatabaseBackend {

    public ConnectionSource getConnectionSource();
    public <D extends Dao<T, ?>, T> D getDao(Class<T> clazz) throws SQLException;

}
