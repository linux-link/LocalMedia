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

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser.MediaItem;
import android.media.session.MediaSession.QueueItem;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.provider.MediaStore;
import android.provider.MediaStore.Audio.AlbumColumns;
import android.provider.MediaStore.Audio.AudioColumns;
import android.service.media.MediaBrowserService.Result;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class DataModel {
    private static final String TAG = "LMBDataModel";

    private static final String EXTERNAL = "external";
    private static final String INTERNAL = "internal";

    private static final Uri ART_BASE_URI = Uri.parse("content://media/external/audio/albumart");

    public static final String PATH_KEY = "PATH";

    private Context mContext;
    private ContentResolver mResolver;
    private AsyncTask mPendingTask;

    private List<QueueItem> mQueue = new ArrayList<>();

    public DataModel(Context context) {
        mContext = context;
        mResolver = context.getContentResolver();
    }

    private static final Uri[] ALL_AUDIO_URI = new Uri[] {
            MediaStore.Audio.Media.INTERNAL_CONTENT_URI,
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    };

    public void onQueryByFolder(String parentId, Result<List<MediaItem>> result) {
        FilesystemListTask query = new FilesystemListTask(result, ALL_AUDIO_URI, mResolver);
        queryInBackground(result, query);
    }

    private static final Uri[] ALBUMS_URI = new Uri[] {
            MediaStore.Audio.Albums.INTERNAL_CONTENT_URI,
            MediaStore.Audio.Albums.EXTERNAL_CONTENT_URI
    };

    public void onQueryByAlbum(String parentId, Result<List<MediaItem>> result) {
        QueryTask query = new QueryTask.Builder()
                .setResolver(mResolver)
                .setResult(result)
                .setUri(ALBUMS_URI)
                .setKeyColumn(AudioColumns.ALBUM_KEY)
                .setTitleColumn(AudioColumns.ALBUM)
                .setFlags(MediaItem.FLAG_BROWSABLE)
                .build();
        queryInBackground(result, query);
    }

    private static final Uri[] ARTISTS_URI = new Uri[] {
            MediaStore.Audio.Artists.INTERNAL_CONTENT_URI,
            MediaStore.Audio.Artists.EXTERNAL_CONTENT_URI
    };

    public void onQueryByArtist(String parentId, Result<List<MediaItem>> result) {
        QueryTask query = new QueryTask.Builder()
                .setResolver(mResolver)
                .setResult(result)
                .setUri(ARTISTS_URI)
                .setKeyColumn(AudioColumns.ARTIST_KEY)
                .setTitleColumn(AudioColumns.ARTIST)
                .setFlags(MediaItem.FLAG_BROWSABLE)
                .build();
        queryInBackground(result, query);
    }

    private static final Uri[] GENRES_URI = new Uri[] {
            MediaStore.Audio.Genres.INTERNAL_CONTENT_URI,
            MediaStore.Audio.Genres.EXTERNAL_CONTENT_URI
    };

    public void onQueryByGenre(String parentId, Result<List<MediaItem>> result) {
        QueryTask query = new QueryTask.Builder()
                .setResolver(mResolver)
                .setResult(result)
                .setUri(GENRES_URI)
                .setKeyColumn(MediaStore.Audio.Genres._ID)
                .setTitleColumn(MediaStore.Audio.Genres.NAME)
                .setFlags(MediaItem.FLAG_BROWSABLE)
                .build();
        queryInBackground(result, query);
    }

    private void queryInBackground(Result<List<MediaItem>> result, AsyncTask<Void, Void, Void> task) {
        result.detach();
        if (mPendingTask != null) {
            mPendingTask.cancel(true);
        }
        mPendingTask = task;
        task.execute();
    }

    public List<QueueItem> getQueue() {
        return mQueue;
    }

    public MediaMetadata getMetadata(String key) {
        Cursor cursor = null;
        MediaMetadata.Builder metadata = new MediaMetadata.Builder();
        try {
            for (Uri uri : ALL_AUDIO_URI) {
                cursor = mResolver.query(uri, null, AudioColumns.TITLE_KEY + " = ?",
                        new String[]{ key }, null);
                if (cursor != null) {
                    int title = cursor.getColumnIndex(AudioColumns.TITLE);
                    int artist = cursor.getColumnIndex(AudioColumns.ARTIST);
                    int album = cursor.getColumnIndex(AudioColumns.ALBUM);
                    int albumId = cursor.getColumnIndex(AudioColumns.ALBUM_ID);
                    int duration = cursor.getColumnIndex(AudioColumns.DURATION);

                    while (cursor.moveToNext()) {
                        metadata.putString(MediaMetadata.METADATA_KEY_TITLE,
                                cursor.getString(title));
                        metadata.putString(MediaMetadata.METADATA_KEY_ARTIST,
                                cursor.getString(artist));
                        metadata.putString(MediaMetadata.METADATA_KEY_ALBUM,
                                cursor.getString(album));
                        metadata.putLong(MediaMetadata.METADATA_KEY_DURATION,
                                cursor.getLong(duration));

                        String albumArt = null;
                        Uri albumArtUri = ContentUris.withAppendedId(ART_BASE_URI,
                                cursor.getLong(albumId));
                        try {
                            InputStream dummy = mResolver.openInputStream(albumArtUri);
                            albumArt = albumArtUri.toString();
                            dummy.close();
                        } catch (IOException e) {
                            // Ignored because the albumArt is intialized correctly anyway.
                        }
                        metadata.putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, albumArt);
                        break;
                    }
                }
            }
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }

        return metadata.build();
    }

    private static final String QUERY_BY_KEY_WHERE_CLAUSE =
            AudioColumns.ALBUM_KEY + "= ? or "
                    + AudioColumns.ARTIST_KEY + " = ? or "
                    + AudioColumns.TITLE_KEY + " = ? or "
                    + AudioColumns.DATA + " like ?";

    // 注意：这会清除队列。 在调用此方法之前，应该拥有队列的本地备份。
    public void onQueryByKey(String lastCategory, String parentId, Result<List<MediaItem>> result) {
        mQueue.clear();
        QueryTask.Builder query = new QueryTask.Builder()
                .setResolver(mResolver)
                .setResult(result);
        // Genre（流派）来自不同的表，并且不使用通常媒体表中的 where 子句，因此我们需要有这个条件。
        if (LocalMediaBrowserService.GENRES_ID.equals(lastCategory)) {
            try {
                long id = Long.parseLong(parentId);
                query.setUri(new Uri[] {
                    MediaStore.Audio.Genres.Members.getContentUri(EXTERNAL, id),
                    MediaStore.Audio.Genres.Members.getContentUri(INTERNAL, id) });
            } catch (NumberFormatException e) {
                // 这不应该发生。
                Log.e(TAG, "Incorrect key type: " + parentId + ", sending empty result");
                result.sendResult(new ArrayList<MediaItem>());
                return;
            }
        } else {
            query.setUri(ALL_AUDIO_URI)
                    .setWhereClause(QUERY_BY_KEY_WHERE_CLAUSE)
                    .setWhereArgs(new String[] { parentId, parentId, parentId, parentId });
        }

        query.setKeyColumn(AudioColumns.TITLE_KEY)
                .setTitleColumn(AudioColumns.TITLE)
                .setSubtitleColumn(AudioColumns.ALBUM)
                .setFlags(MediaItem.FLAG_PLAYABLE)
                .setQueue(mQueue);
        queryInBackground(result, query.build());
    }

    // 为了便于理解代码，没有和QueryTask封装在一起。如果强行写在一起，看起来非常奇怪并且过度参数化，有可能变得更加冗长
    private static class FilesystemListTask extends AsyncTask<Void, Void, Void> {
        private static final String[] COLUMNS = { AudioColumns.DATA };
        private Result<List<MediaItem>> mResult;
        private Uri[] mUris;
        private ContentResolver mResolver;

        public FilesystemListTask(Result<List<MediaItem>> result, Uri[] uris,
                ContentResolver resolver) {
            mResult = result;
            mUris = uris;
            mResolver = resolver;
        }

        @Override
        protected Void doInBackground(Void... voids) {
            Set<String> paths = new HashSet<String>();
            Cursor cursor = null;
            for (Uri uri : mUris) {
                try {
                    cursor = mResolver.query(uri, COLUMNS, null , null, null);
                    if (cursor != null) {
                        int pathColumn = cursor.getColumnIndex(AudioColumns.DATA);
                        while (cursor.moveToNext()) {
                            // 我们想要对每首歌曲的路径进行重复数据删除，因此我们只得到一个包含目录的列表。
                            String fullPath = cursor.getString(pathColumn);
                            int fileNameStart = fullPath.lastIndexOf(File.separator);
                            if (fileNameStart < 0) {
                                continue;
                            }
                            String dirPath = fullPath.substring(0, fileNameStart);
                            paths.add(dirPath);
                        }
                    }
                } catch (SQLiteException e) {
                    Log.e(TAG, "Failed to execute query " + e);
                } finally {
                    if (cursor != null) {
                        cursor.close();
                    }
                }
            }

            // 取出去重目录列表，并将它们放入结果列表中，以完整目录路径为键，以便我们稍后进行匹配。
            List<MediaItem> results = new ArrayList<>();
            for (String path : paths) {
                int dirNameStart = path.lastIndexOf(File.separator) + 1;
                String dirName = path.substring(dirNameStart, path.length());
                //在封装为 MediaItem
                MediaDescription description = new MediaDescription.Builder()
                        .setMediaId(path + "%")  // 在类似查询中使用。
                        .setTitle(dirName)
                        .setSubtitle(path)
                        .build();
                results.add(new MediaItem(description, MediaItem.FLAG_BROWSABLE));
            }
            mResult.sendResult(results);
            return null;
        }
    }

    private static class QueryTask extends AsyncTask<Void, Void, Void> {
        private Result<List<MediaItem>> mResult;
        private String[] mColumns;
        private String mWhereClause;
        private String[] mWhereArgs;
        private String mKeyColumn;
        private String mTitleColumn;
        private String mSubtitleColumn;
        private Uri[] mUris;
        private int mFlags;
        private ContentResolver mResolver;
        private List<QueueItem> mQueue;

        private QueryTask(Builder builder) {
            mColumns = builder.mColumns;
            mWhereClause = builder.mWhereClause;
            mWhereArgs = builder.mWhereArgs;
            mKeyColumn = builder.mKeyColumn;
            mTitleColumn = builder.mTitleColumn;
            mUris = builder.mUris;
            mFlags = builder.mFlags;
            mResolver = builder.mResolver;
            mResult = builder.mResult;
            mQueue = builder.mQueue;
            mSubtitleColumn = builder.mSubtitleColumn;
        }

        @Override
        protected Void doInBackground(Void... voids) {
            List<MediaItem> results = new ArrayList<>();
            long idx = 0;
            Cursor cursor = null;
            for (Uri uri : mUris) {
                try {
                    cursor = mResolver.query(uri, mColumns, mWhereClause, mWhereArgs, null);
                    if (cursor != null) {
                        int keyColumn = cursor.getColumnIndex(mKeyColumn);
                        int titleColumn = cursor.getColumnIndex(mTitleColumn);
                        int pathColumn = cursor.getColumnIndex(AudioColumns.DATA);
                        int subtitleColumn = -1;
                        if (mSubtitleColumn != null) {
                            subtitleColumn = cursor.getColumnIndex(mSubtitleColumn);
                        }

                        while (cursor.moveToNext()) {
                            Bundle path = new Bundle();
                            if (pathColumn != -1) {
                                path.putString(PATH_KEY, cursor.getString(pathColumn));
                            }

                            MediaDescription.Builder builder = new MediaDescription.Builder()
                                    .setMediaId(cursor.getString(keyColumn))
                                    .setTitle(cursor.getString(titleColumn))
                                    .setExtras(path);

                            if (subtitleColumn != -1) {
                                builder.setSubtitle(cursor.getString(subtitleColumn));
                            }

                            MediaDescription description = builder.build();
                            results.add(new MediaItem(description, mFlags));

                            // 我们在这里重建队列，所以如果用户选择项目，那么我们可以立即使用这个队列。
                            if (mQueue != null) {
                                mQueue.add(new QueueItem(description, idx));
                            }
                            idx++;
                        }
                    }
                } catch (SQLiteException e) {
                    // 有时，如果媒体扫描尚未看到该类型的数据，则表不存在。
                    // 例如，在第一次遇到具有流派的歌曲之前，流派表似乎根本不存在。
                    // 如果我们遇到异常，则永远不会发送结果导致另一端挂断，这是一件坏事。
                    // 相反，我们可以保持弹性并返回一个空列表。
                    Log.i(TAG, "Failed to execute query " + e);
                } finally {
                    if (cursor != null) {
                        cursor.close();
                    }
                }
            }
            mResult.sendResult(results);
            return null;  // 忽略.
        }

        //
        // 只是示例
        //
        public static class Builder {
            private Result<List<MediaItem>> mResult;
            private String[] mColumns;
            private String mWhereClause;
            private String[] mWhereArgs;
            private String mKeyColumn;
            private String mTitleColumn;
            private String mSubtitleColumn;
            private Uri[] mUris;
            private int mFlags;
            private ContentResolver mResolver;
            private List<QueueItem> mQueue;

            public Builder setColumns(String[] columns) {
                mColumns = columns;
                return this;
            }

            public Builder setWhereClause(String whereClause) {
                mWhereClause = whereClause;
                return this;
            }

            public Builder setWhereArgs(String[] whereArgs) {
                mWhereArgs = whereArgs;
                return this;
            }

            public Builder setUri(Uri[] uris) {
                mUris = uris;
                return this;
            }

            public Builder setKeyColumn(String keyColumn) {
                mKeyColumn = keyColumn;
                return this;
            }

            public Builder setTitleColumn(String titleColumn) {
                mTitleColumn = titleColumn;
                return this;
            }

            public Builder setSubtitleColumn(String subtitleColumn) {
                mSubtitleColumn = subtitleColumn;
                return this;
            }

            public Builder setFlags(int flags) {
                mFlags = flags;
                return this;
            }

            public Builder setResult(Result<List<MediaItem>> result) {
                mResult = result;
                return this;
            }

            public Builder setResolver(ContentResolver resolver) {
                mResolver = resolver;
                return this;
            }

            public Builder setQueue(List<QueueItem> queue) {
                mQueue = queue;
                return this;
            }

            public QueryTask build() {
                if (mUris == null || mKeyColumn == null || mResolver == null ||
                        mResult == null || mTitleColumn == null) {
                    throw new IllegalStateException(
                            "uri, keyColumn, resolver, result and titleColumn are required.");
                }
                return new QueryTask(this);
            }
        }
    }
}
