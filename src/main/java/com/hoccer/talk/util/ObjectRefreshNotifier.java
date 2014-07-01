package com.hoccer.talk.util;

import com.hoccer.talk.client.IXoDatabaseRefreshListener;

import java.lang.ref.WeakReference;
import java.util.*;


/**
 * Encapsulates a list of weak referenced refreshable instances of one type.
 */
public class ObjectRefreshNotifier {

    class WeakRefreshListener extends WeakReference<IXoDatabaseRefreshListener> {

        public WeakRefreshListener(IXoDatabaseRefreshListener referent) {
            super(referent);
        }
    }

    Map<Integer, Collection<WeakRefreshListener>> mMap =
            new HashMap<Integer, Collection<WeakRefreshListener>>();

    // Adds the given listener to array
    public void add(IXoDatabaseRefreshListener listener)
    {
        Collection<WeakRefreshListener> collection = mMap.get(listener.getId());
        if(collection == null) {
            collection = new ArrayList<WeakRefreshListener>();
            mMap.put(listener.getId(), collection);
        }
        collection.add(new WeakRefreshListener(listener));
    }

    // Removes the given listener instance from array
    public void notifyRefresh(Integer id) {
        Collection<WeakRefreshListener> collection = mMap.get(id);
        if(collection != null) {
            for (Iterator<WeakRefreshListener> iterator = collection.iterator();
                 iterator.hasNext(); ) {
                WeakRefreshListener weakListener = iterator.next();
                if (weakListener.get() != null) {
                    weakListener.get().needsRefresh();
                } else {
                    iterator.remove();
                }
            }
        }
    }
}
