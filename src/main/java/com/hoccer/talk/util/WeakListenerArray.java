package com.hoccer.talk.util;

import java.lang.ref.WeakReference;
import java.util.*;


/**
 * Encapsulates a list of weak referenced listeners.
 */
public class WeakListenerArray<T> {

    public interface Caller<T> {
        void call(T listener);
    }

    private ArrayList<WeakReference<T>> mItems = new ArrayList<WeakReference<T>>();

    // Adds the given listener to array
    public void addListener(T listener) {
        mItems.add(new WeakReference<T>(listener));
    }

    // Removes the given listener instance from array
    public void removeListener(T listener) {
        for (Iterator<WeakReference<T>> iterator = mItems.iterator();
             iterator.hasNext(); )
        {
            WeakReference<T> weakRef = iterator.next();
            if(weakRef.get() == listener) {
                iterator.remove();
            }
        }
    }

    // Executes the given caller with every listener sequentially
    public void callForEach(Caller caller)
    {
        for (Iterator<WeakReference<T>> iterator = mItems.iterator();
             iterator.hasNext(); )
        {
            WeakReference<T> weakRef = iterator.next();
            T listener = weakRef.get();
            if (listener != null)
            {
                caller.call(listener);
            } else {
                iterator.remove();
            }
        }
    }
}
