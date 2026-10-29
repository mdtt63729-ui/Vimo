package com.atube.app.db

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.atube.app.db.dao.CustomInstanceDao
import com.atube.app.db.dao.DownloadDao
import com.atube.app.db.dao.LocalPlaylistsDao
import com.atube.app.db.dao.LocalSubscriptionDao
import com.atube.app.db.dao.PlaylistBookmarkDao
import com.atube.app.db.dao.SearchHistoryDao
import com.atube.app.db.dao.SubscriptionGroupsDao
import com.atube.app.db.dao.SubscriptionsFeedDao
import com.atube.app.db.dao.WatchHistoryDao
import com.atube.app.db.dao.WatchPositionDao
import com.atube.app.db.obj.CustomInstance
import com.atube.app.db.obj.Download
import com.atube.app.db.obj.DownloadChapter
import com.atube.app.db.obj.DownloadItem
import com.atube.app.db.obj.DownloadPlaylist
import com.atube.app.db.obj.DownloadPlaylistVideosCrossRef
import com.atube.app.db.obj.DownloadSponsorBlockSegment
import com.atube.app.db.obj.LocalPlaylist
import com.atube.app.db.obj.LocalPlaylistItem
import com.atube.app.db.obj.LocalSubscription
import com.atube.app.db.obj.PlaylistBookmark
import com.atube.app.db.obj.SearchHistoryItem
import com.atube.app.db.obj.SubscriptionGroup
import com.atube.app.db.obj.SubscriptionsFeedItem
import com.atube.app.db.obj.WatchHistoryItem
import com.atube.app.db.obj.WatchPosition

@Database(
    entities = [
        WatchHistoryItem::class,
        WatchPosition::class,
        SearchHistoryItem::class,
        CustomInstance::class,
        LocalSubscription::class,
        PlaylistBookmark::class,
        LocalPlaylist::class,
        LocalPlaylistItem::class,
        Download::class,
        DownloadItem::class,
        DownloadChapter::class,
        DownloadSponsorBlockSegment::class,
        DownloadPlaylist::class,
        DownloadPlaylistVideosCrossRef::class,
        SubscriptionGroup::class,
        SubscriptionsFeedItem::class
    ],
    version = 25,
    autoMigrations = [
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9),
        AutoMigration(from = 9, to = 10),
        AutoMigration(from = 10, to = 11),
        AutoMigration(from = 16, to = 17),
        AutoMigration(from = 18, to = 19),
        AutoMigration(from = 19, to = 20),
        AutoMigration(from = 20, to = 21),
        AutoMigration(from = 23, to = 24, spec = DatabaseHolder.MIGRATION_23_24::class),
        AutoMigration(from = 24, to = 25),
    ]
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    /**
     * Watch History
     */
    abstract fun watchHistoryDao(): WatchHistoryDao

    /**
     * Watch Positions
     */
    abstract fun watchPositionDao(): WatchPositionDao

    /**
     * Search History
     */
    abstract fun searchHistoryDao(): SearchHistoryDao

    /**
     * Custom Instances
     */
    abstract fun customInstanceDao(): CustomInstanceDao

    /**
     * Local Subscriptions
     */
    abstract fun localSubscriptionDao(): LocalSubscriptionDao

    /**
     * Bookmarked Playlists
     */
    abstract fun playlistBookmarkDao(): PlaylistBookmarkDao

    /**
     * Local playlists
     */
    abstract fun localPlaylistsDao(): LocalPlaylistsDao

    /**
     * Downloads
     */
    abstract fun downloadDao(): DownloadDao

    /**
     * Subscription groups
     */
    abstract fun subscriptionGroupsDao(): SubscriptionGroupsDao

    /**
     * Locally cached subscription feed
     */
    abstract fun feedDao(): SubscriptionsFeedDao
}
