package io.github.sardinemehico.iptvplayer.ui

import android.graphics.Rect
import android.view.View
import android.widget.TextView
import io.github.sardinemehico.iptvplayer.MainActivity
import io.github.sardinemehico.iptvplayer.R
import io.github.sardinemehico.iptvplayer.data.model.ContentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Live TV · Movies · Series, plus smaller Settings and Reload playlist buttons. */
class HomeScreen(activity: MainActivity) : Screen(activity) {

    override val root: View = inflater.inflate(R.layout.screen_home, null)
    private val account: TextView = root.findViewById(R.id.account)
    private val live: View = root.findViewById(R.id.tile_live)
    private val reload: TextView = root.findViewById(R.id.tile_reload)
    private val busy: View = root.findViewById(R.id.busy)
    private val reloadStatus: TextView = root.findViewById(R.id.reload_status)
    private var reloading = false

    init {
        live.setOnClickListener { activity.push(LiveScreen(activity)) }
        root.findViewById<View>(R.id.tile_movies).setOnClickListener { activity.push(VodScreen(activity, ContentType.MOVIE)) }
        root.findViewById<View>(R.id.tile_series).setOnClickListener { activity.push(VodScreen(activity, ContentType.SERIES)) }
        // Settings holds the playlist list (add, refresh, details, delete).
        root.findViewById<View>(R.id.tile_settings).setOnClickListener { activity.push(PlaylistsScreen(activity)) }
        reload.setOnClickListener { reloadPlaylist() }
        val version = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
        root.findViewById<TextView>(R.id.version).text = activity.getString(R.string.version_label, version)
    }

    override fun onShown() {
        activity.setVideoRect(Rect(0, 0, 1, 1))
        graph.player.stop()
        live.requestFocus()
        scope.launch { showAccount() }
    }

    private suspend fun showAccount() {
        val p = graph.repo.playlist(graph.prefs.activePlaylist) ?: return
        val parts = arrayListOf(p.name)
        p.expires?.let { parts += "Expires " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it * 1000)) }
        if (p.maxConnections > 0) parts += "${p.maxConnections} connection" + if (p.maxConnections > 1) "s" else ""
        account.text = parts.joinToString("  ·  ")
    }

    /**
     * Downloads the active playlist again: channels, movies, series and their links.
     * Favourites and Continue watching are kept. The old lists stay usable until it finishes.
     */
    private fun reloadPlaylist() {
        if (reloading) return
        reloading = true
        busy.visibility = View.VISIBLE
        reloadStatus.setTextColor(activity.getColor(R.color.text_secondary))
        reloadStatus.setText(R.string.reloading)
        scope.launch {
            try {
                val p = graph.repo.playlist(graph.prefs.activePlaylist) ?: return@launch
                graph.syncer.sync(p) { text -> activity.runOnUiThread { reloadStatus.text = text } }
                reloadStatus.setText(R.string.reload_done)
                showAccount()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reloadStatus.setTextColor(activity.getColor(R.color.error))
                reloadStatus.text = activity.getString(R.string.reload_failed, e.message ?: e.javaClass.simpleName)
            } finally {
                reloading = false
                busy.visibility = View.GONE
            }
        }
    }
}
