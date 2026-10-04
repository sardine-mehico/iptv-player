package io.github.sardinemehico.iptvplayer.ui

import android.view.View
import android.widget.EditText
import android.widget.TextView
import io.github.sardinemehico.iptvplayer.MainActivity
import io.github.sardinemehico.iptvplayer.R
import io.github.sardinemehico.iptvplayer.data.repo.Playlist
import io.github.sardinemehico.iptvplayer.data.source.XtreamCredentials
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Add an Xtream login or an M3U URL, check it, and load it. */
class AddPlaylistScreen(activity: MainActivity, private val firstRun: Boolean = false) : Screen(activity) {

    override val root: View = inflater.inflate(R.layout.screen_add_playlist, null)

    private val modeXtream: TextView = root.findViewById(R.id.mode_xtream)
    private val modeM3u: TextView = root.findViewById(R.id.mode_m3u)
    private val name: EditText = root.findViewById(R.id.name)
    private val server: EditText = root.findViewById(R.id.server)
    private val username: EditText = root.findViewById(R.id.username)
    private val password: EditText = root.findViewById(R.id.password)
    private val m3uUrl: EditText = root.findViewById(R.id.m3u_url)
    private val message: TextView = root.findViewById(R.id.message)
    private val save: TextView = root.findViewById(R.id.save)

    private var xtream = true
    private var busy = false

    init {
        modeXtream.setOnClickListener { setMode(true) }
        modeM3u.setOnClickListener { setMode(false) }
        save.setOnClickListener { submit() }
        if (firstRun) message.setText(R.string.first_run_notice)
        setMode(true)
    }

    override fun onShown() {
        activity.setVideoRect(android.graphics.Rect(0, 0, 1, 1))
        if (!busy) modeXtream.requestFocus()
    }

    override fun onBack(): Boolean = busy // ignore Back while loading

    private fun setMode(isXtream: Boolean) {
        xtream = isXtream
        val xtreamVisibility = if (isXtream) View.VISIBLE else View.GONE
        server.visibility = xtreamVisibility
        username.visibility = xtreamVisibility
        password.visibility = xtreamVisibility
        m3uUrl.visibility = if (isXtream) View.GONE else View.VISIBLE
        modeXtream.alpha = if (isXtream) 1f else 0.55f
        modeM3u.alpha = if (isXtream) 0.55f else 1f
    }

    private fun submit() {
        if (busy) return
        val title = name.text.toString().trim().ifEmpty { "My playlist" }
        var useXtream = xtream
        var serverUrl = server.text.toString().trim()
        var user = username.text.toString().trim()
        var pass = password.text.toString().trim()
        val m3u = m3uUrl.text.toString().trim()

        if (!useXtream) {
            if (m3u.isEmpty()) return showError("Enter the M3U URL.")
            // An Xtream panel's own M3U link: use the API instead, it's faster and richer.
            XtreamCredentials.fromM3uUrl(m3u)?.let {
                useXtream = true
                serverUrl = it.baseUrl
                user = it.username
                pass = it.password
            }
        } else if (serverUrl.isEmpty() || user.isEmpty() || pass.isEmpty()) {
            return showError("Enter the server URL, username and password.")
        }

        busy = true
        save.isEnabled = false
        val progress: (String) -> Unit = { text -> activity.runOnUiThread { showInfo(text) } }

        scope.launch {
            var createdId = -1L
            try {
                val repo = graph.repo
                if (useXtream) {
                    val creds = XtreamCredentials(serverUrl, user, pass)
                    progress("Signing in…")
                    graph.syncer.login(creds)
                    createdId = repo.addPlaylist(title, Playlist.KIND_XTREAM, creds.baseUrl, user, pass)
                } else {
                    createdId = repo.addPlaylist(title, Playlist.KIND_M3U, m3u, null, null)
                }
                val playlist = repo.playlist(createdId) ?: error("Playlist not saved")
                graph.syncer.sync(playlist, progress)
                graph.prefs.activePlaylist = createdId
                activity.resetTo(HomeScreen(activity))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (createdId > 0) graph.repo.deletePlaylist(createdId)
                showError(e.message ?: "Could not load the playlist.")
                busy = false
                save.isEnabled = true
            }
        }
    }

    private fun showError(text: String) {
        message.setTextColor(activity.getColor(R.color.error))
        message.text = text
    }

    private fun showInfo(text: String) {
        message.setTextColor(activity.getColor(R.color.text_secondary))
        message.text = text
    }
}
