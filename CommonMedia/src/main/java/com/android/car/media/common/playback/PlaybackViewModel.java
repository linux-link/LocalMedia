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
 * ????????????????????? ViewModel???
 * <p>
 * ?????????????????? MediaController ?????????????????????????????????????????? observables???
 * <p>
 * PlaybackViewModel ???????????????????????????????????????????????????????????????????????????
 */
public class PlaybackViewModel extends AndroidViewModel {
    private static final String TAG = "PlaybackViewModel";

    private static final String ACTION_SET_RATING =
            "com.android.car.media.common.ACTION_SET_RATING";
    private static final String EXTRA_SET_HEART = "com.android.car.media.common.EXTRA_SET_HEART";

    private static PlaybackViewModel[] sInstances = new PlaybackViewModel[2];

    /**
     * ?????????????????????????????????????????????PlaybackViewModel????????????
     */
    public static PlaybackViewModel get(@NonNull Application application, int mode) {
        if (sInstances[mode] == null) {
            sInstances[mode] = new PlaybackViewModel(application, mode);
        }
        return sInstances[mode];
    }

    /**
     * ?????????action
     */
    @IntDef({ACTION_PLAY, ACTION_STOP, ACTION_PAUSE, ACTION_DISABLED})
    @Retention(RetentionPolicy.SOURCE)
    public @interface Action {
    }

    /**
     * main action?????????????????????????????????
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
     * ????????? MediaMetadata ?????? compat ??????????????? equals...
     */
    private static final MediaMetadata EMPTY_MEDIA_METADATA = new MediaMetadata.Builder().build();

    private final MediaControllerCallback mMediaControllerCallback = new MediaControllerCallback();
    private final Observer<MediaBrowserConnector.BrowsingState> mMediaBrowsingObserver =
            mMediaControllerCallback::onMediaBrowsingStateChanged;

    private final MediaSourceColors.Factory mColorsFactory;
    private final MutableLiveData<MediaSourceColors> mColors = dataOf(null);

    private final MutableLiveData<MediaItemMetadata> mMetadata = dataOf(null);

    // ????????????????????????????????????????????????????????????????????? MediaItemMetadata
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
     * ???????????? LiveData???????????????????????? {@link MediaControllerCompat} ???????????????????????????????????? MediaItemMetadata???
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

            // ??????????????????????????????????????????????????????????????????????????????????????????
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

                // ?????????????????????????????????????????????????????????????????????????????????
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
            // ???MediaSession?????????unregisterCallback???
            //TODO???????????????????????????????????????????????????......
            setMediaController(null);
        }

        @Override
        public void onMetadataChanged(@Nullable MediaMetadataCompat mmdCompat) {
            // MediaSession#setMetadata ??????????????? null ????????????????????? MediaMetadata?????? MediaMetadataCompat ????????? equals...
            // ???????????????????????? mmdCompat ??? MediaMetadata ?????? EMPTY_MEDIA_METADATA????????? mMediaMetadata ????????? null ????????????????????????????????????????????????
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
     * {@link PlaybackStateCompat} ????????????
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
         * ???????????????????????????????????????????????? UI???????????? true???
         */
        public boolean shouldDisplay() {
            // STATE_NONE means no content to play.
            return mState.getState() != PlaybackStateCompat.STATE_NONE && ((mMetadata != null) || (
                    getMainAction() != ACTION_DISABLED));
        }

        /**
         * ?????? ??? action
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
         * ?????????????????????????????????
         */
        public long getSupportedActions() {
            return mState.getActions();
        }

        /**
         * ????????????????????????????????????????????????????????? ?????????????????? {@link #getProgress()} ??????????????????????????????????????????
         */
        public long getMaxProgress() {
            return mMetadata == null ? 0 :
                    mMetadata.getLong(MediaMetadataCompat.METADATA_KEY_DURATION);
        }

        /**
         * ???????????????????????????????????????????????????
         */
        public boolean isPlaying() {
            return mState.getState() == PlaybackStateCompat.STATE_PLAYING;
        }

        /**
         * ?????????????????????????????????????????????
         */
        public boolean isSkipNextEnabled() {
            return (mState.getActions() & PlaybackStateCompat.ACTION_SKIP_TO_NEXT) != 0;
        }

        /**
         * ?????????????????????????????????????????????
         */
        public boolean isSkipPreviousEnabled() {
            return (mState.getActions() & PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS) != 0;
        }

        /**
         * ????????????????????????????????????????????????????????????
         */
        public boolean isSeekToEnabled() {
            return (mState.getActions() & PlaybackStateCompat.ACTION_SEEK_TO) != 0;
        }

        /**
         * ??????????????????????????????????????????????????????????????????
         */
        public boolean isSkipNextReserved() {
            return mMediaController.getExtras() != null
                    && (mMediaController.getExtras().getBoolean(
                    MediaConstants.SLOT_RESERVATION_SKIP_TO_NEXT)
                    || mMediaController.getExtras().getBoolean(
                    MediaConstants.PLAYBACK_SLOT_RESERVATION_SKIP_TO_NEXT));
        }

        /**
         * ??????????????????????????????????????????????????????????????????
         */
        public boolean iSkipPreviousReserved() {
            return mMediaController.getExtras() != null
                    && (mMediaController.getExtras().getBoolean(
                    MediaConstants.SLOT_RESERVATION_SKIP_TO_PREV)
                    || mMediaController.getExtras().getBoolean(
                    MediaConstants.PLAYBACK_SLOT_RESERVATION_SKIP_TO_PREV));
        }

        /**
         * ?????????????????????????????????????????????????????????????????????
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
         * ??? {@link PlaybackStateCompat#getErrorMessage}.
         */
        public CharSequence getErrorMessage() {
            return mState.getErrorMessage();
        }

        /**
         * ??? {@link PlaybackStateCompat#getErrorCode()}.
         */
        public int getErrorCode() {
            return mState.getErrorCode();
        }

        /**
         * ??? {@link PlaybackStateCompat#getActiveQueueItemId}.
         */
        public long getActiveQueueItemId() {
            return mState.getActiveQueueItemId();
        }

        /**
         * ??? {@link PlaybackStateCompat#getState}.
         */
        @PlaybackStateCompat.State
        public int getState() {
            return mState.getState();
        }

        /**
         * ??? {@link PlaybackStateCompat#getExtras}.
         */
        public Bundle getExtras() {
            return mState.getExtras();
        }

        @VisibleForTesting
        PlaybackStateCompat getStateCompat() {
            return mState;
        }

        /**
         * ?????????????????????????????????????????????
         * ??????{@link RawCustomPlaybackAction#fetchDrawable???Context???}????????????????????????????????????
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
     * ??? {@link MediaControllerCompat} ?????? {@link android.media.session.MediaController.TransportControls TransportControls} ??????????????????
     * TODO(arnaudberry) ?????????????????????????????????????????????????????????????????????????????????
     * ???????????????????????????????????????????????????
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
         * ?????????????????????????????????
         *
         * @param pos ?????????????????????????????????????????????
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
         * ?????????????????????????????????
         *
         * @param action ?????????????????????????????????
         * @param extras ??????????????????????????????????????????
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
         * ????????????????????????????????????
         */
        public void playItem(MediaItemMetadata item) {
            if (mMediaController != null) {
                // ??????????????????????????????????????????????????? API???????????? media2 ?????????????????????????????????????????????????????????
                mMediaController.getTransportControls().playFromMediaId(item.getId(), null);
            }
        }

        /**
         * ??????????????????????????????????????? ??? id ????????? {@link PlaybackViewModel#getQueue()} ?????????????????? {@link MediaItemMetadata#mQueueId}???
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
     * ??????????????????????????????????????? ???????????????????????????????????????????????? {@link PlaybackController} ????????????????????????????????????????????????
     * ??????????????????????????????????????? {@linkPlaybackStateWrapper#getCustomActions} ??????
     * ?????????????????? {@link Drawable} ????????? ??????????????????????????? {@link RawCustomPlaybackAction#fetchDrawable(Context)} ????????? {@link CustomPlaybackAction} ??????????????????
     */
    public static class RawCustomPlaybackAction {
        // TODO (keyboardr)????????????????????????????????????????????????????????? CL ?????? CustomPlaybackAction ?????????
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
         * ??????????????????????????????????????????????????? {@link RawCustomPlaybackAction} ????????? {@link CustomPlaybackAction}???
         * @param context ?????????????????????????????????
         * @return ???????????? CustomPlaybackAction ??? null ??????????????????????????? {@link Resources}
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
                    // ????????????????????????????????? ???????????????????????????????????????????????????????????????????????? DPI ????????????????????????????????????
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
