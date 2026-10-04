package io.github.sardinemehico.iptvplayer.ui

import android.app.AlertDialog
import android.graphics.Rect
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import io.github.sardinemehico.iptvplayer.MainActivity
import io.github.sardinemehico.iptvplayer.R
import io.github.sardinemehico.iptvplayer.data.model.ContentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Live TV · Movies · Series, smaller App Settings and Reload playlist buttons, and five app
 * slots the user fills (WorldTV can be the box's launcher).
 */
class HomeScreen(activity: MainActivity) : Screen(activity) {

    override val root: View = inflater.inflate(R.layout.screen_home, null)
    private val account: TextView = root.findViewById(R.id.account)
    private val live: View = root.findViewById(R.id.tile_live)
    private val reload: TextView = root.findViewById(R.id.tile_reload)
    private val busy: View = root.findViewById(R.id.busy)
    private val reloadStatus: TextView = root.findViewById(R.id.reload_status)
    private var reloading = false
    private val slots: List<View> = List(SLOTS) { i -> root.findViewById(SLOT_IDS[i]) }

    init {
        live.setOnClickListener { withPlaylist { activity.push(LiveScreen(activity)) } }
        root.findViewById<View>(R.id.tile_movies).setOnClickListener { withPlaylist { activity.push(VodScreen(activity, ContentType.MOVIE)) } }
        root.findViewById<View>(R.id.tile_series).setOnClickListener { withPlaylist { activity.push(VodScreen(activity, ContentType.SERIES)) } }
        // Settings holds the playlist list (add, refresh, details, delete).
        root.findViewById<View>(R.id.tile_settings).setOnClickListener { activity.push(PlaylistsScreen(activity)) }
        reload.setOnClickListener { reloadPlaylist() }
        slots.forEachIndexed { i, v ->
            v.setOnClickListener { onSlotClicked(i) }
            v.setOnLongClickListener { slotMenu(i); true }
            v.setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_DOWN) { slotMenu(i); true } else false
            }
        }
        val version = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName
        root.findViewById<TextView>(R.id.version).text = activity.getString(R.string.version_label, version)
    }

    override fun onShown() {
        activity.setVideoRect(Rect(0, 0, 1, 1))
        graph.player.stop()
        live.requestFocus()
        scope.launch { showAccount() }
        scope.launch { showSlots() } // an app may have been installed or removed meanwhile
    }

    /** Opens a library, or the playlist screens first if there is no playlist yet. */
    private fun withPlaylist(open: () -> Unit) {
        scope.launch {
            val playlists = graph.repo.playlists()
            when {
                playlists.isEmpty() -> activity.push(AddPlaylistScreen(activity, firstRun = true))
                playlists.none { it.id == graph.prefs.activePlaylist } -> activity.push(PlaylistsScreen(activity))
                else -> open()
            }
        }
    }

    // ---- app slots ----

    private var slotApps: List<LaunchableApp?> = List(SLOTS) { null }

    private suspend fun showSlots() {
        val packages = List(SLOTS) { graph.prefs.appSlot(it) }
        // Icons and banners come from disk: load them off the UI thread.
        slotApps = withContext(graph.io) { packages.map { pkg -> pkg?.let { Apps.info(activity, it) } } }
        slots.forEachIndexed { i, v ->
            val icon = v.findViewById<ImageView>(R.id.app_icon)
            val label = v.findViewById<TextView>(R.id.app_label)
            val app = slotApps[i]
            when {
                app == null -> {
                    icon.setImageResource(R.drawable.ic_add)
                    icon.imageTintList = activity.getColorStateList(R.color.text_secondary)
                    label.setText(R.string.add_app)
                    label.visibility = View.VISIBLE
                }
                app.banner != null -> {
                    // TV banners already carry the app's name.
                    icon.imageTintList = null
                    icon.setImageDrawable(app.banner)
                    label.visibility = View.GONE
                }
                else -> {
                    icon.imageTintList = null
                    icon.setImageDrawable(app.icon)
                    label.text = app.label
                    label.visibility = View.VISIBLE
                }
            }
            v.contentDescription = app?.label ?: activity.getString(R.string.add_app)
        }
    }

    private fun onSlotClicked(i: Int) {
        val app = slotApps[i]
        if (app != null) Apps.launch(activity, app.pkg) else chooseApp(i)
    }

    private fun slotMenu(i: Int) {
        if (slotApps[i] == null) return chooseApp(i)
        AlertDialog.Builder(activity)
            .setTitle(slotApps[i]?.label)
            .setItems(arrayOf(activity.getString(R.string.slot_change), activity.getString(R.string.slot_remove))) { _, which ->
                if (which == 0) {
                    chooseApp(i)
                } else {
                    graph.prefs.setAppSlot(i, null)
                    scope.launch { showSlots() }
                }
            }
            .show()
    }

    private fun chooseApp(i: Int) {
        scope.launch {
            val apps = withContext(graph.io) { Apps.list(activity) }
            Apps.pick(activity, R.string.choose_app, apps) { app ->
                graph.prefs.setAppSlot(i, app.pkg)
                scope.launch { showSlots() }
            }
        }
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
                val p = graph.repo.playlist(graph.prefs.activePlaylist)
                if (p == null) {
                    reloadStatus.setText(R.string.no_playlist_yet)
                    return@launch
                }
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

    private companion object {
        const val SLOTS = 5
        val SLOT_IDS = intArrayOf(R.id.app_slot_0, R.id.app_slot_1, R.id.app_slot_2, R.id.app_slot_3, R.id.app_slot_4)
    }
}
