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

package com.android.car.media.common.playback;

import static androidx.lifecycle.Transformations.switchMap;

import static com.android.car.arch.common.LiveDataFunctions.dataOf;
import static com.android.car.media.common.playback.PlaybackStateAnnotations.Actions;

import android.app.Application;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadata;
import android.os.Bundle;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.RatingCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;

import com.android.car.media.common.CustomPlaybackAction;
import com.android.car.media.common.MediaConstants;
import com.android.car.media.common.MediaItemMetadata;
import com.android.car.media.common.R;
import com.android.car.media.common.source.MediaBrowserConnector;
import com.android.car.media.common.source.MediaBrowserConnector.ConnectionStatus;
import com.android.car.media.common.source.MediaSourceColors;
import com.android.car.media.common.source.MediaSourceViewModel;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 用于媒体播放的 ViewModel。
 * <p>
 * 观察对提供的 MediaController 的更改以公开播放状态和元数据 observables。
 * <p>
 * PlaybackViewModel 是与应用程序绑定的“单例”，以提供单一的事实来源。
 */
public class PlaybackViewModel extends AndroidViewModel {
    private static final String TAG = "PlaybackViewModel";

    private static final String ACTION_SET_RATING =
            "com.android.car.media.common.ACTION_SET_RATING";
    private static final String EXTRA_SET_HEART = "com.android.car.media.common.EXTRA_SET_HEART";

    private static PlaybackViewModel[] sInstances = new PlaybackViewModel[2];

    /**
     * 返回给定模式下绑定到应用程序的PlaybackViewModel的单例。
     */
    public static PlaybackViewModel get(@NonNull Application application, int mode) {
        if (sInstances[mode] == null) {
            sInstances[mode] = new PlaybackViewModel(application, mode);
        }
        return sInstances[mode];
    }

    /**
     * 可能的action
     */
    @IntDef({ACTION_PLAY, ACTION_STOP, ACTION_PAUSE, ACTION_DISABLED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Action {
    }

    /**
     * main action已禁用。无法播放媒体源
     */
    public static final int ACTION_DISABLED = 0;
    /**
     * Start playing
     */
    public static final int ACTION_PLAY = 1;
    /**
     * Stop playing
     */
    public static final int ACTION_STOP = 2;
    /**
     * Pause playing
     */
    public static final int ACTION_PAUSE = 3;

    /**
     * Factory for creating dependencies. Can be swapped out for testing.
     */
    @VisibleForTesting
    interface InputFactory {
        MediaControllerCompat getControllerForBrowser(@NonNull MediaBrowserCompat browser);
    }

    /**
     * 需要是 MediaMetadata 因为 compat 类没有实现 equals...
     */
    private static final MediaMetadata EMPTY_MEDIA_METADATA = new MediaMetadata.Builder().build();

    private final MediaControllerCallback mMediaControllerCallback = new MediaControllerCallback();
    private final Observer<MediaBrowserConnector.BrowsingState> mMediaBrowsingObserver =
            mMediaControllerCallback::onMediaBrowsingStateChanged;

    private final MediaSourceColors.Factory mColorsFactory;
    private final MutableLiveData<MediaSourceColors> mColors = dataOf(null);

    private final MutableLiveData<MediaItemMetadata> mMetadata = dataOf(null);

    // 过滤掉没有描述或标题的队列项目，并将它们转换为 MediaItemMetadata
    private final MutableLiveData<List<MediaItemMetadata>> mSanitizedQueue = dataOf(null);

    private final MutableLiveData<Boolean> mHasQueue = dataOf(null);

    private final MutableLiveData<CharSequence> mQueueTitle = dataOf(null);

    private final MutableLiveData<PlaybackController> mPlaybackControls = dataOf(null);

    private final MutableLiveData<PlaybackStateWrapper> mPlaybackStateWrapper = dataOf(null);

    private final LiveData<PlaybackProgress> mProgress =
            switchMap(mPlaybackStateWrapper,
                    state -> state == null ? dataOf(new PlaybackProgress(0L, 0L))
                            : new ProgressLiveData(state.mState, state.getMaxProgress()));

    private final InputFactory mInputFactory;

    private PlaybackViewModel(Application application, int mode) {
        this(application,
                MediaSourceViewModel.get(application, mode).getBrowsingState(),
                new InputFactory() {
                    @Override
                    public MediaControllerCompat getControllerForBrowser(@NonNull MediaBrowserCompat browser) {
                        return new MediaControllerCompat(application, browser.getSessionToken());
                    }
                }
        );
    }

    @VisibleForTesting
    public PlaybackViewModel(Application application,
                             LiveData<MediaBrowserConnector.BrowsingState> browsingState, InputFactory factory) {
        super(application);
        mInputFactory = factory;
        mColorsFactory = new MediaSourceColors.Factory(application);
        browsingState.observeForever(mMediaBrowsingObserver);
    }

    /**
     * Returns a LiveData that emits the colors for the currently set media source.
     */
    public LiveData<MediaSourceColors> getMediaSourceColors() {
        return mColors;
    }

    /**
     * 返回一个 LiveData，它发出由提供的 {@link MediaControllerCompat} 管理的会话中当前媒体项的 MediaItemMetadata。
     */
    public LiveData<MediaItemMetadata> getMetadata() {
        return mMetadata;
    }

    /**
     * Returns a LiveData that emits the current queue as MediaItemMetadatas where items without a
     * title have been filtered out.
     */
    public LiveData<List<MediaItemMetadata>> getQueue() {
        return mSanitizedQueue;
    }

    /**
     * Returns a LiveData that emits whether the MediaController has a non-empty queue
     */
    public LiveData<Boolean> hasQueue() {
        return mHasQueue;
    }

    /**
     * Returns a LiveData that emits the current queue title.
     */
    public LiveData<CharSequence> getQueueTitle() {
        return mQueueTitle;
    }

    /**
     * Returns a LiveData that emits an object for controlling the currently selected
     * MediaController.
     */
    public LiveData<PlaybackController> getPlaybackController() {
        return mPlaybackControls;
    }

    /**
     * Returns a {@PlaybackStateWrapper} live data.
     */
    public LiveData<PlaybackStateWrapper> getPlaybackStateWrapper() {
        return mPlaybackStateWrapper;
    }

    /**
     * Returns a LiveData that emits the current playback progress, in milliseconds. This is a
     * value between 0 and {@link #getPlaybackStateWrapper#getMaxProgress()} or
     * {@link PlaybackStateCompat#PLAYBACK_POSITION_UNKNOWN} if the current position is unknown.
     * This value will update on its own periodically (less than a second) while active.
     */
    public LiveData<PlaybackProgress> getProgress() {
        return mProgress;
    }

    @VisibleForTesting
    MediaControllerCompat getMediaController() {
        return mMediaControllerCallback.mMediaController;
    }

    @VisibleForTesting
    MediaMetadataCompat getMediaMetadata() {
        return mMediaControllerCallback.mMediaMetadata;
    }


    private class MediaControllerCallback extends MediaControllerCompat.Callback {

        private MediaBrowserConnector.BrowsingState mBrowsingState;
        private MediaControllerCompat mMediaController;
        private MediaMetadataCompat mMediaMetadata;
        private PlaybackStateCompat mPlaybackState;


        void onMediaBrowsingStateChanged(MediaBrowserConnector.BrowsingState newBrowsingState) {
            if (Objects.equals(mBrowsingState, newBrowsingState)) {
                Log.w(TAG, "onMediaBrowsingStateChanged noop ");
                return;
            }

            // 重置旧控制器（如果有），在浏览未暂停（崩溃）时取消注册回调。
            if (mMediaController != null) {
                switch (newBrowsingState.mConnectionStatus) {
                    case DISCONNECTING:
                    case REJECTED:
                    case CONNECTING:
                    case CONNECTED:
                        mMediaController.unregisterCallback(this);
                        // Fall through
                    case SUSPENDED:
                        setMediaController(null);
                }
            }
            mBrowsingState = newBrowsingState;
            if (mBrowsingState.mConnectionStatus == ConnectionStatus.CONNECTED) {
                setMediaController(mInputFactory.getControllerForBrowser(mBrowsingState.mBrowser));
            }
        }

        private void setMediaController(MediaControllerCompat mediaController) {
            mMediaMetadata = null;
            mPlaybackState = null;
            mMediaController = mediaController;
            mPlaybackControls.setValue(new PlaybackController(mediaController));

            if (mMediaController != null) {
                mMediaController.registerCallback(this);
                mColors.setValue(mColorsFactory.extractColors(mediaController.getPackageName()));

                // 应用程序并不总是发送更新，因此请确保我们获取最新的值。
                onMetadataChanged(mMediaController.getMetadata());
                onPlaybackStateChanged(mMediaController.getPlaybackState());
                onQueueChanged(mMediaController.getQueue());
                onQueueTitleChanged(mMediaController.getQueueTitle());
            } else {
                mColors.setValue(null);
                onMetadataChanged(null);
                onPlaybackStateChanged(null);
                onQueueChanged(null);
                onQueueTitleChanged(null);
            }

            updatePlaybackStatus();
        }

        @Override
        public void onSessionDestroyed() {
            Log.w(TAG, "onSessionDestroyed");
            // 在MediaSession销毁时unregisterCallback。
            //TODO：考虑跟踪孤立的回调，以防它们复活......
            setMediaController(null);
        }

        @Override
        public void onMetadataChanged(@Nullable MediaMetadataCompat mmdCompat) {
            // MediaSession#setMetadata 在其参数为 null 时构建一个空的 MediaMetadata，但 MediaMetadataCompat 不实现 equals...
            // 因此，如果给定的 mmdCompat 的 MediaMetadata 等于 EMPTY_MEDIA_METADATA，请将 mMediaMetadata 设置为 null 以使代码在其他任何地方都更简单。
            if ((mmdCompat != null) && EMPTY_MEDIA_METADATA.equals(mmdCompat.getMediaMetadata())) {
                mMediaMetadata = null;
            } else {
                mMediaMetadata = mmdCompat;
            }
            MediaItemMetadata item =
                    (mMediaMetadata != null) ? new MediaItemMetadata(mMediaMetadata) : null;
            mMetadata.setValue(item);
            updatePlaybackStatus();
        }

        @Override
        public void onQueueTitleChanged(CharSequence title) {
            mQueueTitle.setValue(title);
        }

        @Override
        public void onQueueChanged(@Nullable List<MediaSessionCompat.QueueItem> queue) {
            List<MediaItemMetadata> filtered = queue == null ? Collections.emptyList()
                    : queue.stream()
                    .filter(item -> item != null
                            && item.getDescription() != null
                            && item.getDescription().getTitle() != null)
                    .map(MediaItemMetadata::new)
                    .collect(Collectors.toList());
            mSanitizedQueue.setValue(filtered);
            mHasQueue.setValue(filtered.size() > 1);
        }

        @Override
        public void onPlaybackStateChanged(PlaybackStateCompat playbackState) {
            mPlaybackState = playbackState;
            updatePlaybackStatus();
        }

        private void updatePlaybackStatus() {
            if (mMediaController != null && mPlaybackState != null) {
                mPlaybackStateWrapper.setValue(
                        new PlaybackStateWrapper(mMediaController, mMediaMetadata, mPlaybackState));
            } else {
                mPlaybackStateWrapper.setValue(null);
            }
        }
    }

    /**
     * {@link PlaybackStateCompat} 的扩展。
     */
    public static final class PlaybackStateWrapper {

        private final MediaControllerCompat mMediaController;
        @Nullable
        private final MediaMetadataCompat mMetadata;
        private final PlaybackStateCompat mState;

        PlaybackStateWrapper(@NonNull MediaControllerCompat mediaController,
                             @Nullable MediaMetadataCompat metadata, @NonNull PlaybackStateCompat state) {
            mMediaController = mediaController;
            mMetadata = metadata;
            mState = state;
        }

        /**
         * 如果状态中有足够的信息来显示它的 UI，则返回 true。
         */
        public boolean shouldDisplay() {
            // STATE_NONE means no content to play.
            return mState.getState() != PlaybackStateCompat.STATE_NONE && ((mMetadata != null) || (
                    getMainAction() != ACTION_DISABLED));
        }

        /**
         * 返回 主 action
         */
        @Action
        public int getMainAction() {
            @Actions long actions = mState.getActions();
            @Action int stopAction = ACTION_DISABLED;
            if ((actions & (PlaybackStateCompat.ACTION_PAUSE
                    | PlaybackStateCompat.ACTION_PLAY_PAUSE)) != 0) {
                stopAction = ACTION_PAUSE;
            } else if ((actions & PlaybackStateCompat.ACTION_STOP) != 0) {
                stopAction = ACTION_STOP;
            }

            switch (mState.getState()) {
                case PlaybackStateCompat.STATE_PLAYING:
                case PlaybackStateCompat.STATE_BUFFERING:
                case PlaybackStateCompat.STATE_CONNECTING:
                case PlaybackStateCompat.STATE_FAST_FORWARDING:
                case PlaybackStateCompat.STATE_REWINDING:
                case PlaybackStateCompat.STATE_SKIPPING_TO_NEXT:
                case PlaybackStateCompat.STATE_SKIPPING_TO_PREVIOUS:
                case PlaybackStateCompat.STATE_SKIPPING_TO_QUEUE_ITEM:
                    return stopAction;
                case PlaybackStateCompat.STATE_STOPPED:
                case PlaybackStateCompat.STATE_PAUSED:
                case PlaybackStateCompat.STATE_NONE:
                case PlaybackStateCompat.STATE_ERROR:
                    return (actions & PlaybackStateCompat.ACTION_PLAY) != 0 ? ACTION_PLAY
                            : ACTION_DISABLED;
                default:
                    Log.w(TAG, String.format("Unknown PlaybackState: %d", mState.getState()));
                    return ACTION_DISABLED;
            }
        }

        /**
         * 返回当前支持的播放动作
         */
        public long getSupportedActions() {
            return mState.getActions();
        }

        /**
         * 返回媒体项的持续时间（以毫秒为单位）。 可以通过调用 {@link #getProgress()} 获取此持续时间内的当前位置。
         */
        public long getMaxProgress() {
            return mMetadata == null ? 0 :
                    mMetadata.getLong(MediaMetadataCompat.METADATA_KEY_DURATION);
        }

        /**
         * 返回当前媒体源是否正在播放媒体项。
         */
        public boolean isPlaying() {
            return mState.getState() == PlaybackStateCompat.STATE_PLAYING;
        }

        /**
         * 返回媒体源是否支持跳到下一项。
         */
        public boolean isSkipNextEnabled() {
            return (mState.getActions() & PlaybackStateCompat.ACTION_SKIP_TO_NEXT) != 0;
        }

        /**
         * 返回媒体源是否支持跳到上一项。
         */
        public boolean isSkipPreviousEnabled() {
            return (mState.getActions() & PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS) != 0;
        }

        /**
         * 返回媒体源是否支持在媒体流中寻找新位置。
         */
        public boolean isSeekToEnabled() {
            return (mState.getActions() & PlaybackStateCompat.ACTION_SEEK_TO) != 0;
        }

        /**
         * 返回媒体源是否需要为跳到下一个操作保留空间。
         */
        public boolean isSkipNextReserved() {
            return mMediaController.getExtras() != null
                    && (mMediaController.getExtras().getBoolean(
                    MediaConstants.SLOT_RESERVATION_SKIP_TO_NEXT)
                    || mMediaController.getExtras().getBoolean(
                    MediaConstants.PLAYBACK_SLOT_RESERVATION_SKIP_TO_NEXT));
        }

        /**
         * 返回媒体源是否需要为跳到上一个操作保留空间。
         */
        public boolean iSkipPreviousReserved() {
            return mMediaController.getExtras() != null
                    && (mMediaController.getExtras().getBoolean(
                    MediaConstants.SLOT_RESERVATION_SKIP_TO_PREV)
                    || mMediaController.getExtras().getBoolean(
                    MediaConstants.PLAYBACK_SLOT_RESERVATION_SKIP_TO_PREV));
        }

        /**
         * 返回媒体源是否正在加载（例如：缓冲、连接等）。
         */
        public boolean isLoading() {
            int state = mState.getState();
            return state == PlaybackStateCompat.STATE_BUFFERING
                    || state == PlaybackStateCompat.STATE_CONNECTING
                    || state == PlaybackStateCompat.STATE_FAST_FORWARDING
                    || state == PlaybackStateCompat.STATE_REWINDING
                    || state == PlaybackStateCompat.STATE_SKIPPING_TO_NEXT
                    || state == PlaybackStateCompat.STATE_SKIPPING_TO_PREVIOUS
                    || state == PlaybackStateCompat.STATE_SKIPPING_TO_QUEUE_ITEM;
        }

        /**
         * 见 {@link PlaybackStateCompat#getErrorMessage}.
         */
        public CharSequence getErrorMessage() {
            return mState.getErrorMessage();
        }

        /**
         * 见 {@link PlaybackStateCompat#getErrorCode()}.
         */
        public int getErrorCode() {
            return mState.getErrorCode();
        }

        /**
         * 见 {@link PlaybackStateCompat#getActiveQueueItemId}.
         */
        public long getActiveQueueItemId() {
            return mState.getActiveQueueItemId();
        }

        /**
         * 见 {@link PlaybackStateCompat#getState}.
         */
        @PlaybackStateCompat.State
        public int getState() {
            return mState.getState();
        }

        /**
         * 见 {@link PlaybackStateCompat#getExtras}.
         */
        public Bundle getExtras() {
            return mState.getExtras();
        }

        @VisibleForTesting
        PlaybackStateCompat getStateCompat() {
            return mState;
        }

        /**
         * 返回可用自定义操作的排序列表。
         * 调用{@link RawCustomPlaybackAction#fetchDrawable（Context）}以获得适当的可绘制图标。
         */
        public List<RawCustomPlaybackAction> getCustomActions() {
            List<RawCustomPlaybackAction> actions = new ArrayList<>();
            RawCustomPlaybackAction ratingAction = getRatingAction();
            if (ratingAction != null) actions.add(ratingAction);

            for (PlaybackStateCompat.CustomAction action : mState.getCustomActions()) {
                String packageName = mMediaController.getPackageName();
                actions.add(
                        new RawCustomPlaybackAction(action.getIcon(), packageName,
                                action.getAction(),
                                action.getExtras()));
            }
            return actions;
        }

        @Nullable
        private RawCustomPlaybackAction getRatingAction() {
            long stdActions = mState.getActions();
            if ((stdActions & PlaybackStateCompat.ACTION_SET_RATING) == 0) return null;

            int ratingType = mMediaController.getRatingType();
            if (ratingType != RatingCompat.RATING_HEART) return null;

            boolean hasHeart = false;
            if (mMetadata != null) {
                RatingCompat rating = mMetadata.getRating(
                        MediaMetadataCompat.METADATA_KEY_USER_RATING);
                hasHeart = rating != null && rating.hasHeart();
            }

            int iconResource = hasHeart ? R.drawable.ic_star_filled : R.drawable.ic_star_empty;
            Bundle extras = new Bundle();
            extras.putBoolean(EXTRA_SET_HEART, !hasHeart);
            return new RawCustomPlaybackAction(iconResource, null, ACTION_SET_RATING, extras);
        }
    }


    /**
     * 为 {@link MediaControllerCompat} 包装 {@link android.media.session.MediaController.TransportControls TransportControls} 以发送命令。
     * TODO(arnaudberry) 这种包装有意义吗，因为我们仍然需要对包装进行空值检查？
     * 我们应该在模型类上调用动作方法吗？
     */
    public class PlaybackController {
        private final MediaControllerCompat mMediaController;

        private PlaybackController(@Nullable MediaControllerCompat mediaController) {
            mMediaController = mediaController;
        }

        public void play() {
            if (mMediaController != null) {
                mMediaController.getTransportControls().play();
            }
        }

        public void skipToPrevious() {
            if (mMediaController != null) {
                mMediaController.getTransportControls().skipToPrevious();
            }
        }

        public void skipToNext() {
            if (mMediaController != null) {
                mMediaController.getTransportControls().skipToNext();
            }
        }

        public void pause() {
            if (mMediaController != null) {
                mMediaController.getTransportControls().pause();
            }
        }

        public void stop() {
            if (mMediaController != null) {
                mMediaController.getTransportControls().stop();
            }
        }

        /**
         * 移动到媒体流中的新位置
         *
         * @param pos 要移动到的位置，以毫秒为单位。
         */
        public void seekTo(long pos) {
            if (mMediaController != null) {
                PlaybackStateCompat oldState = mMediaController.getPlaybackState();
                PlaybackStateCompat newState = new PlaybackStateCompat.Builder(oldState)
                        .setState(oldState.getState(), pos, oldState.getPlaybackSpeed())
                        .build();
                mMediaControllerCallback.onPlaybackStateChanged(newState);
                mMediaController.getTransportControls().seekTo(pos);
            }
        }

        /**
         * 向媒体源发送自定义操作
         *
         * @param action 自定义动作的动作标识符
         * @param extras 附加额外数据以发送到媒体源。
         */
        public void doCustomAction(String action, Bundle extras) {
            if (mMediaController == null) return;
            MediaControllerCompat.TransportControls cntrl = mMediaController.getTransportControls();

            if (ACTION_SET_RATING.equals(action)) {
                boolean setHeart = extras != null && extras.getBoolean(EXTRA_SET_HEART, false);
                cntrl.setRating(RatingCompat.newHeartRating(setHeart));
            } else {
                cntrl.sendCustomAction(action, extras);
            }
        }

        /**
         * 开始播放给定的媒体项目。
         */
        public void playItem(MediaItemMetadata item) {
            if (mMediaController != null) {
                // 不要将额外内容传回，因为这不是官方 API，并且在 media2 中不受支持，因此应用程序不应依赖于此。
                mMediaController.getTransportControls().playFromMediaId(item.getId(), null);
            }
        }

        /**
         * 跳到媒体队列中的特定项目。 此 id 是通过 {@link PlaybackViewModel#getQueue()} 获得的项目的 {@link MediaItemMetadata#mQueueId}。
         */
        public void skipToQueueItem(long queueId) {
            if (mMediaController != null) {
                mMediaController.getTransportControls().skipToQueueItem(queueId);
            }
        }

        public void prepare() {
            if (mMediaController != null) {
                mMediaController.getTransportControls().prepare();
            }
        }
    }

    /**
     * 自定义播放操作的抽象表示。 自定义播放操作表示可用于触发标准 {@link PlaybackController} 类中未包含的播放操作的视觉元素。
     * 当前媒体源的自定义操作通过 {@linkPlaybackStateWrapper#getCustomActions} 公开
     * 不包含图标的 {@link Drawable} 表示。 此对象的实例应通过 {@link RawCustomPlaybackAction#fetchDrawable(Context)} 转换为 {@link CustomPlaybackAction} 以进行显示。
     */
    public static class RawCustomPlaybackAction {
        // TODO (keyboardr)：这个类（和相关的翻译代码）将在未来的 CL 中与 CustomPlaybackAction 合并。
        /**
         * Icon to display for this custom action
         */
        public final int mIcon;
        /**
         * If true, use the resources from the this package to resolve the icon. If null use our own
         * resources.
         */
        @Nullable
        public final String mPackageName;
        /**
         * Action identifier used to request this action to the media service
         */
        @NonNull
        public final String mAction;
        /**
         * Any additional information to send along with the action identifier
         */
        @Nullable
        public final Bundle mExtras;

        /**
         * Creates a custom action
         */
        public RawCustomPlaybackAction(int icon, String packageName,
                                       @NonNull String action,
                                       @Nullable Bundle extras) {
            mIcon = icon;
            mPackageName = packageName;
            mAction = action;
            mExtras = extras;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            RawCustomPlaybackAction that = (RawCustomPlaybackAction) o;

            return mIcon == that.mIcon
                    && Objects.equals(mPackageName, that.mPackageName)
                    && Objects.equals(mAction, that.mAction)
                    && Objects.equals(mExtras, that.mExtras);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mIcon, mPackageName, mAction, mExtras);
        }

        /**
         * 通过获取图标的适当可绘制对象，将此 {@link RawCustomPlaybackAction} 转换为 {@link CustomPlaybackAction}。
         * @param context 图标将被绘制到的上下文
         * @return 转换后的 CustomPlaybackAction 或 null 如果无法获得合适的 {@link Resources}
         */
        @Nullable
        public CustomPlaybackAction fetchDrawable(@NonNull Context context) {
            Drawable icon;
            if (mPackageName == null) {
                icon = context.getDrawable(mIcon);
            } else {
                Resources resources = getResourcesForPackage(context, mPackageName);
                if (resources == null) {
                    return null;
                } else {
                    // 资源可能来自另一个包。 我们需要使用活动中的上下文更新配置，以便从正确的 DPI 存储桶中获取可绘制对象。
                    resources.updateConfiguration(context.getResources().getConfiguration(),
                            context.getResources().getDisplayMetrics());
                    icon = resources.getDrawable(mIcon, null);
                }
            }
            return new CustomPlaybackAction(icon, mAction, mExtras);
        }

        private Resources getResourcesForPackage(Context context, String packageName) {
            try {
                return context.getPackageManager().getResourcesForApplication(packageName);
            } catch (PackageManager.NameNotFoundException e) {
                Log.e(TAG, "Unable to get resources for " + packageName);
                return null;
            }
        }
    }

}
