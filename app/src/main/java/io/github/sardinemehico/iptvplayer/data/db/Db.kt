package io.github.sardinemehico.iptvplayer.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Raw SQLite (no Room): no annotation processing, no generated code, smaller APK.
 * WAL mode lets screens keep reading the old rows while a sync rewrites them in one transaction.
 */
class Db(context: Context) : SQLiteOpenHelper(context, "iptv.db", null, VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
        db.setForeignKeyConstraintsEnabled(false)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE playlist (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                kind TEXT NOT NULL,
                url TEXT NOT NULL,
                username TEXT,
                password TEXT,
                epg_url TEXT,
                last_sync INTEGER NOT NULL DEFAULT 0,
                expires INTEGER,
                max_connections INTEGER NOT NULL DEFAULT 0,
                formats TEXT,
                server_tz TEXT,
                status TEXT
            )""",
        )
        db.execSQL(
            """CREATE TABLE category (
                playlist_id INTEGER NOT NULL,
                type INTEGER NOT NULL,
                cat_id TEXT NOT NULL,
                name TEXT NOT NULL,
                sort INTEGER NOT NULL,
                PRIMARY KEY (playlist_id, type, cat_id)
            )""",
        )
        db.execSQL(
            """CREATE TABLE entry (
                playlist_id INTEGER NOT NULL,
                type INTEGER NOT NULL,
                item_id TEXT NOT NULL,
                name TEXT NOT NULL,
                category_id TEXT,
                logo TEXT,
                stream_url TEXT,
                epg_id TEXT,
                catchup_days INTEGER NOT NULL DEFAULT 0,
                ext TEXT,
                rating TEXT,
                plot TEXT,
                added INTEGER NOT NULL DEFAULT 0,
                sort INTEGER NOT NULL
            )""",
        )
        db.execSQL("CREATE UNIQUE INDEX entry_key ON entry(playlist_id, type, item_id)")
        db.execSQL("CREATE INDEX entry_all ON entry(playlist_id, type, sort)")
        db.execSQL("CREATE INDEX entry_cat ON entry(playlist_id, type, category_id, sort)")
        // Favourites live outside `entry` so they survive a re-sync.
        db.execSQL(
            """CREATE TABLE favourite (
                playlist_id INTEGER NOT NULL,
                type INTEGER NOT NULL,
                item_id TEXT NOT NULL,
                PRIMARY KEY (playlist_id, type, item_id)
            )""",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    companion object {
        const val VERSION = 1
    }
}
