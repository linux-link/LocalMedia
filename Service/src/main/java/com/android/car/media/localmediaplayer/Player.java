/*
 * Copyright (c) 2016, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.car.media.localmediaplayer;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.AudioManager.OnAudioFocusChangeListener;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.session.MediaSession;
import android.media.session.MediaSession.QueueItem;
import android.media.session.PlaybackState;
import android.media.session.PlaybackState.CustomAction;
import android.os.Bundle;
import android.util.Log;

import com.android.car.media.localmediaplayer.nano.Proto.Playlist;
import com.android.car.media.localmediaplayer.nano.Proto.Song;

// Proto should be available in AOSP.
import com.google.protobuf.nano.MessageNano;
import com.google.protobuf.nano.InvalidProtocolBufferNanoException;

import java.io.IOException;
import java.io.File;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * TODO: Consider doing all content provider accesses and player operations asynchronously.
 */
public class Player extends MediaSession.Callback {
    private static final String TAG = "LMPlayer";
    private static final String SHARED_PREFS_NAME = "com.android.car.media.localmediaplayer.prefs";
    private static final String CURRENT_PLAYLIST_KEY = "__CURRENT_PLAYLIST_KEY__";
    private static final int NOTIFICATION_ID = 42;
    private static final int REQUEST_CODE = 94043;

    private static final float PLAYBACK_SPEED = 1.0f;
    private static final float PLAYBACK_SPEED_STOPPED = 1.0f;
    private static final long PLAYBACK_POSITION_STOPPED = 0;

    // 注意：队列循环，所以下一个/上一个总是可用的。
    private static final long PLAYING_ACTIONS = PlaybackState.ACTION_PAUSE
            | PlaybackState.ACTION_PLAY_FROM_MEDIA_ID | PlaybackState.ACTION_SKIP_TO_NEXT
            | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_SKIP_TO_QUEUE_ITEM;

    private static final long PAUSED_ACTIONS = PlaybackState.ACTION_PLAY
            | PlaybackState.ACTION_PLAY_FROM_MEDIA_ID | PlaybackState.ACTION_SKIP_TO_NEXT
            | PlaybackState.ACTION_SKIP_TO_PREVIOUS;

    private static final long STOPPED_ACTIONS = PlaybackState.ACTION_PLAY
            | PlaybackState.ACTION_PLAY_FROM_MEDIA_ID | PlaybackState.ACTION_SKIP_TO_NEXT
            | PlaybackState.ACTION_SKIP_TO_PREVIOUS;

    private static final String SHUFFLE = "android.car.media.localmediaplayer.shuffle";

    private final Context mContext;
    private final MediaSession mSession;
    private final AudioManager mAudioManager;
    private final PlaybackState mErrorState;
    private final DataModel mDataModel;
    private final CustomAction mShuffle;

    private List<QueueItem> mQueue;
    private int mCurrentQueueIdx = 0;
    private final SharedPreferences mSharedPrefs;

    private NotificationManager mNotificationManager;
    private Notification.Builder mPlayingNotificationBuilder;
    private Notification.Builder mPausedNotificationBuilder;

    // TODO: Use multiple media players for gapless playback.
    private final MediaPlayer mMediaPlayer;

    public Player(Context context, MediaSession session, DataModel dataModel) {
        mContext = context;
        mDataModel = dataModel;
        // 创建AudioManager
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

        mSession = session;
        // 创建SharedPreferences用于记录播放状态
        mSharedPrefs = context.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE);

        mShuffle = new CustomAction.Builder(SHUFFLE, context.getString(R.string.shuffle),
                R.drawable.shuffle).build();

        mMediaPlayer = new MediaPlayer();
        mMediaPlayer.reset();
        mMediaPlayer.setOnCompletionListener(mOnCompletionListener);

        // 初始化播放器状态，这里设定为error状态
        mErrorState = new PlaybackState.Builder()
                .setState(PlaybackState.STATE_ERROR, 0, 0)
                .setErrorMessage(context.getString(R.string.playback_error))
                .build();

        // 初始化Notification
        mNotificationManager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        // 媒体通知有两种形式，播放时需要显示暂停和跳过的控件，暂停时需要显示播放和跳过的控件。
        // 预先为这两个设置预先填充的构建器。
        Notification.Action prevAction = makeNotificationAction(
                LocalMediaBrowserService.ACTION_PREV, R.drawable.ic_prev, R.string.prev);
        Notification.Action nextAction = makeNotificationAction(
                LocalMediaBrowserService.ACTION_NEXT, R.drawable.ic_next, R.string.next);
        Notification.Action playAction = makeNotificationAction(
                LocalMediaBrowserService.ACTION_PLAY, R.drawable.ic_play, R.string.play);
        Notification.Action pauseAction = makeNotificationAction(
                LocalMediaBrowserService.ACTION_PAUSE, R.drawable.ic_pause, R.string.pause);

        // 播放时，需要上一个，暂停，下一个。
        mPlayingNotificationBuilder = new Notification.Builder(context)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setSmallIcon(R.drawable.ic_sd_storage_black)
                .addAction(prevAction)
                .addAction(pauseAction)
                .addAction(nextAction);

        // 暂停时，需要上一个，播放，下一个。
        mPausedNotificationBuilder = new Notification.Builder(context)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setSmallIcon(R.drawable.ic_sd_storage_black)
                .addAction(prevAction)
                .addAction(playAction)
                .addAction(nextAction);
    }

    // 创建 Notification.Action
    private Notification.Action makeNotificationAction(String action, int iconId, int stringId) {
        PendingIntent intent = PendingIntent.getBroadcast(mContext, REQUEST_CODE,
                new Intent(action), PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Action notificationAction = new Notification.Action.Builder(iconId,
                mContext.getString(stringId), intent)
                .build();
        return notificationAction;
    }

    @Override
    public void onPlay() {
        super.onPlay();
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onPlay");
        }
        // 每次尝试播放媒体时都要检查权限
        if (!Utils.hasRequiredPermissions(mContext)) {
            setMissingPermissionError();
        } else {
            requestAudioFocus(() -> resumePlayback());
        }
    }

    // 权限检查错误
    private void setMissingPermissionError() {
        // 启动权限申请用的Activity
        Intent prefsIntent = new Intent();
        prefsIntent.setClass(mContext, PermissionsActivity.class);
        prefsIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pendingIntent = PendingIntent.getActivity(mContext, 0, prefsIntent, 0);

        // 将播放状态设定未ERROR
        Bundle extras = new Bundle();
        extras.putString(Utils.ERROR_RESOLUTION_ACTION_LABEL,
                mContext.getString(R.string.permission_error_resolve));
        extras.putParcelable(Utils.ERROR_RESOLUTION_ACTION_INTENT, pendingIntent);
        PlaybackState state = new PlaybackState.Builder()
                .setState(PlaybackState.STATE_ERROR, 0, 0)
                .setErrorMessage(mContext.getString(R.string.permission_error))
                .setExtras(extras)
                .build();
        mSession.setPlaybackState(state);
    }

    private void resumePlayback() {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "resumePlayback()");
        }
        // 更新播放状态
        updatePlaybackStatePlaying();
        if (!mMediaPlayer.isPlaying()) {
            mMediaPlayer.start();
        }
    }

    @Override
    public void onPlayFromMediaId(String mediaId, Bundle extras) {
        super.onPlayFromMediaId(mediaId, extras);
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onPlayFromMediaId mediaId" + mediaId + " extras=" + extras);
        }
        requestAudioFocus(() -> startPlayback(mediaId));
    }

    private void startPlayback(String key) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "startPlayback()");
        }
        List<QueueItem> queue = mDataModel.getQueue();
        int idx = 0;
        int foundIdx = -1;
        for (QueueItem item : queue) {
            if (item.getDescription().getMediaId().equals(key)) {
                foundIdx = idx;
                break;
            }
            idx++;
        }
        if (foundIdx == -1) {
            mSession.setPlaybackState(mErrorState);
            return;
        }
        mQueue = new ArrayList<>(queue);
        mCurrentQueueIdx = foundIdx;
        QueueItem current = mQueue.get(mCurrentQueueIdx);
        String path = current.getDescription().getExtras().getString(DataModel.PATH_KEY);
        MediaMetadata metadata = mDataModel.getMetadata(current.getDescription().getMediaId());
        updateSessionQueueState();
        try {
            play(path, metadata);
        } catch (IOException e) {
            Log.e(TAG, "Playback failed.", e);
            mSession.setPlaybackState(mErrorState);
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onPause");
        }
        pausePlayback();
        // 放弃音频焦点
        mAudioManager.abandonAudioFocus(mAudioFocusListener);
    }

    private void pausePlayback() {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "pausePlayback()");
        }
        long currentPosition = 0;
        if (mMediaPlayer.isPlaying()) {
            currentPosition = mMediaPlayer.getCurrentPosition();
            mMediaPlayer.pause();
        }
        // 更新播放状态
        PlaybackState state = new PlaybackState.Builder()
                .setState(PlaybackState.STATE_PAUSED, currentPosition, PLAYBACK_SPEED_STOPPED)
                .setActions(PAUSED_ACTIONS)
                .addCustomAction(mShuffle)
                .setActiveQueueItemId(mQueue.get(mCurrentQueueIdx).getQueueId())
                .build();
        mSession.setPlaybackState(state);
        // 更新媒体的Notification状态。
        postMediaNotification(mPausedNotificationBuilder);
    }

    @Override
    public void onSkipToNext() {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onSkipToNext()");
        }
        safeAdvance();
    }

    private void safeAdvance() {
        try {
            advance();
        } catch (IOException e) {
            Log.e(TAG, "Failed to advance.", e);
            mSession.setPlaybackState(mErrorState);
        }
    }

    private void advance() throws IOException {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "advance()");
        }
        // 如果存在，请转到下一首歌曲。
        // 请注意，如果您要支持无缝播放，则必须更改此代码，
        // 以便拥有当前正在播放和正在加载的MediaPlayer，并在它们之间进行切换，同时还调用setNextMediaPlayer。
        if (mQueue != null && !mQueue.isEmpty()) {
            // 当我们跑出当前队列的末尾时，继续循环。
            mCurrentQueueIdx = (mCurrentQueueIdx + 1) % mQueue.size();
            playCurrentQueueIndex();
        } else {
            // 停止播放
            stopPlayback();
        }
    }

    private void playCurrentQueueIndex() throws IOException {
        MediaDescription next = mQueue.get(mCurrentQueueIdx).getDescription();
        String path = next.getExtras().getString(DataModel.PATH_KEY);
        MediaMetadata metadata = mDataModel.getMetadata(next.getMediaId());
        play(path, metadata);
    }

    private void play(String path, MediaMetadata metadata) throws IOException {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "play path=" + path + " metadata=" + metadata);
        }
        mMediaPlayer.reset();
        mMediaPlayer.setDataSource(path);
        mMediaPlayer.prepare();
        if (metadata != null) {
            mSession.setMetadata(metadata);
        }
        // 判断此时是否获取到音频焦点
        boolean wasGrantedAudio = requestAudioFocus(() -> {
            mMediaPlayer.start();
            updatePlaybackStatePlaying();
        });
        if (!wasGrantedAudio) {
            pausePlayback();
        }
    }

    private void stopPlayback() {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "stopPlayback()");
        }
        if (mMediaPlayer.isPlaying()) {
            mMediaPlayer.stop();
        }
        // 更新播放状态
        PlaybackState state = new PlaybackState.Builder()
                .setState(PlaybackState.STATE_STOPPED, PLAYBACK_POSITION_STOPPED,
                        PLAYBACK_SPEED_STOPPED)
                .setActions(STOPPED_ACTIONS)
                .build();
        mSession.setPlaybackState(state);
    }

    @Override
    public void onSkipToPrevious() {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "onSkipToPrevious()");
        }
        safeRetreat();
    }

    private void safeRetreat() {
        try {
            retreat();
        } catch (IOException e) {
            Log.e(TAG, "Failed to advance.", e);
            mSession.setPlaybackState(mErrorState);
        }
    }

    private void retreat() throws IOException {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "retreat()");
        }
        // 如果有下一首歌，请转到下一首。
        // 请注意，如果要支持无间隙播放，则必须更改此代码，以便在调用setNextMediaPlayer的同时，
        // 拥有当前正在播放和正在加载的MediaPlayer，并在两者之间进行切换。
        if (mQueue != null) {
            // 当我们跑完当前队列的末尾时，继续循环。
            mCurrentQueueIdx--;
            if (mCurrentQueueIdx < 0) {
                mCurrentQueueIdx = mQueue.size() - 1;
            }
            playCurrentQueueIndex();
        } else {
            stopPlayback();
        }
    }

    @Override
    public void onSkipToQueueItem(long id) {
        try {
            mCurrentQueueIdx = (int) id;
            playCurrentQueueIndex();
        } catch (IOException e) {
            Log.e(TAG, "Failed to play.", e);
            mSession.setPlaybackState(mErrorState);
        }
    }

    @Override
    public void onCustomAction(String action, Bundle extras) {
        switch (action) {
            case SHUFFLE:
                shuffle();
                break;
            default:
                Log.e(TAG, "Unhandled custom action: " + action);
        }
    }

    /**
     * 这是shuffle 的一个简单实现，之前播放的歌曲可能会在shuffle操作后重复。只能从主线程调用此函数。
     * shuffle 可以理解为乱序播放。
     */
    private void shuffle() {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "Shuffling");
        }
        // 以随机的形式重建队列。
        if (mQueue != null && mQueue.size() > 2) {
            QueueItem current = mQueue.remove(mCurrentQueueIdx);
            // 打乱队列顺序
            Collections.shuffle(mQueue);
            mQueue.add(0, current);
            // QueueItem 包含一个队列 id，当用户选择当前播放列表时，该 id 用作键。
            // 这意味着必须重建 QueueItems 以设置其新 ID。
            for (int i = 0; i < mQueue.size(); i++) {
                mQueue.set(i, new QueueItem(mQueue.get(i).getDescription(), i));
            }
            mCurrentQueueIdx = 0;
            // 更新MediaSession队列状态
            updateSessionQueueState();
        }
    }

    private void updateSessionQueueState() {
        mSession.setQueueTitle(mContext.getString(R.string.playlist));
        mSession.setQueue(mQueue);
    }

    public void destroy() {
        stopPlayback();
        mNotificationManager.cancelAll();
        mAudioManager.abandonAudioFocus(mAudioFocusListener);
        mMediaPlayer.release();
    }

    public void saveState() {
        if (mQueue == null || mQueue.isEmpty()) {
            return;
        }

        Playlist playlist = new Playlist();
        playlist.songs = new Song[mQueue.size()];

        int idx = 0;
        for (QueueItem item : mQueue) {
            Song song = new Song();
            song.queueId = item.getQueueId();
            MediaDescription description = item.getDescription();
            song.mediaId = description.getMediaId();
            song.title = description.getTitle().toString();
            song.subtitle = description.getSubtitle().toString();
            song.path = description.getExtras().getString(DataModel.PATH_KEY);

            playlist.songs[idx] = song;
            idx++;
        }
        playlist.currentQueueId = mQueue.get(mCurrentQueueIdx).getQueueId();
        playlist.currentSongPosition = mMediaPlayer.getCurrentPosition();
        playlist.name = CURRENT_PLAYLIST_KEY;

        // Go to Base64 to ensure that we can actually store the string in a sharedpref. This is
        // slightly wasteful because of the fact that base64 expands the size a bit but it's a
        // lot less riskier than abusing the java string to directly store bytes coming out of
        // proto encoding.
        String serialized = Base64.getEncoder().encodeToString(MessageNano.toByteArray(playlist));
        SharedPreferences.Editor editor = mSharedPrefs.edit();
        editor.putString(CURRENT_PLAYLIST_KEY, serialized);
        editor.commit();
    }

    public boolean maybeRestoreState() {
        if (!Utils.hasRequiredPermissions(mContext)) {
            setMissingPermissionError();
            return false;
        }
        String serialized = mSharedPrefs.getString(CURRENT_PLAYLIST_KEY, null);
        if (serialized == null) {
            return false;
        }

        try {
            Playlist playlist = Playlist.parseFrom(Base64.getDecoder().decode(serialized));
            if (!maybeRebuildQueue(playlist)) {
                return false;
            }
            updateSessionQueueState();

            requestAudioFocus(() -> {
                try {
                    playCurrentQueueIndex();
                    mMediaPlayer.seekTo(playlist.currentSongPosition);
                    updatePlaybackStatePlaying();
                } catch (IOException e) {
                    Log.e(TAG, "Restored queue, but couldn't resume playback.");
                }
            });
        } catch (IllegalArgumentException | InvalidProtocolBufferNanoException e) {
            // Couldn't restore the playlist. Not the end of the world.
            return false;
        }

        return true;
    }

    private boolean maybeRebuildQueue(Playlist playlist) {
        List<QueueItem> queue = new ArrayList<>();
        int foundIdx = 0;
        // You need to check if the playlist actually is still valid because the user could have
        // deleted files or taken out the sd card between runs so we might as well check this ahead
        // of time before we load up the playlist.
        for (Song song : playlist.songs) {
            File tmp = new File(song.path);
            if (!tmp.exists()) {
                continue;
            }

            if (playlist.currentQueueId == song.queueId) {
                foundIdx = queue.size();
            }

            Bundle bundle = new Bundle();
            bundle.putString(DataModel.PATH_KEY, song.path);
            MediaDescription description = new MediaDescription.Builder()
                    .setMediaId(song.mediaId)
                    .setTitle(song.title)
                    .setSubtitle(song.subtitle)
                    .setExtras(bundle)
                    .build();
            queue.add(new QueueItem(description, song.queueId));
        }

        if (queue.isEmpty()) {
            return false;
        }

        mQueue = queue;
        mCurrentQueueIdx = foundIdx;  // Resumes from beginning if last playing song was not found.

        return true;
    }

    // 更新播放状态
    private void updatePlaybackStatePlaying() {
        if (!mSession.isActive()) {
            mSession.setActive(true);
        }
        // 更新媒体会话中的状态。
        CustomAction action = new CustomAction
                .Builder("android.car.media.localmediaplayer.shuffle",
                mContext.getString(R.string.shuffle),
                R.drawable.shuffle)
                .build();
        PlaybackState state = new PlaybackState.Builder()
                .setState(PlaybackState.STATE_PLAYING,
                        mMediaPlayer.getCurrentPosition(), PLAYBACK_SPEED)
                .setActions(PLAYING_ACTIONS)
                .addCustomAction(action)
                .setActiveQueueItemId(mQueue.get(mCurrentQueueIdx).getQueueId())
                .build();
        mSession.setPlaybackState(state);
        // 更新媒体样式的通知。
        postMediaNotification(mPlayingNotificationBuilder);
    }

    // 更新媒体的Notification状态
    private void postMediaNotification(Notification.Builder builder) {
        if (mQueue == null) {
            return;
        }
        MediaDescription current = mQueue.get(mCurrentQueueIdx).getDescription();
        Notification notification = builder
                .setStyle(new Notification.MediaStyle().setMediaSession(mSession.getSessionToken()))
                .setContentTitle(current.getTitle())
                .setContentText(current.getSubtitle())
                .setShowWhen(false)
                .build();
        notification.flags |= Notification.FLAG_NO_CLEAR;
        mNotificationManager.notify(NOTIFICATION_ID, notification);
    }

    private boolean requestAudioFocus(Runnable onSuccess) {
        int result = mAudioManager.requestAudioFocus(mAudioFocusListener, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN);
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            onSuccess.run();
            return true;
        }
        Log.e(TAG, "Failed to acquire audio focus");
        return false;
    }


    private OnAudioFocusChangeListener mAudioFocusListener = new OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange(int focus) {
            switch (focus) {
                case AudioManager.AUDIOFOCUS_GAIN:
                    resumePlayback();
                    break;
                case AudioManager.AUDIOFOCUS_LOSS:
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    pausePlayback();
                    break;
                default:
                    Log.e(TAG, "Unhandled audio focus type: " + focus);
            }
        }
    };

    private OnCompletionListener mOnCompletionListener = new OnCompletionListener() {
        @Override
        public void onCompletion(MediaPlayer mediaPlayer) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onCompletion()");
            }
            safeAdvance();
        }
    };
}
