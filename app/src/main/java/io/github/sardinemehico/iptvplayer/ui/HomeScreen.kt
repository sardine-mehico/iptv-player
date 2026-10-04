package io.github.sardinemehico.iptvplayer.ui

import android.graphics.Rect
import android.view.View
import android.widget.TextView
import io.github.sardinemehico.iptvplayer.MainActivity
import io.github.sardinemehico.iptvplayer.R
import io.github.sardinemehico.iptvplayer.data.model.ContentType
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Live TV · Movies · Series · Settings, as on IBO's home screen. */
class HomeScreen(activity: MainActivity) : Screen(activity) {

    override val root: View = inflater.inflate(R.layout.screen_home, null)
    private val account: TextView = root.findViewById(R.id.account)
    private val live: View = root.findViewById(R.id.tile_live)

    init {
        live.setOnClickListener { activity.push(LiveScreen(activity)) }
        root.findViewById<View>(R.id.tile_movies).setOnClickListener { activity.push(VodScreen(activity, ContentType.MOVIE)) }
        root.findViewById<View>(R.id.tile_series).setOnClickListener { activity.push(VodScreen(activity, ContentType.SERIES)) }
        // Settings holds the playlist list (add, refresh, details, delete).
        root.findViewById<View>(R.id.tile_settings).setOnClickListener { activity.push(PlaylistsScreen(activity)) }
        val version = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
        root.findViewById<TextView>(R.id.version).text = activity.getString(R.string.version_label, version)
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
