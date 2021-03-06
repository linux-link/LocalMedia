/*
 * Copyright 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.car.media.common.source;

import static com.android.car.arch.common.LiveDataFunctions.dataOf;

import android.app.Application;
import android.car.Car;
import android.car.CarNotConnectedException;
import android.car.media.CarMediaManager;
import android.content.ComponentName;
import android.os.Handler;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.android.car.media.common.source.MediaBrowserConnector.BrowsingState;

import java.util.Objects;

/**
 * 包含显示回放和浏览用户界面所需的可观察数据。
 * MediaSourceViewModel是一个绑定到应用程序的单一组件，用于提供单一的真相来源。
 */
public class MediaSourceViewModel extends AndroidViewModel {
    private static final String TAG = "MediaSourceViewModel";

    private static MediaSourceViewModel[] sInstances = new MediaSourceViewModel[2];

    private final Car mCar;
    private CarMediaManager mCarMediaManager;

    // 主要媒体来源.
    private final MutableLiveData<MediaSource> mPrimaryMediaSource = dataOf(null);

    // 主要媒体源及其连接状态的浏览器.
    private final MutableLiveData<BrowsingState> mBrowsingState = dataOf(null);

    private final Handler mHandler;
    private final InputFactory mInputFactory;
    private final CarMediaManager.MediaSourceChangedListener mMediaSourceListener;

    /**
     * Factory for creating dependencies. Can be swapped out for testing.
     */
    @VisibleForTesting
    interface InputFactory {
        MediaBrowserConnector createMediaBrowserConnector(@NonNull Application application,
                @NonNull MediaBrowserConnector.Callback connectedBrowserCallback);

        Car getCarApi();

        CarMediaManager getCarMediaManager(Car carApi) throws CarNotConnectedException;

        MediaSource getMediaSource(ComponentName componentName);
    }

    /** Returns the MediaSourceViewModel singleton tied to the application. */
    public static MediaSourceViewModel get(@NonNull Application application, int mode) {
        if (sInstances[mode] == null) {
            sInstances[mode] = new MediaSourceViewModel(application, mode);
        }
        return sInstances[mode];
    }

    /**
     * 创建MediaSourceViewModel的新实例
     */
    private MediaSourceViewModel(@NonNull Application application, int mode) {
        this(application, mode, new InputFactory() {
            @Override
            public MediaBrowserConnector createMediaBrowserConnector(
                    @NonNull Application application,
                    @NonNull MediaBrowserConnector.Callback connectedBrowserCallback) {
                return new MediaBrowserConnector(application, connectedBrowserCallback);
            }

            @Override
            public Car getCarApi() {
                return Car.createCar(application);
            }

            @Override
            public CarMediaManager getCarMediaManager(Car carApi) throws CarNotConnectedException {
                return (CarMediaManager) carApi.getCarManager(Car.CAR_MEDIA_SERVICE);
            }

            @Override
            public MediaSource getMediaSource(ComponentName componentName) {
                return componentName == null ? null : MediaSource.create(application,
                        componentName);
            }
        });
    }

    private final MediaBrowserConnector mBrowserConnector;
    private final MediaBrowserConnector.Callback mBrowserCallback = new MediaBrowserConnector.Callback() {
        @Override
        public void onBrowserConnectionChanged(@NonNull BrowsingState state) {
            mBrowsingState.setValue(state);
        }
    };

    @VisibleForTesting
    MediaSourceViewModel(@NonNull Application application, int mode,
            @NonNull InputFactory inputFactory) {
        super(application);

        mInputFactory = inputFactory;
        mCar = inputFactory.getCarApi();

        mBrowserConnector = inputFactory.createMediaBrowserConnector(application, mBrowserCallback);

        mHandler = new Handler(application.getMainLooper());
        mMediaSourceListener = new CarMediaManager.MediaSourceChangedListener() {
            @Override
            public void onMediaSourceChanged(ComponentName componentName) {
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        updateModelState(mInputFactory.getMediaSource(componentName));
                    }
                });
            }
        };

        try {
            mCarMediaManager = mInputFactory.getCarMediaManager(mCar);
            mCarMediaManager.addMediaSourceListener(mMediaSourceListener, mode);
            updateModelState(mInputFactory.getMediaSource(mCarMediaManager.getMediaSource(mode)));
        } catch (CarNotConnectedException e) {
            Log.e(TAG, "Car not connected", e);
        }
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        mCar.disconnect();
    }

    @VisibleForTesting
    MediaBrowserConnector.Callback getBrowserCallback() {
        return mBrowserCallback;
    }

    /**
     * Returns a LiveData that emits the MediaSource that is to be browsed or displayed.
     */
    public LiveData<MediaSource> getPrimaryMediaSource() {
        return mPrimaryMediaSource;
    }

    /**
     * Updates the primary media source.
     */
    public void setPrimaryMediaSource(@NonNull MediaSource mediaSource, int mode) {
        mCarMediaManager.setMediaSource(mediaSource.getBrowseServiceComponentName(), mode);
    }

    /**
     * Returns a LiveData that emits a {@link BrowsingState}, or {@code null} if there is no media
     * source.
     */
    public LiveData<BrowsingState> getBrowsingState() {
        return mBrowsingState;
    }

    private void updateModelState(MediaSource newMediaSource) {
        MediaSource oldMediaSource = mPrimaryMediaSource.getValue();
        if (Objects.equals(oldMediaSource, newMediaSource)) {
            return;
        }
        // 广播新的源
        mPrimaryMediaSource.setValue(newMediaSource);

        // 重新计算相关值
        if (newMediaSource != null) {
            mBrowserConnector.connectTo(newMediaSource);
        }
    }
}
