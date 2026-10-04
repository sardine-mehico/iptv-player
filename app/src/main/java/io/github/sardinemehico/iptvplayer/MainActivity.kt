package io.github.sardinemehico.iptvplayer

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.annotation.OptIn
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import io.github.sardinemehico.iptvplayer.ui.AddPlaylistScreen
import io.github.sardinemehico.iptvplayer.ui.HomeScreen
import io.github.sardinemehico.iptvplayer.ui.PlaylistsScreen
import io.github.sardinemehico.iptvplayer.ui.Screen
import kotlinx.coroutines.launch

/**
 * Single activity. The video [PlayerView] sits at the bottom of the window for the whole app
 * lifetime (one SurfaceView, never hidden); screens are stacked above it and either cover it
 * or leave a transparent hole where the video should show.
 */
@OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {

    lateinit var playerView: PlayerView
        private set
    private lateinit var screens: FrameLayout
    private val stack = ArrayList<Screen>()

    /**
     * Draws text at the designed size whatever the box's "Font size" setting is. The TV layouts
     * are fixed-size (rows, tiles, buttons); some boxes ship with a 115% font scale, which made
     * every screen look zoomed in and could clip labels.
     */
    override fun attachBaseContext(newBase: Context) {
        val config = newBase.resources.configuration
        if (config.fontScale == 1f) return super.attachBaseContext(newBase)
        val fixed = Configuration(config).apply { fontScale = 1f }
        super.attachBaseContext(newBase.createConfigurationContext(fixed))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        playerView = findViewById(R.id.player)
        screens = findViewById(R.id.screens)
        App.graph.player.attach(playerView)
        playerView.resizeMode = App.graph.prefs.resizeMode

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val top = stack.lastOrNull() ?: return finish()
                if (top.onBack()) return
                if (stack.size > 1) pop() else if (!isLauncher()) finish()
                // As the launcher, Back on the home screen stays put: there is nothing behind it.
            }
        })

        lifecycleScope.launch { route() }
    }

    /** First screen: add a playlist, pick one, or go straight home. */
    /**
     * The home screen is always at the bottom of the stack (it is also the launcher, with the
     * app slots); with no playlist yet, the add/choose screen opens on top of it.
     */
    private suspend fun route() {
        val graph = App.graph
        val playlists = graph.repo.playlists()
        val active = playlists.firstOrNull { it.id == graph.prefs.activePlaylist }
        push(HomeScreen(this))
        when {
            playlists.isEmpty() -> push(AddPlaylistScreen(this, firstRun = true))
            active == null -> push(PlaylistsScreen(this))
        }
    }

    /** True when the box opened us as its launcher (Home), not from the app list. */
    fun isLauncher() = intent?.hasCategory(Intent.CATEGORY_HOME) == true

    /** Home button while WorldTV is the launcher: back to the home screen, wherever we are. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!intent.hasCategory(Intent.CATEGORY_HOME)) return
        setIntent(intent)
        while (stack.size > 1 && stack.last() !is HomeScreen) pop()
    }

    fun push(screen: Screen) {
        stack.lastOrNull()?.let {
            it.onHidden()
            screens.removeView(it.root)
        }
        stack += screen
        screens.addView(screen.root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        screen.onShown()
    }

    fun pop() {
        val top = stack.removeLastOrNull() ?: return
        top.onHidden()
        screens.removeView(top.root)
        top.destroy()
        stack.lastOrNull()?.let {
            screens.addView(it.root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            it.onShown()
        }
    }

    /** Replaces the whole stack with [screen]. */
    fun resetTo(screen: Screen) {
        while (stack.isNotEmpty()) {
            val s = stack.removeAt(stack.size - 1)
            s.onHidden()
            screens.removeView(s.root)
            s.destroy()
        }
        push(screen)
    }

    /**
     * Takes the video surface off screen while no screen shows video.
     *
     * It used to be shrunk to 1x1 px instead (to keep the surface alive). Some TV-box display
     * hardware (seen on an Allwinner H618 "8K618-T", Android 12) mis-composes such a tiny layer
     * and sends only the window background to HDMI: a plain maroon screen, while screenshots
     * (composed differently) look fine.
     */
    fun hideVideo() {
        if (playerView.visibility != View.GONE) playerView.visibility = View.GONE
    }

    /** Positions the video and shows it. null = full screen. */
    fun setVideoRect(rect: Rect?) {
        if (playerView.visibility != View.VISIBLE) playerView.visibility = View.VISIBLE
        val lp = playerView.layoutParams as FrameLayout.LayoutParams
        if (rect == null) {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            lp.leftMargin = 0
            lp.topMargin = 0
        } else {
            lp.width = rect.width()
            lp.height = rect.height()
            lp.leftMargin = rect.left
            lp.topMargin = rect.top
        }
        playerView.layoutParams = lp
    }

    fun keepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.keyCode != KeyEvent.KEYCODE_BACK) {
            val top = stack.lastOrNull()
            if (top != null && top.onKeyDown(event.keyCode, event)) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onStop() {
        super.onStop()
        // Leaving the app (Home button): stop the stream so it doesn't keep downloading.
        // onHidden first, so a screen can note where playback was (onStart calls onShown again).
        stack.lastOrNull()?.onHidden()
        App.graph.player.stop()
    }

    override fun onStart() {
        super.onStart()
        stack.lastOrNull()?.onShown()
    }

    override fun onDestroy() {
        stack.forEach { it.destroy() }
        stack.clear()
        super.onDestroy()
    }
}
