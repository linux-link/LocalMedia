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

import static com.android.car.apps.common.util.CarAppsDebugUtils.idHash;

import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.support.v4.media.MediaBrowserCompat;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.core.util.Preconditions;

import com.android.car.media.common.MediaConstants;

import java.util.Objects;

/**
 * 将单个 {@link MediaSource} 连接到其 {@link MediaBrowserCompat} 的帮助器类。
 * 连接到新浏览器会自动断开之前的浏览器。 连接状态的变化通过 {@link Callback} 发送。
 */

public class MediaBrowserConnector {

    private static final String TAG = "MediaBrowserConnector";

    /**
     * 表示给 {@link #connectTo} 的媒体浏览器服务的连接状态。
     */
    public enum ConnectionStatus {
        /**
         * 正在发起对浏览器的连接请求。 在调用 {@link MediaBrowserCompat#connect} 之前从 {@link #connectTo} 发送。
         */
        CONNECTING,
        /**
         * 与浏览器的连接已经建立，可以使用了。
         * 如果 {@link MediaBrowserCompat#isConnected} 也返回 true，则从 {@link MediaBrowserCompat.ConnectionCallback#onConnected} 发送。
         */
        CONNECTED,
        /**
         * 与浏览器的连接被拒绝。
         * 如果 {@link MediaBrowserCompat#isConnected} 返回 false，则从 {@link MediaBrowserCompat.ConnectionCallback#onConnectionFailed} 或 {@link MediaBrowserCompat.ConnectionCallback#onConnected} 发送。
         */
        REJECTED,
        /**
         * 浏览器崩溃了，不应再对其进行调用。
         * 当 {@link MediaBrowserCompat#connect} 抛出 {@link IllegalStateException} 时，从 {@link MediaBrowserCompat.ConnectionCallback#onConnectionSuspended} 和 {@link #connectTo} 调用。
         */
        SUSPENDED,
        /**
         * 与浏览器的连接正在关闭。
         * 当连接到新浏览器并且旧浏览器已连接时，这会在旧浏览器上调用 {@link MediaBrowserCompat#disconnect} 之前从 {@link #connectTo} 发送。
         */
        DISCONNECTING
    }

    /**
     * 用 {@link MediaBrowserCompat} 和 {@link ConnectionStatus} 封装一个 {@link ComponentName}。
     */
    public static class BrowsingState {
        @NonNull
        public final MediaSource mMediaSource;
        @NonNull
        public final MediaBrowserCompat mBrowser;
        @NonNull
        public final ConnectionStatus mConnectionStatus;

        @VisibleForTesting
        public BrowsingState(@NonNull MediaSource mediaSource, @NonNull MediaBrowserCompat browser,
                             @NonNull ConnectionStatus status) {
            mMediaSource = Preconditions.checkNotNull(mediaSource, "source can't be null");
            mBrowser = Preconditions.checkNotNull(browser, "browser can't be null");
            mConnectionStatus = Preconditions.checkNotNull(status, "status can't be null");
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            BrowsingState that = (BrowsingState) o;
            return mMediaSource.equals(that.mMediaSource)
                    && mBrowser.equals(that.mBrowser)
                    && mConnectionStatus == that.mConnectionStatus;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mMediaSource, mBrowser, mConnectionStatus);
        }
    }

    /**
     * 接收当前 {@link MediaBrowserCompat} 及其连接状态的回调。
     */
    public interface Callback {
        /**
         * 通知监听器连接状态更改。
         */
        void onBrowserConnectionChanged(@NonNull BrowsingState state);
    }

    private final Context mContext;
    private final Callback mCallback;
    private final int mMaxBitmapSizePx;

    // 服务端媒体源。提供了方便的方法来访问媒体源的原始数据，例如应用程序名称和图标。
    @Nullable
    private MediaSource mMediaSource;
    @Nullable
    private MediaBrowserCompat mBrowser;

    /**
     * Create a new MediaBrowserConnector.
     *
     * @param context The Context with which to build MediaBrowsers.
     */
    public MediaBrowserConnector(@NonNull Context context, @NonNull Callback callback) {
        mContext = context;
        mCallback = callback;
        mMaxBitmapSizePx = mContext.getResources().getInteger(
                com.android.car.media.common.R.integer.media_items_bitmap_max_size_px);
    }

    private String getSourcePackage() {
        if (mMediaSource == null) return null;
        return mMediaSource.getBrowseServiceComponentName().getPackageName();
    }

    /**
     * 计数器，因此可以忽略来自过时连接的回调。
     */
    private int mBrowserConnectionCallbackCounter = 0;

    private class BrowserConnectionCallback extends MediaBrowserCompat.ConnectionCallback {

        private final int mSequenceNumber = ++mBrowserConnectionCallbackCounter;
        private final String mCallbackPackage = getSourcePackage();

        private BrowserConnectionCallback() {
            if (Log.isLoggable(TAG, Log.INFO)) {
                Log.i(TAG, "New Callback: " + idHash(this));
            }
        }

        private boolean isValidCall(String method) {
            if (mSequenceNumber != mBrowserConnectionCallbackCounter) {
                Log.e(TAG, "Callback: " + idHash(this) + " ignoring " + method + " for "
                        + mCallbackPackage + " seq: "
                        + mSequenceNumber + " current: " + mBrowserConnectionCallbackCounter
                        + " package: " + getSourcePackage());
                return false;
            } else if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, method + " " + getSourcePackage() + " mBrowser: " + idHash(mBrowser));
            }
            return true;
        }

        @Override
        public void onConnected() {
            if (isValidCall("onConnected")) {
                if (mBrowser != null && mBrowser.isConnected()) {
                    sendNewState(ConnectionStatus.CONNECTED);
                } else {
                    sendNewState(ConnectionStatus.REJECTED);
                }
            }
        }

        @Override
        public void onConnectionFailed() {
            if (isValidCall("onConnectionFailed")) {
                sendNewState(ConnectionStatus.REJECTED);
            }
        }

        @Override
        public void onConnectionSuspended() {
            if (isValidCall("onConnectionSuspended")) {
                sendNewState(ConnectionStatus.SUSPENDED);
            }
        }
    }

    private void sendNewState(ConnectionStatus cnx) {
        if (mMediaSource == null) {
            Log.e(TAG, "sendNewState mMediaSource is null!");
            return;
        }
        if (mBrowser == null) {
            Log.e(TAG, "sendNewState mBrowser is null!");
            return;
        }
        mCallback.onBrowserConnectionChanged(new BrowsingState(mMediaSource, mBrowser, cnx));
    }

    /**
     * 如果给定的 {@link MediaSource} 不为空，则创建并连接一个新的 {@link MediaBrowserCompat}。
     * 如果需要，之前的浏览器会断开连接。
     *
     * @param mediaSource 要连接的媒体源。
     * @see MediaBrowserCompat#MediaBrowserCompat(Context, ComponentName,
     * MediaBrowserCompat.ConnectionCallback, Bundle)
     */
    public void connectTo(@Nullable MediaSource mediaSource) {
        if (mBrowser != null && mBrowser.isConnected()) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Disconnecting: " + getSourcePackage()
                        + " mBrowser: " + idHash(mBrowser));
            }
            sendNewState(ConnectionStatus.DISCONNECTING);
            mBrowser.disconnect();
        }

        mMediaSource = mediaSource;
        if (mMediaSource != null) {
            mBrowser = createMediaBrowser(mMediaSource, new BrowserConnectionCallback());
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "Connecting to: " + getSourcePackage()
                        + " mBrowser: " + idHash(mBrowser));
            }
            try {
                sendNewState(ConnectionStatus.CONNECTING);
                mBrowser.connect();
            } catch (IllegalStateException ex) {
                // 这个comment还有效吗？
                // 忽略：MediaBrowse 可能处于中间状态（未连接，但也未断开连接。）
                // 在这种情况下，再次尝试连接可以抛出这个异常，但是不尝试是无法知道的。
                Log.e(TAG, "Connection exception: " + ex);
                sendNewState(ConnectionStatus.SUSPENDED);
            }
        } else {
            mBrowser = null;
        }
    }

    // Override for testing.
    @NonNull
    protected MediaBrowserCompat createMediaBrowser(@NonNull MediaSource mediaSource,
                                                    @NonNull MediaBrowserCompat.ConnectionCallback callback) {
        Bundle rootHints = new Bundle();
        rootHints.putInt(MediaConstants.EXTRA_MEDIA_ART_SIZE_HINT_PIXELS, mMaxBitmapSizePx);
        ComponentName browseService = mediaSource.getBrowseServiceComponentName();
        return new MediaBrowserCompat(mContext, browseService, callback, rootHints);
    }
}
