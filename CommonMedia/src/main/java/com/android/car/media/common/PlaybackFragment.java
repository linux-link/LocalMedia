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

package com.android.car.media.common;

import static android.car.media.CarMediaManager.MEDIA_SOURCE_MODE_PLAYBACK;

import static com.android.car.arch.common.LiveDataFunctions.mapNonNull;

import android.app.Application;
import android.app.PendingIntent;
import android.car.Car;
import android.car.content.pm.CarPackageManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.arch.core.util.Function;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;
import androidx.lifecycle.ViewModelProviders;

import com.android.car.apps.common.BitmapUtils;
import com.android.car.apps.common.CrossfadeImageView;
import com.android.car.apps.common.imaging.ImageBinder;
import com.android.car.apps.common.imaging.ImageBinder.PlaceholderType;
import com.android.car.apps.common.util.CarPackageManagerUtils;
import com.android.car.apps.common.util.ViewUtils;
import com.android.car.arch.common.FutureData;
import com.android.car.media.common.browse.MediaBrowserViewModelImpl;
import com.android.car.media.common.browse.MediaItemsRepository;
import com.android.car.media.common.playback.PlaybackViewModel;
import com.android.car.media.common.playback.PlaybackViewModel.PlaybackStateWrapper;
import com.android.car.media.common.source.MediaSource;
import com.android.car.media.common.source.MediaSourceViewModel;

import java.util.List;

/**
 * {@link Fragment} 可用于显示和控制当前播放的媒体项。
 * 它要求托管应用程序持有 android.Manifest.permission.MEDIA_CONTENT_CONTROL 权限。
 */
public class PlaybackFragment extends Fragment {
    private static final String TAG = "PlaybackFragmentWidget";

    private Car mCar;
    private CarPackageManager mCarPackageManager;
    private Intent mAppSelectorIntent;
    private MediaSourceViewModel mMediaSourceViewModel;
    private PlaybackViewModel mPlaybackViewModel;
    private ImageBinder<MediaItemMetadata.ArtworkRef> mAlbumArtBinder;
    private ViewModel mInnerViewModel;

    private PlaybackErrorViewController mPlaybackErrorViewController;
    private PlaybackErrorsHelper mErrorsHelper;
    private boolean mIsFatalError;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             Bundle savedInstanceState) {
        FragmentActivity activity = requireActivity();
        mCar = Car.createCar(activity);
        mCarPackageManager = (CarPackageManager) mCar.getCarManager(Car.PACKAGE_SERVICE);
        // 获取 PlaybackViewModel 和 MediaSourceViewModel
        Application application = activity.getApplication();
        mPlaybackViewModel = PlaybackViewModel.get(application, MEDIA_SOURCE_MODE_PLAYBACK);
        mMediaSourceViewModel = MediaSourceViewModel.get(application, MEDIA_SOURCE_MODE_PLAYBACK);
        mAppSelectorIntent = MediaSource.getSourceSelectorIntent(getContext(), true);
        // PlaybackFragment持有的内部ViewModel
        mInnerViewModel = ViewModelProviders.of(activity).get(ViewModel.class);
        mInnerViewModel.init(activity, mMediaSourceViewModel, mPlaybackViewModel,
                MediaItemsRepository.get(application, MEDIA_SOURCE_MODE_PLAYBACK));

        View view = inflater.inflate(R.layout.playback_fragment, container, false);

        // 用于分解大多数错误处理逻辑的抽象类。
        mPlaybackErrorViewController = new PlaybackErrorViewController(view);
        // 基本播放控制栏（不显示任何元数据）。
        PlaybackControlsActionBar playbackControls = view.findViewById(R.id.playback_controls);
        playbackControls.setModel(mPlaybackViewModel, getViewLifecycleOwner());

        // 处理错误场景
        mErrorsHelper = new PlaybackErrorsHelper(activity) {

            @Override
            public void handleNewPlaybackState(String displayedMessage, PendingIntent intent,
                                               String label) {
                mIsFatalError = false;
                if (!TextUtils.isEmpty(displayedMessage)) {
                    Boolean hasChildren = mInnerViewModel.getBrowseTreeHasChildren().getValue();
                    if (hasChildren != null && !hasChildren) {
                        boolean isDistractionOptimized =
                                intent != null && CarPackageManagerUtils.isDistractionOptimized(mCarPackageManager, intent);
                        mPlaybackErrorViewController.setError(displayedMessage, label, intent,
                                isDistractionOptimized);
                        mIsFatalError = true;
                    }
                }

                if (!mIsFatalError) {
                    mPlaybackErrorViewController.hideError();
                }
            }
        };
        mPlaybackViewModel.getPlaybackStateWrapper().observe(getViewLifecycleOwner(),
                state -> {
                    ViewUtils.setVisible(playbackControls,
                            (state != null) && state.shouldDisplay());
                    if (mErrorsHelper != null) {
                        mErrorsHelper.handlePlaybackState(TAG, state, /*ignoreSameState*/ true);
                    }
                });

        // 处理 SubscriptionCallback.onChildrenLoaded 中回调的数据
        mInnerViewModel.getBrowseTreeHasChildren().observe(getViewLifecycleOwner(),
                this::onBrowseTreeHasChildrenChanged);

        // 处理媒体源变更
        mMediaSourceViewModel.getPrimaryMediaSource().observe(getViewLifecycleOwner(),
                this::onMediaSourceChanged);
        // UI 初始化
        TextView appName = view.findViewById(R.id.app_name);
        mInnerViewModel.getAppName().observe(getViewLifecycleOwner(), appName::setText);

        TextView title = view.findViewById(R.id.title);
        mInnerViewModel.getTitle().observe(getViewLifecycleOwner(), title::setText);

        TextView subtitle = view.findViewById(R.id.subtitle);
        mInnerViewModel.getSubtitle().observe(getViewLifecycleOwner(), subtitle::setText);

        boolean useSourceLogoForAppSelector =
                getResources().getBoolean(R.bool.use_media_source_logo_for_app_selector);

        ImageView appIcon = view.findViewById(R.id.app_icon);
        View appSelector = view.findViewById(R.id.app_selector_container);
        mInnerViewModel.getAppIcon().observe(getViewLifecycleOwner(), bitmap -> {
            if (useSourceLogoForAppSelector) {
                ImageView appSelectorIcon = appSelector.findViewById(R.id.app_selector);
                appSelectorIcon.setImageBitmap(bitmap);
                appSelectorIcon.setImageTintList(null);

                appIcon.setVisibility(View.GONE);
            } else {
                appIcon.setImageBitmap(bitmap);
            }
        });

        View playbackScrim = view.findViewById(R.id.playback_scrim);
        playbackScrim.setOnClickListener(
                // Let the Media center trampoline figure out what to open.
                v -> {
                    if (!mIsFatalError) {
                        startActivity(new Intent(Car.CAR_INTENT_ACTION_MEDIA_TEMPLATE));
                    }
                });

        CrossfadeImageView albumBackground = view.findViewById(R.id.album_background);
        int max = activity.getResources().getInteger(R.integer.media_items_bitmap_max_size_px);
        Size maxArtSize = new Size(max, max);
        mAlbumArtBinder = new ImageBinder<>(PlaceholderType.FOREGROUND, maxArtSize,
                drawable -> {
                    Bitmap bitmap = (drawable != null)
                            ? BitmapUtils.fromDrawable(drawable, maxArtSize) : null;
                    albumBackground.setImageBitmap(bitmap, true);
                });

        mPlaybackViewModel.getMetadata().observe(getViewLifecycleOwner(),
                item -> mAlbumArtBinder.setImage(PlaybackFragment.this.getContext(),
                        item != null ? item.getArtworkKey() : null));
        appSelector.setVisibility(mAppSelectorIntent != null ? View.VISIBLE : View.GONE);
        appSelector.setOnClickListener(e -> getContext().startActivity(mAppSelectorIntent));

        return view;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        mCar.disconnect();
        mErrorsHelper = null;
    }

    private void onBrowseTreeHasChildrenChanged(@Nullable Boolean hasChildren) {
        if (hasChildren != null && mErrorsHelper != null) {
            PlaybackStateWrapper state = mPlaybackViewModel.getPlaybackStateWrapper().getValue();
            mErrorsHelper.handlePlaybackState(TAG, state, /*ignoreSameState*/ false);
        }
    }

    private void onMediaSourceChanged(MediaSource source) {
        mPlaybackErrorViewController.hideErrorNoAnim();
    }

    /**
     * ViewModel for the PlaybackFragment
     */
    public static class ViewModel extends AndroidViewModel {

        private LiveData<MediaSource> mMediaSource;
        private LiveData<CharSequence> mAppName;
        private LiveData<Bitmap> mAppIcon;
        private LiveData<CharSequence> mTitle;
        private LiveData<CharSequence> mSubtitle;
        private MutableLiveData<Boolean> mBrowseTreeHasChildren = new MutableLiveData<>();

        private MediaItemsRepository mMediaItemsRepository;
        private PlaybackViewModel mPlaybackViewModel;
        private MediaSourceViewModel mMediaSourceViewModel;

        public ViewModel(Application application) {
            super(application);
        }

        void init(FragmentActivity activity, MediaSourceViewModel mediaSourceViewModel,
                  PlaybackViewModel playbackViewModel, MediaItemsRepository mediaItemsRepository) {
            if (mMediaSourceViewModel == mediaSourceViewModel
                    && mPlaybackViewModel == playbackViewModel
                    && mMediaItemsRepository == mediaItemsRepository) {
                return;
            }
            mPlaybackViewModel = playbackViewModel;
            mMediaSourceViewModel = mediaSourceViewModel;
            mMediaItemsRepository = mediaItemsRepository;

            mMediaSource = mMediaSourceViewModel.getPrimaryMediaSource();
            // 媒体源APP名字
            mAppName = mapNonNull(mMediaSource, new Function<MediaSource, CharSequence>() {
                @Override
                public CharSequence apply(MediaSource mediaSource) {
                    return mediaSource.getDisplayName();
                }
            });
            // 媒体源APP图标
            mAppIcon = mapNonNull(mMediaSource, new Function<MediaSource, Bitmap>() {
                @Override
                public Bitmap apply(MediaSource mediaSource) {
                    return mediaSource.getCroppedPackageIcon();
                }
            });

            // 当前播放的媒体的title
            mTitle = mapNonNull(playbackViewModel.getMetadata(), new Function<MediaItemMetadata, CharSequence>() {
                @Override
                public CharSequence apply(MediaItemMetadata mediaItemMetadata) {
                    return mediaItemMetadata.getTitle();
                }
            });
            // 当前播放的媒体的子title
            mSubtitle = mapNonNull(playbackViewModel.getMetadata(), new Function<MediaItemMetadata, CharSequence>() {
                @Override
                public CharSequence apply(MediaItemMetadata mediaItemMetadata) {
                    return mediaItemMetadata.getSubtitle();
                }
            });
            // 媒体列表数据
            mMediaItemsRepository.getRootMediaItems()
                    .observe(activity, this::onRootMediaItemsUpdate);
        }

        LiveData<CharSequence> getAppName() {
            return mAppName;
        }

        LiveData<Bitmap> getAppIcon() {
            return mAppIcon;
        }

        LiveData<CharSequence> getTitle() {
            return mTitle;
        }

        LiveData<CharSequence> getSubtitle() {
            return mSubtitle;
        }

        LiveData<Boolean> getBrowseTreeHasChildren() {
            return mBrowseTreeHasChildren;
        }

        private void onRootMediaItemsUpdate(FutureData<List<MediaItemMetadata>> data) {
            if (data.isLoading()) {
                mBrowseTreeHasChildren.setValue(null);
                return;
            }
            List<MediaItemMetadata> items = MediaBrowserViewModelImpl.filterItems(/*forRoot*/ true, data.getData());

            boolean browseTreeHasChildren = items != null && !items.isEmpty();
            mBrowseTreeHasChildren.setValue(browseTreeHasChildren);
        }

        /**
         * 类似于 Transformations.map(LiveData, Function)，但在 source 发出 null 时发出 nullValue。
         * func 的输入可能被视为不可为空。
         */
        public static <T, R> LiveData<R> mapNonNull(@NonNull LiveData<T> source,
                                                    @NonNull Function<T, R> func) {
            return mapNonNull(source, null, func);
        }

        /**
         * 类似于 Transformations.map(LiveData, Function)，但在 source 发出 null 时发出 nullValue。
         * func 的输入可能被视为不可为空。
         */
        public static <T, R> LiveData<R> mapNonNull(@NonNull LiveData<T> source, @Nullable R nullValue,
                                                    @NonNull Function<T, R> func) {
            return Transformations.map(source, new Function<T, R>() {
                @Override
                public R apply(T value) {
                    if (value == null) {
                        return nullValue;
                    } else {
                        return func.apply(value);
                    }
                }
            });
        }
    }
}
