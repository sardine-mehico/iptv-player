package io.github.sardinemehico.iptvplayer

import android.graphics.Rect
import android.os.Bundle
import android.view.KeyEvent
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
                if (stack.size > 1) pop() else finish()
            }
        })

        lifecycleScope.launch { route() }
    }

    /** First screen: add a playlist, pick one, or go straight home. */
    private suspend fun route() {
        val graph = App.graph
        val playlists = graph.repo.playlists()
        val active = playlists.firstOrNull { it.id == graph.prefs.activePlaylist }
        when {
            playlists.isEmpty() -> push(AddPlaylistScreen(this, firstRun = true))
            active == null -> push(PlaylistsScreen(this))
            else -> push(HomeScreen(this))
        }
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

    /** Positions the video. null = full screen. */
    fun setVideoRect(rect: Rect?) {
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
