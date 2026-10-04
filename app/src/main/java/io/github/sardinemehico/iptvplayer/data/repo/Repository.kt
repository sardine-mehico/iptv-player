package io.github.sardinemehico.iptvplayer.data.repo

import android.content.ContentValues
import android.database.Cursor
import io.github.sardinemehico.iptvplayer.data.db.Db
import io.github.sardinemehico.iptvplayer.data.model.ContentType
import io.github.sardinemehico.iptvplayer.data.source.XtreamAccount
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

data class Playlist(
    val id: Long,
    val name: String,
    val kind: String,
    val url: String,
    val username: String?,
    val password: String?,
    val epgUrl: String?,
    val lastSync: Long,
    val expires: Long?,
    val maxConnections: Int,
    val formats: List<String>,
    val serverTz: String?,
    val status: String?,
) {
    val isXtream get() = kind == KIND_XTREAM

    companion object {
        const val KIND_XTREAM = "xtream"
        const val KIND_M3U = "m3u"
    }
}

data class CategoryRow(val key: String, val name: String)

/** What a list row needs, nothing more: keeps paged memory small. */
data class EntryRow(
    val itemId: String,
    val name: String,
    val logo: String?,
    val streamUrl: String?,
    val ext: String?,
    val catchupDays: Int,
    val favourite: Boolean,
)

/**
 * All database reads and writes. Every call runs on [io]; nothing here may be called
 * from the UI thread directly.
 */
class Repository(private val db: Db, private val io: CoroutineDispatcher) {

    // ---- playlists ----

    suspend fun playlists(): List<Playlist> = withContext(io) {
        db.readableDatabase.rawQuery("SELECT * FROM playlist ORDER BY id", null).use { c ->
            val out = ArrayList<Playlist>()
            while (c.moveToNext()) out += c.toPlaylist()
            out
        }
    }

    suspend fun playlist(id: Long): Playlist? = withContext(io) {
        db.readableDatabase.rawQuery("SELECT * FROM playlist WHERE id = ?", arrayOf(id.toString())).use { c ->
            if (c.moveToFirst()) c.toPlaylist() else null
        }
    }

    suspend fun addPlaylist(name: String, kind: String, url: String, username: String?, password: String?): Long =
        withContext(io) {
            val v = ContentValues().apply {
                put("name", name)
                put("kind", kind)
                put("url", url)
                put("username", username)
                put("password", password)
            }
            db.writableDatabase.insertOrThrow("playlist", null, v)
        }

    suspend fun deletePlaylist(id: Long) = withContext(io) {
        val w = db.writableDatabase
        val args = arrayOf(id.toString())
        w.beginTransaction()
        try {
            w.delete("entry", "playlist_id = ?", args)
            w.delete("category", "playlist_id = ?", args)
            w.delete("favourite", "playlist_id = ?", args)
            w.delete("playlist", "id = ?", args)
            w.setTransactionSuccessful()
        } finally {
            w.endTransaction()
        }
    }

    suspend fun saveAccount(id: Long, a: XtreamAccount) = withContext(io) {
        val v = ContentValues().apply {
            if (a.expiresAt != null) put("expires", a.expiresAt) else putNull("expires")
            put("max_connections", a.maxConnections)
            put("formats", a.allowedFormats.joinToString(","))
            put("server_tz", a.serverTimezone)
            put("status", a.status)
        }
        db.writableDatabase.update("playlist", v, "id = ?", arrayOf(id.toString()))
    }

    suspend fun markSynced(id: Long, epgUrl: String?) = withContext(io) {
        val v = ContentValues().apply {
            put("last_sync", System.currentTimeMillis())
            if (epgUrl != null) put("epg_url", epgUrl)
        }
        db.writableDatabase.update("playlist", v, "id = ?", arrayOf(id.toString()))
    }

    // ---- browsing ----

    /** Categories of one library, with the virtual "All" and "Favourites" entries first. */
    suspend fun categories(playlistId: Long, type: ContentType): List<CategoryRow> = withContext(io) {
        val out = arrayListOf(CategoryRow(KEY_ALL, "All"), CategoryRow(KEY_FAV, "Favourites"))
        db.readableDatabase.rawQuery(
            "SELECT cat_id, name FROM category WHERE playlist_id = ? AND type = ? ORDER BY sort",
            arrayOf(playlistId.toString(), type.ordinal.toString()),
        ).use { c ->
            while (c.moveToNext()) out += CategoryRow(c.getString(0), c.getString(1))
        }
        out
    }

    suspend fun count(playlistId: Long, type: ContentType, key: String): Int = withContext(io) {
        val (where, args) = filter(playlistId, type, key)
        db.readableDatabase.rawQuery("SELECT COUNT(*) FROM entry e $where", args).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    suspend fun page(playlistId: Long, type: ContentType, key: String, offset: Int, limit: Int): List<EntryRow> =
        withContext(io) {
            val (where, args) = filter(playlistId, type, key)
            db.readableDatabase.rawQuery(
                """SELECT e.item_id, e.name, e.logo, e.stream_url, e.ext, e.catchup_days,
                   EXISTS(SELECT 1 FROM favourite f WHERE f.playlist_id = e.playlist_id
                          AND f.type = e.type AND f.item_id = e.item_id)
                   FROM entry e $where ORDER BY e.sort LIMIT $limit OFFSET $offset""",
                args,
            ).use { c ->
                val out = ArrayList<EntryRow>(c.count)
                while (c.moveToNext()) {
                    out += EntryRow(
                        itemId = c.getString(0),
                        name = c.getString(1),
                        logo = c.getStringOrNull(2),
                        streamUrl = c.getStringOrNull(3),
                        ext = c.getStringOrNull(4),
                        catchupDays = c.getInt(5),
                        favourite = c.getInt(6) != 0,
                    )
                }
                out
            }
        }

    /** Position of [itemId] inside the list for [key], or -1. Used to restore the last channel. */
    suspend fun indexOf(playlistId: Long, type: ContentType, key: String, itemId: String): Int = withContext(io) {
        val r = db.readableDatabase
        val sort = r.rawQuery(
            "SELECT sort FROM entry WHERE playlist_id = ? AND type = ? AND item_id = ?",
            arrayOf(playlistId.toString(), type.ordinal.toString(), itemId),
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else return@withContext -1 }
        val (where, args) = filter(playlistId, type, key)
        r.rawQuery("SELECT COUNT(*) FROM entry e $where AND e.sort < $sort", args).use { c ->
            if (c.moveToFirst()) c.getInt(0) else -1
        }
    }

    /** Toggles a favourite and returns the new state. */
    suspend fun toggleFavourite(playlistId: Long, type: ContentType, itemId: String): Boolean = withContext(io) {
        val w = db.writableDatabase
        val args = arrayOf(playlistId.toString(), type.ordinal.toString(), itemId)
        val removed = w.delete("favourite", "playlist_id = ? AND type = ? AND item_id = ?", args)
        if (removed > 0) {
            false
        } else {
            val v = ContentValues().apply {
                put("playlist_id", playlistId)
                put("type", type.ordinal)
                put("item_id", itemId)
            }
            w.insertWithOnConflict("favourite", null, v, android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE)
            true
        }
    }

    private fun filter(playlistId: Long, type: ContentType, key: String): Pair<String, Array<String>> {
        val base = arrayOf(playlistId.toString(), type.ordinal.toString())
        return when (key) {
            KEY_ALL -> "WHERE e.playlist_id = ? AND e.type = ?" to base
            KEY_FAV -> ("WHERE e.playlist_id = ? AND e.type = ? AND EXISTS(SELECT 1 FROM favourite f " +
                "WHERE f.playlist_id = e.playlist_id AND f.type = e.type AND f.item_id = e.item_id)") to base
            else -> "WHERE e.playlist_id = ? AND e.type = ? AND e.category_id = ?" to (base + key)
        }
    }

    private fun Cursor.getStringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)

    private fun Cursor.toPlaylist(): Playlist {
        fun s(name: String) = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getString(it) }
        fun l(name: String) = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getLong(it) }
        return Playlist(
            id = l("id")!!,
            name = s("name").orEmpty(),
            kind = s("kind").orEmpty(),
            url = s("url").orEmpty(),
            username = s("username"),
            password = s("password"),
            epgUrl = s("epg_url"),
            lastSync = l("last_sync") ?: 0,
            expires = l("expires"),
            maxConnections = (l("max_connections") ?: 0).toInt(),
            formats = s("formats")?.split(',')?.filter { it.isNotBlank() }.orEmpty(),
            serverTz = s("server_tz"),
            status = s("status"),
        )
    }

    companion object {
        const val KEY_ALL = "\u0000all"
        const val KEY_FAV = "\u0000fav"
    }
}
