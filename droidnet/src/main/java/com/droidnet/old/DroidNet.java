/*
 *Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.droidnet.old;

import android.content.Context;
import android.content.IntentFilter;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Created by aa on 29/04/17.
 */

/*
* use the new [com.droidnet.new.NetworkStateHolder DroidConnectivityBroadcaster]
* DroidNet [init] is not supported anymore
 */
@Deprecated
public final class DroidNet implements NetworkChangeReceiver.NetworkChangeListener {

    private static final Object LOCK = new Object();
    private static volatile DroidNet sInstance;

    private WeakReference<Context> mContextWeakReference;
    private List<WeakReference<DroidListener>> mInternetConnectivityListenersWeakReferences;
    private NetworkChangeReceiver mNetworkChangeReceiver;
    private boolean mIsNetworkChangeRegistered = false;
    private boolean mIsInternetConnected = false;

    private TaskFinished<Boolean> mCheckConnectivityCallback;

    private static final String CONNECTIVITY_CHANGE_INTENT_ACTION = "android.net.conn.CONNECTIVITY_CHANGE";

    private DroidNet(Context context) {
        Context appContext = context.getApplicationContext();
        mContextWeakReference = new WeakReference<>(appContext);
        mInternetConnectivityListenersWeakReferences = new ArrayList<>();
    }

    /**
     * Call this function in application class to do initial setup. it returns singleton instance.
     *
     * @param context need to register for Connectivity broadcast
     * @return instance of InternetConnectivityHelper
     */
    public static DroidNet init(Context context) {
        if (context == null) {
            throw new NullPointerException("context can not be null");
        }

        if (sInstance == null) {
            synchronized (LOCK) {
                if (sInstance == null) {
                    sInstance = new DroidNet(context);
                }
            }
        }
        return sInstance;
    }

    public static DroidNet getInstance() {
        if (sInstance == null) {
            throw new IllegalStateException("call init(Context) in your application class before calling getInstance()");
        }
        return sInstance;
    }

    /**
     * Add DroidListener only if it's not added. It keeps a weak reference to the listener.
     * So user should have a strong reference to that listener otherwise that will be garbage collected
     */
    public void addInternetConnectivityListener(DroidListener droidListener) {
        if (droidListener == null) {
            return;
        }
        mInternetConnectivityListenersWeakReferences.add(new WeakReference<>(droidListener));
        if (mInternetConnectivityListenersWeakReferences.size() == 1) {
            registerNetworkChangeReceiver();
            return;
        }
        publishInternetAvailabilityStatus(mIsInternetConnected);
    }

    /**
     * remove the weak reference to the listener
     */
    public void removeInternetConnectivityChangeListener(DroidListener droidListener) {
        if (droidListener == null) {
            return;
        }

        if (mInternetConnectivityListenersWeakReferences == null) {
            return;
        }

        Iterator<WeakReference<DroidListener>> iterator = mInternetConnectivityListenersWeakReferences.iterator();
        while (iterator.hasNext()) {

            //if weak reference is null then remove it from iterator
            WeakReference<DroidListener> reference = iterator.next();
            if (reference == null) {
                iterator.remove();
                continue;
            }

            //if listener referenced by this weak reference is garbage collected then remove it from iterator
            DroidListener listener = reference.get();
            if (listener == null) {
                reference.clear();
                iterator.remove();
                continue;
            }

            //if listener to be removed is found then remove it
            if (listener == droidListener) {
                reference.clear();
                iterator.remove();
                break;
            }
        }

        //if all listeners are removed then unregister NetworkChangeReceiver
        if (mInternetConnectivityListenersWeakReferences.size() == 0) {
            unregisterNetworkChangeReceiver();
        }
    }

    public void removeAllInternetConnectivityChangeListeners() {
        if (mInternetConnectivityListenersWeakReferences == null) {
            return;
        }

        Iterator<WeakReference<DroidListener>> iterator = mInternetConnectivityListenersWeakReferences.iterator();
        while (iterator.hasNext()) {
            WeakReference<DroidListener> reference = iterator.next();
            if (reference != null) {
                reference.clear();
            }
            iterator.remove();
        }
        unregisterNetworkChangeReceiver();
    }

    /**
     * registers a NetworkChangeReceiver if not registered already
     */
    private void registerNetworkChangeReceiver() {
        Context context = mContextWeakReference.get();
        if (context != null && !mIsNetworkChangeRegistered) {
            mNetworkChangeReceiver = new NetworkChangeReceiver();
            mNetworkChangeReceiver.setNetworkChangeListener(this);
            context.registerReceiver(mNetworkChangeReceiver, new IntentFilter(CONNECTIVITY_CHANGE_INTENT_ACTION));
            mIsNetworkChangeRegistered = true;
        }
    }

    /**
     * unregisters the already registered NetworkChangeReceiver
     */
    private void unregisterNetworkChangeReceiver() {
        Context context = mContextWeakReference.get();
        if (context != null && mNetworkChangeReceiver != null && mIsNetworkChangeRegistered) {
            try {
                context.unregisterReceiver(mNetworkChangeReceiver);
            } catch (IllegalArgumentException exception) {
                //consume this exception
            }
            mNetworkChangeReceiver.removeNetworkChangeListener();
        }
        mNetworkChangeReceiver = null;
        mIsNetworkChangeRegistered = false;
        mCheckConnectivityCallback = null;
    }

    @Override
    public void onNetworkChange(boolean isNetworkAvailable) {
        if (isNetworkAvailable) {
            mCheckConnectivityCallback = new TaskFinished<Boolean>() {
                @Override
                public void onTaskFinished(Boolean isInternetAvailable) {
                    mCheckConnectivityCallback = null;
                    publishInternetAvailabilityStatus(isInternetAvailable);
                }
            };
            new CheckInternetTask(mCheckConnectivityCallback).execute();
        } else {
            publishInternetAvailabilityStatus(false);
        }
    }

    private void publishInternetAvailabilityStatus(boolean isInternetAvailable) {
        mIsInternetConnected = isInternetAvailable;
        if (mInternetConnectivityListenersWeakReferences == null) {
            return;
        }

        Iterator<WeakReference<DroidListener>> iterator = mInternetConnectivityListenersWeakReferences.iterator();
        while (iterator.hasNext()) {
            WeakReference<DroidListener> reference = iterator.next();

            if (reference == null) {
                iterator.remove();
                continue;
            }

            DroidListener listener = reference.get();
            if (listener == null) {
                iterator.remove();
                continue;
            }

            listener.onInternetConnectivityChanged(isInternetAvailable);
        }

        if (mInternetConnectivityListenersWeakReferences.size() == 0) {
            unregisterNetworkChangeReceiver();
        }
    }
}
