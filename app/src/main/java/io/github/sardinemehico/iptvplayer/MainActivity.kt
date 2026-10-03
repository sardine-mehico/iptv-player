package io.github.sardinemehico.iptvplayer

import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * Single activity. Screens will be swapped in as plain Views (no Fragments, no Compose)
 * to keep cold start and per-frame cost low on 1 GB / ~1 GHz boxes.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }
}
