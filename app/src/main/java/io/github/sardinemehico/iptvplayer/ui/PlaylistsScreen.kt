package io.github.sardinemehico.iptvplayer.ui

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import io.github.sardinemehico.iptvplayer.MainActivity
import io.github.sardinemehico.iptvplayer.R
import io.github.sardinemehico.iptvplayer.Prefs
import io.github.sardinemehico.iptvplayer.data.net.AppDns
import io.github.sardinemehico.iptvplayer.data.repo.Pin
import io.github.sardinemehico.iptvplayer.data.repo.Playlist
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App Settings, in sections: Home screen (default launcher, app slots), Start-up (auto-start),
 * Playback & network (DNS), Shortcuts (All apps, System settings) and Playlists (open, refresh,
 * details, delete, add). Each setting row shows its name, a one-line explanation and its value.
 */
class PlaylistsScreen(activity: MainActivity) : Screen(activity) {

    /** One row of row_setting.xml. */
    private class SettingRow(val view: View) {
        val title: TextView = view.findViewById(R.id.setting_title)
        val summary: TextView = view.findViewById(R.id.setting_summary)
        val value: TextView = view.findViewById(R.id.setting_value)
    }

    override val root: View = inflater.inflate(R.layout.screen_playlists, null)
    private val list: LinearLayout = root.findViewById(R.id.list)
    private val add: TextView = root.findViewById(R.id.add)
    private val message: TextView = root.findViewById(R.id.message)
    private val busyDots: View = root.findViewById(R.id.busy)
    private val homeRow = SettingRow(root.findViewById(R.id.default_home))
    private val slotsRow = SettingRow(root.findViewById(R.id.slot_count))
    private val autoStartRow = SettingRow(root.findViewById(R.id.auto_start))
    private val dnsRow = SettingRow(root.findViewById(R.id.dns))
    private var busy = false
    private var firstShow = true

    init {
        add.setOnClickListener { activity.push(AddPlaylistScreen(activity)) }

        homeRow.title.setText(R.string.set_launcher_title)
        homeRow.view.setOnClickListener {
            if (Apps.isDefaultHome(activity)) {
                // Already the Home app: let the user go back to the box's launcher or pick another.
                Apps.changeDefaultHome(activity)
            } else {
                Apps.requestDefaultHome(activity) { updateDefaultHome() }
            }
        }

        slotsRow.title.setText(R.string.set_slots_title)
        slotsRow.summary.setText(R.string.set_slots_summary)
        slotsRow.view.setOnClickListener { changeSlots(+1, wrap = true) }
        slotsRow.view.setOnKeyListener { _, keyCode, event ->
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { changeSlots(-1, wrap = false); true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { changeSlots(+1, wrap = false); true }
                else -> false
            }
        }
        showSlots()

        autoStartRow.title.setText(R.string.set_autostart_title)
        autoStartRow.view.setOnClickListener { toggleAutoStart() }

        dnsRow.title.setText(R.string.set_dns_title)
        dnsRow.summary.setText(R.string.set_dns_summary)
        dnsRow.view.setOnClickListener {
            graph.prefs.dnsMode = (graph.prefs.dnsMode + 1) % 3
            showDns()
        }
        showDns()

        root.findViewById<View>(R.id.all_apps).setOnClickListener {
            scope.launch {
                val apps = withContext(graph.io) { Apps.list(activity) }
                Apps.pick(activity, R.string.all_apps, apps) { Apps.launch(activity, it.pkg) }
            }
        }
        root.findViewById<View>(R.id.android_settings).setOnClickListener { Apps.openAndroidSettings(activity) }
    }

    private fun changeSlots(delta: Int, wrap: Boolean) {
        var n = graph.prefs.appSlotCount + delta
        if (n > Prefs.MAX_SLOTS) n = if (wrap) Prefs.MIN_SLOTS else Prefs.MAX_SLOTS
        if (n < Prefs.MIN_SLOTS) n = Prefs.MIN_SLOTS
        graph.prefs.appSlotCount = n
        showSlots()
    }

    private fun showSlots() {
        slotsRow.value.text = activity.getString(R.string.set_slots_value, graph.prefs.appSlotCount)
    }

    private fun showDns() {
        dnsRow.value.setText(
            when (graph.prefs.dnsMode) {
                AppDns.MODE_CLOUDFLARE -> R.string.set_dns_cloudflare
                AppDns.MODE_GOOGLE -> R.string.set_dns_google
                else -> R.string.set_dns_box
            },
        )
    }

    private fun updateDefaultHome() {
        val isHome = Apps.isDefaultHome(activity)
        homeRow.value.setText(if (isHome) R.string.set_launcher_is_worldtv else R.string.set_launcher_other)
        homeRow.summary.setText(if (isHome) R.string.set_launcher_summary_worldtv else R.string.set_launcher_summary_other)
    }

    private fun canLaunchAtBoot() = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(activity)

    /** On/Off, plus what happened at the last boot and anything still missing. */
    private fun updateAutoStart() {
        val prefs = graph.prefs
        autoStartRow.value.setText(if (prefs.autoStart) R.string.set_on else R.string.set_off)
        if (Apps.isDefaultHome(activity)) {
            autoStartRow.summary.setText(R.string.set_autostart_summary_home)
            return
        }
        if (!prefs.autoStart) {
            autoStartRow.summary.setText(R.string.set_autostart_summary_off)
            return
        }
        val lines = ArrayList<String>()
        if (!canLaunchAtBoot()) lines += activity.getString(R.string.auto_start_blocked, activity.packageName)
        val at = prefs.lastBootAt
        lines += if (at == 0L) {
            activity.getString(R.string.auto_start_never)
        } else {
            val time = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(at))
            activity.getString(if (prefs.lastBootAllowed) R.string.auto_start_last_boot else R.string.auto_start_last_boot_blocked, time)
        }
        autoStartRow.summary.text = lines.joinToString("\n")
    }

    private fun toggleAutoStart() {
        val prefs = graph.prefs
        // Already on but still blocked: OK reopens the permission page instead of switching off.
        val on = if (prefs.autoStart && !canLaunchAtBoot()) true else !prefs.autoStart
        prefs.autoStart = on
        updateAutoStart()
        // Android 10+ blocks opening at boot unless the app may draw over other apps.
        if (on && !canLaunchAtBoot()) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + activity.packageName))
            try {
                activity.toast(activity.getString(R.string.auto_start_permission))
                activity.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                // Many TV builds have no such settings page.
                activity.toast(activity.getString(R.string.auto_start_no_settings))
            }
        }
    }

    override fun onShown() {
        activity.hideVideo()
        updateDefaultHome() // may have been changed in system settings meanwhile
        updateAutoStart() // the permission may have been granted meanwhile
        graph.player.stop()
        reload()
    }

    override fun onBack(): Boolean = busy

    private fun reload() {
        scope.launch {
            val playlists = graph.repo.playlists()
            list.removeAllViews()
            val active = graph.prefs.activePlaylist
            for (p in playlists) list.addView(row(p, p.id == active))
            // First visit: start at the top. Coming back (from a sub-screen or the system's Home
            // setting): keep whatever had focus.
            if (firstShow) {
                firstShow = false
                homeRow.view.requestFocus()
            } else if (activity.currentFocus == null || activity.currentFocus?.parent === list) {
                (list.getChildAt(playlists.indexOfFirst { it.id == active }.coerceAtLeast(0)) ?: add).requestFocus()
            }
        }
    }

    private fun row(p: Playlist, active: Boolean): View {
        val v = LayoutInflater.from(activity).inflate(R.layout.row_text, list, false) as TextView
        val kind = if (p.isXtream) "Xtream" else "M3U"
        val lock = if (p.hasPin) "  ·  PIN" else ""
        v.text = if (active) "${p.name}  ·  $kind$lock  ·  active" else "${p.name}  ·  $kind$lock"
        v.setOnClickListener { open(p) }
        v.setOnLongClickListener { actions(p); true }
        v.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_MENU && event.action == KeyEvent.ACTION_DOWN) {
                actions(p)
                true
            } else {
                false
            }
        }
        return v
    }

    private fun open(p: Playlist) {
        if (busy) return
        graph.prefs.activePlaylist = p.id
        graph.prefs.lastLiveCategory = null
        graph.prefs.lastLiveItem = null
        activity.resetTo(HomeScreen(activity))
    }

    private fun actions(p: Playlist) {
        if (busy) return
        val labels = arrayOf(
            activity.getString(R.string.action_open),
            activity.getString(R.string.action_refresh),
            activity.getString(R.string.action_details),
            activity.getString(R.string.action_delete),
        )
        AlertDialog.Builder(activity)
            .setTitle(p.name)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> open(p)
                    1 -> refresh(p)
                    2 -> PinPrompt.require(activity, p) { details(p) }
                    3 -> PinPrompt.require(activity, p) { delete(p) }
                }
            }
            .show()
    }

    private fun refresh(p: Playlist) {
        busy = true
        busyDots.visibility = View.VISIBLE
        message.setTextColor(activity.getColor(R.color.text_secondary))
        scope.launch {
            try {
                graph.syncer.sync(p) { text -> activity.runOnUiThread { message.text = text } }
                message.text = "${p.name} is up to date"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message.setTextColor(activity.getColor(R.color.error))
                message.text = e.message ?: "Refresh failed"
            } finally {
                busy = false
                busyDots.visibility = View.GONE
            }
        }
    }

    /** Server, login and URL. Reached only through [PinPrompt] when the playlist has a PIN. */
    private fun details(p: Playlist) {
        val lines = if (p.isXtream) {
            listOf("Type: Xtream Codes", "Server: ${p.url}", "Username: ${p.username.orEmpty()}", "Password: ${p.password.orEmpty()}")
        } else {
            listOf("Type: M3U", "URL: ${p.url}")
        }
        AlertDialog.Builder(activity)
            .setTitle(p.name)
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton(android.R.string.ok, null)
            .setNeutralButton(if (p.hasPin) R.string.pin_change else R.string.pin_set) { _, _ -> changePin(p) }
            .show()
    }

    private fun changePin(p: Playlist) {
        PinPrompt.askNew(activity, canRemove = p.hasPin) { pin ->
            scope.launch {
                graph.repo.setPin(p.id, pin?.let { Pin.hash(it) })
                activity.toast(activity.getString(if (pin == null) R.string.pin_removed else R.string.pin_saved))
                reload()
            }
        }
    }

    private fun delete(p: Playlist) {
        AlertDialog.Builder(activity)
            .setTitle("Delete ${p.name}?")
            .setPositiveButton(R.string.action_delete) { _, _ ->
                scope.launch {
                    graph.repo.deletePlaylist(p.id)
                    if (graph.prefs.activePlaylist == p.id) graph.prefs.activePlaylist = -1
                    if (graph.repo.playlists().isEmpty()) {
                        activity.resetTo(HomeScreen(activity))
                        activity.push(AddPlaylistScreen(activity, firstRun = true))
                    } else {
                        reload()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
