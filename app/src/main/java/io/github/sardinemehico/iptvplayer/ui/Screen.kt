package io.github.sardinemehico.iptvplayer.ui

import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import androidx.lifecycle.lifecycleScope
import io.github.sardinemehico.iptvplayer.App
import io.github.sardinemehico.iptvplayer.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * A screen is a plain View tree plus a little lifecycle. No Fragments: cheaper to create and
 * simpler to reason about on slow boxes. Screens are stacked by [MainActivity].
 */
abstract class Screen(protected val activity: MainActivity) {

    protected val graph get() = App.graph
    protected val inflater: LayoutInflater get() = activity.layoutInflater

    /** Cancelled when the screen is removed from the stack. */
    val scope: CoroutineScope = activity.lifecycleScope.coroutineContext.let { parent ->
        CoroutineScope(parent + SupervisorJob(parent[Job]))
    }

    abstract val root: View

    /** Called each time the screen becomes the top of the stack. */
    open fun onShown() {}

    /** Called when another screen is pushed on top, or this one is removed. */
    open fun onHidden() {}

    /** Called once when the screen is removed for good. */
    open fun onDestroy() {}

    /** Key down before normal focus handling. Return true to consume. */
    open fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = false

    /** Back pressed. Return true if handled here, false to pop the screen. */
    open fun onBack(): Boolean = false

    internal fun destroy() {
        onDestroy()
        scope.cancel()
    }
}
