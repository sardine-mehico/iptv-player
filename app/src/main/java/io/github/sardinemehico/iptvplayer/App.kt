package io.github.sardinemehico.iptvplayer

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.allowRgb565
import coil3.request.crossfade
import io.github.sardinemehico.iptvplayer.data.db.Db
import io.github.sardinemehico.iptvplayer.data.repo.Repository
import io.github.sardinemehico.iptvplayer.data.sync.Syncer
import io.github.sardinemehico.iptvplayer.player.PlayerController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import java.util.concurrent.TimeUnit

class App : Application(), SingletonImageLoader.Factory {

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val lowRam = (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
        val memoryBytes = if (lowRam) 8L * 1024 * 1024 else 16L * 1024 * 1024
        return ImageLoader.Builder(context)
            .memoryCache { MemoryCache.Builder().maxSizeBytes(memoryBytes).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("images").toOkioPath())
                    .maxSizeBytes(64L * 1024 * 1024)
                    .build()
            }
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { graph.http })) }
            .allowRgb565(true)
            .crossfade(false)
            .build()
    }

    companion object {
        lateinit var graph: AppGraph
            private set
    }
}

/** Manual dependency graph: everything is created lazily, nothing on startup. */
class AppGraph(private val app: Application) {

    @OptIn(ExperimentalCoroutinesApi::class)
    val io: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(2)

    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    val db: Db by lazy { Db(app) }
    val repo: Repository by lazy { Repository(db, io) }
    val syncer: Syncer by lazy { Syncer(http, db, repo, io) }
    val player: PlayerController by lazy { PlayerController(app, http) }
    val prefs: Prefs by lazy { Prefs(app.getSharedPreferences("app", Context.MODE_PRIVATE)) }
}

/** Small settings and "last used" state. */
class Prefs(private val sp: SharedPreferences) {
    var activePlaylist: Long
        get() = sp.getLong("active_playlist", -1)
        set(v) = sp.edit().putLong("active_playlist", v).apply()

    var lastLiveCategory: String?
        get() = sp.getString("last_live_cat", null)
        set(v) = sp.edit().putString("last_live_cat", v).apply()

    var lastLiveItem: String?
        get() = sp.getString("last_live_item", null)
        set(v) = sp.edit().putString("last_live_item", v).apply()

    /** Open the app when the box boots. */
    var autoStart: Boolean
        get() = sp.getBoolean("auto_start", false)
        set(v) = sp.edit().putBoolean("auto_start", v).apply()

    /** When the boot broadcast last reached the app (ms), 0 = never. Shown in Settings. */
    var lastBootAt: Long
        get() = sp.getLong("last_boot_at", 0)
        set(v) = sp.edit().putLong("last_boot_at", v).apply()

    /** Whether the app had the permission Android 10+ needs to open itself at that boot. */
    var lastBootAllowed: Boolean
        get() = sp.getBoolean("last_boot_allowed", false)
        set(v) = sp.edit().putBoolean("last_boot_allowed", v).apply()

    /** Package shown in home-screen app slot [index] (0..4), or null if the slot is empty. */
    fun appSlot(index: Int): String? = sp.getString("app_slot_$index", null)

    fun setAppSlot(index: Int, pkg: String?) = sp.edit().putString("app_slot_$index", pkg).apply()

    /** PlayerView resize mode (AspectRatioFrameLayout.RESIZE_MODE_*), 0 = fit. */
    var resizeMode: Int
        get() = sp.getInt("resize_mode", 0)
        set(v) = sp.edit().putInt("resize_mode", v).apply()
}
