package io.github.sardinemehico.iptvplayer.ui

import android.app.AlertDialog
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import io.github.sardinemehico.iptvplayer.App
import io.github.sardinemehico.iptvplayer.MainActivity
import io.github.sardinemehico.iptvplayer.R

/** Control-bar actions shared by the live and the movie/episode player. */
@OptIn(UnstableApi::class)
object PlayerUi {

    private val MODES = intArrayOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT,
        AspectRatioFrameLayout.RESIZE_MODE_FILL,
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM,
    )

    /** Fit → Stretch → Zoom, remembered for next time. */
    fun cycleAspect(activity: MainActivity) {
        val view = activity.playerView
        val next = MODES[(MODES.indexOf(view.resizeMode) + 1).mod(MODES.size)]
        view.resizeMode = next
        App.graph.prefs.resizeMode = next
    }

    fun aspectLabel(activity: MainActivity): Int = when (activity.playerView.resizeMode) {
        AspectRatioFrameLayout.RESIZE_MODE_FILL -> R.string.ctl_aspect_fill
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> R.string.ctl_aspect_zoom
        else -> R.string.ctl_aspect_fit
    }

    /**
     * Audio (C.TRACK_TYPE_AUDIO) or subtitle (C.TRACK_TYPE_TEXT) picker.
     * [onDone] runs when the picker closes, or straight away if there is nothing to pick.
     */
    fun chooseTrack(activity: MainActivity, type: Int, onDone: () -> Unit) {
        val player = App.graph.player
        val tracks = player.tracks(type)
        val isText = type == C.TRACK_TYPE_TEXT
        if (tracks.isEmpty()) {
            activity.toast(activity.getString(R.string.no_tracks))
            onDone()
            return
        }
        val labels = ArrayList<String>()
        if (isText) labels += activity.getString(R.string.track_off)
        tracks.forEach { labels += it.label }
        val offset = if (isText) 1 else 0
        val selected = tracks.indexOfFirst { it.selected }
        val checked = when {
            isText && player.isTrackTypeDisabled(type) -> 0
            selected >= 0 -> selected + offset
            isText -> 0
            else -> -1
        }
        AlertDialog.Builder(activity)
            .setTitle(if (isText) R.string.ctl_subs else R.string.ctl_audio)
            .setSingleChoiceItems(labels.toTypedArray(), checked) { d, which ->
                player.selectTrack(type, if (isText && which == 0) null else tracks[which - offset])
                d.dismiss()
            }
            .setOnDismissListener { onDone() }
            .show()
    }

    /** 1:05:09 or 5:09. */
    fun time(ms: Long): String {
        val total = (ms.coerceAtLeast(0) / 1000).toInt()
        val h = total / 3600
        val m = total / 60 % 60
        val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
}
