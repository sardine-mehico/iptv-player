package io.github.sardinemehico.iptvplayer.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.ui.PlayerView
import io.github.sardinemehico.iptvplayer.data.sync.Syncer
import okhttp3.OkHttpClient

/**
 * The app's single ExoPlayer. Created once, never rebuilt: zapping only swaps the MediaItem,
 * because codec setup is slow on cheap SoCs.
 */
@OptIn(UnstableApi::class)
class PlayerController(context: Context, http: OkHttpClient) {

    interface Listener {
        fun onState(state: State) {}
    }

    enum class State { IDLE, BUFFERING, PLAYING, RECONNECTING, FAILED }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<Listener>()
    private var currentUrl: String? = null
    private var retries = 0

    val player: ExoPlayer

    init {
        val dataSource = OkHttpDataSource.Factory(http).setUserAgent(Syncer.USER_AGENT)
        val mediaSources = DefaultMediaSourceFactory(dataSource, DefaultExtractorsFactory())
        // Small buffers: caps RAM on 1 GB boxes and keeps zapping quick.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(3_000, 15_000, 1_000, 2_000)
            .setTargetBufferBytes(16 * 1024 * 1024)
            .build()
        val renderers = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
        player = ExoPlayer.Builder(context, renderers, mediaSources)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true,
            )
            .build()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_BUFFERING -> emit(State.BUFFERING)
                    Player.STATE_READY -> {
                        retries = 0
                        emit(State.PLAYING)
                    }
                    else -> Unit
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    player.seekToDefaultPosition()
                    player.prepare()
                    return
                }
                if (retries < MAX_RETRIES && currentUrl != null) {
                    retries++
                    emit(State.RECONNECTING)
                    val delay = 1_000L * retries
                    main.postDelayed({ if (currentUrl != null) player.prepare() }, delay)
                } else {
                    emit(State.FAILED)
                }
            }
        })
    }

    fun attach(view: PlayerView) {
        view.player = player
    }

    fun addListener(l: Listener) {
        if (l !in listeners) listeners += l
    }

    fun removeListener(l: Listener) {
        listeners -= l
    }

    /** Plays [url]; does nothing if it is already the current stream. */
    fun play(url: String) {
        if (url == currentUrl && player.playbackState != Player.STATE_IDLE) return
        main.removeCallbacksAndMessages(null)
        currentUrl = url
        retries = 0
        val item = MediaItem.Builder().setUri(url)
        if (url.contains(".m3u8", ignoreCase = true)) item.setMimeType(MimeTypes.APPLICATION_M3U8)
        player.setMediaItem(item.build())
        player.prepare()
        player.playWhenReady = true
        emit(State.BUFFERING)
    }

    val playingUrl: String? get() = currentUrl

    fun stop() {
        main.removeCallbacksAndMessages(null)
        currentUrl = null
        player.stop()
        player.clearMediaItems()
        emit(State.IDLE)
    }

    private fun emit(state: State) {
        for (l in listeners.toList()) l.onState(state)
    }

    private companion object {
        const val MAX_RETRIES = 5
    }
}
