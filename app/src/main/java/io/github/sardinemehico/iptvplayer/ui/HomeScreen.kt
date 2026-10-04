package io.github.sardinemehico.iptvplayer.ui

import android.graphics.Rect
import android.view.View
import android.widget.TextView
import io.github.sardinemehico.iptvplayer.MainActivity
import io.github.sardinemehico.iptvplayer.R
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Live TV · Movies · Series · Playlists, as on IBO's home screen. */
class HomeScreen(activity: MainActivity) : Screen(activity) {

    override val root: View = inflater.inflate(R.layout.screen_home, null)
    private val account: TextView = root.findViewById(R.id.account)
    private val live: View = root.findViewById(R.id.tile_live)

    init {
        live.setOnClickListener { activity.push(LiveScreen(activity)) }
        root.findViewById<View>(R.id.tile_movies).setOnClickListener { activity.toast(activity.getString(R.string.coming_next)) }
        root.findViewById<View>(R.id.tile_series).setOnClickListener { activity.toast(activity.getString(R.string.coming_next)) }
        root.findViewById<View>(R.id.tile_playlists).setOnClickListener { activity.push(PlaylistsScreen(activity)) }
    }

    override fun onShown() {
        activity.setVideoRect(Rect(0, 0, 1, 1))
        graph.player.stop()
        live.requestFocus()
        scope.launch {
            val p = graph.repo.playlist(graph.prefs.activePlaylist) ?: return@launch
            val parts = arrayListOf(p.name)
            p.expires?.let { parts += "Expires " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it * 1000)) }
            if (p.maxConnections > 0) parts += "${p.maxConnections} connection" + if (p.maxConnections > 1) "s" else ""
            account.text = parts.joinToString("  ·  ")
        }
    }
}
