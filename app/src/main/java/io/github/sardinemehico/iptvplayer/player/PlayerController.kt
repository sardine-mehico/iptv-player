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
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
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

    enum class State { IDLE, BUFFERING, PLAYING, RECONNECTING, FAILED, ENDED }

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
                    Player.STATE_ENDED -> emit(State.ENDED)
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

    val isPaused: Boolean get() = !player.playWhenReady

    /** Pause or resume. Live streams pick up where the buffer is (or jump to live if it expired). */
    fun togglePause() {
        player.playWhenReady = !player.playWhenReady
    }

    /** Movies and episodes: current position and length in ms (0 if not known yet). */
    val positionMs: Long get() = player.currentPosition.coerceAtLeast(0)
    val durationMs: Long get() = player.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0) ?: 0

    fun seekBy(deltaMs: Long) {
        val d = durationMs
        val target = (positionMs + deltaMs).coerceAtLeast(0)
        player.seekTo(if (d > 0) target.coerceAtMost(d - 1000) else target)
    }

    /** One selectable audio or subtitle track of the current stream. */
    class Track(val label: String, val group: TrackGroup, val index: Int, val selected: Boolean)

    /** Tracks of [type] (C.TRACK_TYPE_AUDIO or C.TRACK_TYPE_TEXT) the device can play. */
    fun tracks(type: Int): List<Track> {
        val out = ArrayList<Track>()
        for (g in player.currentTracks.groups) {
            if (g.type != type) continue
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                val f = g.getTrackFormat(i)
                val parts = listOfNotNull(
                    f.label,
                    f.language?.takeIf { it != "und" }?.let { java.util.Locale.forLanguageTag(it).displayLanguage.ifEmpty { it } },
                    f.channelCount.takeIf { it > 0 }?.let { "${it}ch" },
                ).distinct()
                val label = parts.joinToString(" · ").ifEmpty { "Track ${out.size + 1}" }
                out += Track(label, g.mediaTrackGroup, i, g.isTrackSelected(i))
            }
        }
        return out
    }

    /** Selects [track]; null turns the type off (used for subtitles). */
    fun selectTrack(type: Int, track: Track?) {
        val b = player.trackSelectionParameters.buildUpon().clearOverridesOfType(type)
        if (track == null) {
            b.setTrackTypeDisabled(type, true)
        } else {
            b.setTrackTypeDisabled(type, false)
            b.setOverrideForType(TrackSelectionOverride(track.group, track.index))
        }
        player.trackSelectionParameters = b.build()
    }

    /** True if [type] is switched off by the user. */
    fun isTrackTypeDisabled(type: Int) = type in player.trackSelectionParameters.disabledTrackTypes

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
