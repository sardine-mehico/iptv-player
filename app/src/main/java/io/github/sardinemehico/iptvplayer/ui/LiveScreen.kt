package io.github.sardinemehico.iptvplayer.ui

import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.sardinemehico.iptvplayer.MainActivity
import io.github.sardinemehico.iptvplayer.R
import io.github.sardinemehico.iptvplayer.data.model.ContentType
import io.github.sardinemehico.iptvplayer.data.repo.CategoryRow
import io.github.sardinemehico.iptvplayer.data.repo.EntryRow
import io.github.sardinemehico.iptvplayer.data.repo.Playlist
import io.github.sardinemehico.iptvplayer.data.repo.Repository
import io.github.sardinemehico.iptvplayer.data.source.XtreamCredentials
import io.github.sardinemehico.iptvplayer.data.source.XtreamUrls
import io.github.sardinemehico.iptvplayer.player.PlayerController
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Live TV: categories · channels · preview, like IBO's live screen.
 * OK on a channel plays it in the preview; OK again goes full screen.
 * Full screen: Up/Down or CH+/CH- zap, OK shows the banner, Back returns to the list.
 */
class LiveScreen(activity: MainActivity) : Screen(activity) {

    override val root: View = inflater.inflate(R.layout.screen_live, null)

    private val panels: View = root.findViewById(R.id.panels)
    private val categoriesView: RecyclerView = root.findViewById(R.id.categories)
    private val channelsView: RecyclerView = root.findViewById(R.id.channels)
    private val categoryTitle: TextView = root.findViewById(R.id.category_title)
    private val empty: View = root.findViewById(R.id.empty)
    private val preview: View = root.findViewById(R.id.preview)
    private val nowName: TextView = root.findViewById(R.id.now_name)
    private val nowStatus: TextView = root.findViewById(R.id.now_status)
    private val banner: View = root.findViewById(R.id.banner)
    private val bannerName: TextView = root.findViewById(R.id.banner_name)
    private val bannerInfo: TextView = root.findViewById(R.id.banner_info)
    private val osdStatus: TextView = root.findViewById(R.id.osd_status)

    private val handler = Handler(Looper.getMainLooper())
    private val hideBanner = Runnable { banner.visibility = View.GONE }

    private val categoryAdapter = CategoryAdapter(onFocused = ::onCategoryFocused, onClicked = ::onCategoryClicked)
    private val channelAdapter = PagedEntryAdapter(scope, ::onChannelClicked)

    private var playlist: Playlist? = null
    private var urls: XtreamUrls? = null
    private var liveExt = "ts"
    private var categories: List<CategoryRow> = emptyList()
    private var categoryIndex = -1
    private var categoryKey = Repository.KEY_ALL
    private var channelCount = 0
    private var playingIndex = -1
    private var playingRow: EntryRow? = null
    private var fullscreen = false
    private var loaded = false
    private var categoryJob: Job? = null
    private var pendingCategory: Job? = null

    private val playerListener = object : PlayerController.Listener {
        override fun onState(state: PlayerController.State) {
            val text = when (state) {
                PlayerController.State.BUFFERING -> activity.getString(R.string.loading)
                PlayerController.State.RECONNECTING -> activity.getString(R.string.reconnecting)
                PlayerController.State.FAILED -> activity.getString(R.string.stream_failed)
                else -> ""
            }
            nowStatus.text = text
            osdStatus.text = text
            osdStatus.visibility = if (fullscreen && text.isNotEmpty()) View.VISIBLE else View.GONE
        }
    }

    init {
        categoriesView.layoutManager = LinearLayoutManager(activity)
        categoriesView.adapter = categoryAdapter
        categoriesView.itemAnimator = null
        channelsView.layoutManager = LinearLayoutManager(activity)
        channelsView.adapter = channelAdapter
        channelsView.itemAnimator = null
        channelsView.setHasFixedSize(true)
        channelsView.setItemViewCacheSize(12)

        // Keep the preview 16:9 and put the video exactly under it whenever layout changes.
        preview.addOnLayoutChangeListener { v, left, _, right, _, _, _, _, _ ->
            val wantHeight = (right - left) * 9 / 16
            if (wantHeight > 0 && v.layoutParams.height != wantHeight) {
                v.layoutParams = v.layoutParams.also { it.height = wantHeight }
            } else if (!fullscreen) {
                v.post { placeVideoInPreview() }
            }
        }
    }

    override fun onShown() {
        activity.keepScreenOn(true)
        graph.player.addListener(playerListener)
        if (fullscreen) activity.setVideoRect(null) else preview.post { placeVideoInPreview() }
        if (!loaded) {
            loaded = true
            scope.launch { load() }
        } else {
            playingRow?.let { play(playingIndex, it) }
        }
    }

    override fun onHidden() {
        activity.keepScreenOn(false)
        graph.player.removeListener(playerListener)
        graph.player.stop()
        handler.removeCallbacksAndMessages(null)
    }

    override fun onBack(): Boolean {
        if (fullscreen) {
            exitFullscreen()
            return true
        }
        return false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (fullscreen) {
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> zap(-1)
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_UP -> zap(+1)
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_LEFT,
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_INFO -> showBanner()
                KeyEvent.KEYCODE_MENU -> playingRow?.let { toggleFavourite(playingIndex, it) }
                else -> return false
            }
            return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_MENU -> {
                val focused = activity.currentFocus ?: return false
                if (focused.parent !== channelsView) return false
                val pos = channelsView.getChildAdapterPosition(focused)
                val row = channelAdapter.rowAt(pos) ?: return true
                toggleFavourite(pos, row)
                return true
            }
            KeyEvent.KEYCODE_CHANNEL_UP -> { zap(+1); return true }
            KeyEvent.KEYCODE_CHANNEL_DOWN -> { zap(-1); return true }
        }
        return false
    }

    // ---- loading ----

    private suspend fun load() {
        val p = graph.repo.playlist(graph.prefs.activePlaylist) ?: return
        playlist = p
        if (p.isXtream) {
            urls = XtreamUrls(XtreamCredentials(p.url, p.username.orEmpty(), p.password.orEmpty()))
            liveExt = if (p.formats.isEmpty() || "ts" in p.formats) "ts" else "m3u8"
        }
        categories = graph.repo.categories(p.id, ContentType.LIVE)
        categoryAdapter.items = categories

        val lastKey = graph.prefs.lastLiveCategory
        val startIndex = categories.indexOfFirst { it.key == lastKey }.takeIf { it >= 0 } ?: 0
        selectCategory(startIndex)

        // Restore the last channel and play it in the preview.
        val lastItem = graph.prefs.lastLiveItem
        val index = if (lastItem != null) graph.repo.indexOf(p.id, ContentType.LIVE, categoryKey, lastItem) else -1
        if (index >= 0) {
            val row = graph.repo.page(p.id, ContentType.LIVE, categoryKey, index, 1).firstOrNull()
            if (row != null) play(index, row)
            focusChannel(index)
        } else {
            focusChannel(0)
        }
        categoriesView.scrollToPosition(startIndex)
    }

    private fun onCategoryFocused(index: Int) {
        if (index == categoryIndex) return
        // Debounce: holding Down through the category list must not run a query per row.
        pendingCategory?.cancel()
        pendingCategory = scope.launch {
            delay(300)
            selectCategory(index)
        }
    }

    private fun onCategoryClicked(index: Int) {
        pendingCategory?.cancel()
        scope.launch {
            if (index != categoryIndex) selectCategory(index)
            focusChannel(0)
        }
    }

    private suspend fun selectCategory(index: Int) {
        val p = playlist ?: return
        val cat = categories.getOrNull(index) ?: return
        categoryIndex = index
        categoryKey = cat.key
        categoryAdapter.selected = index
        categoryJob?.cancel()
        val count = graph.repo.count(p.id, ContentType.LIVE, cat.key)
        channelCount = count
        categoryTitle.text = "${cat.name}  ($count)"
        empty.visibility = if (count == 0) View.VISIBLE else View.GONE
        val key = cat.key
        channelAdapter.reset(count) { offset, limit -> graph.repo.page(p.id, ContentType.LIVE, key, offset, limit) }
        channelAdapter.playingItemId = playingRow?.itemId
        channelsView.scrollToPosition(0)
    }

    private fun focusChannel(index: Int) {
        if (channelCount == 0) return
        val i = index.coerceIn(0, channelCount - 1)
        (channelsView.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(i, channelsView.height / 3)
        channelsView.post {
            channelsView.findViewHolderForAdapterPosition(i)?.itemView?.requestFocus()
        }
    }

    // ---- playback ----

    private fun onChannelClicked(index: Int, row: EntryRow) {
        if (row.itemId == playingRow?.itemId && graph.player.playingUrl != null) {
            enterFullscreen()
        } else {
            play(index, row)
        }
    }

    private fun play(index: Int, row: EntryRow) {
        val url = row.streamUrl ?: urls?.live(row.itemId, liveExt) ?: return
        playingIndex = index
        playingRow = row
        channelAdapter.playingItemId = row.itemId
        nowName.text = row.name
        graph.player.play(url)
        graph.prefs.lastLiveCategory = categoryKey
        graph.prefs.lastLiveItem = row.itemId
        if (fullscreen) showBanner()
    }

    private fun zap(delta: Int) {
        val p = playlist ?: return
        if (channelCount == 0) return
        val base = if (playingIndex >= 0) playingIndex else 0
        val next = ((base + delta) % channelCount + channelCount) % channelCount
        val cached = channelAdapter.rowAt(next)
        if (cached != null) {
            play(next, cached)
            return
        }
        scope.launch {
            val row = graph.repo.page(p.id, ContentType.LIVE, categoryKey, next, 1).firstOrNull() ?: return@launch
            play(next, row)
        }
    }

    private fun toggleFavourite(index: Int, row: EntryRow) {
        val p = playlist ?: return
        scope.launch {
            val fav = graph.repo.toggleFavourite(p.id, ContentType.LIVE, row.itemId)
            activity.toast(activity.getString(if (fav) R.string.favourite_added else R.string.favourite_removed))
            if (categoryKey == Repository.KEY_FAV) {
                selectCategory(categoryIndex)
            } else {
                channelAdapter.setFavourite(index, fav)
            }
        }
    }

    // ---- full screen ----

    private fun enterFullscreen() {
        fullscreen = true
        panels.visibility = View.INVISIBLE
        activity.setVideoRect(null)
        // The root only takes focus in full screen, so it never steals D-pad focus from the lists.
        root.isFocusable = true
        root.requestFocus()
        showBanner()
    }

    private fun exitFullscreen() {
        fullscreen = false
        banner.visibility = View.GONE
        osdStatus.visibility = View.GONE
        panels.visibility = View.VISIBLE
        root.isFocusable = false
        preview.post { placeVideoInPreview() }
        if (playingIndex >= 0) focusChannel(playingIndex)
    }

    private fun showBanner() {
        val row = playingRow ?: return
        bannerName.text = "${playingIndex + 1}  ${row.name}"
        bannerInfo.text = categories.getOrNull(categoryIndex)?.name.orEmpty()
        banner.visibility = View.VISIBLE
        handler.removeCallbacks(hideBanner)
        handler.postDelayed(hideBanner, 4_000)
    }

    private fun placeVideoInPreview() {
        if (fullscreen || preview.width == 0) return
        val loc = IntArray(2)
        preview.getLocationInWindow(loc)
        val parentLoc = IntArray(2)
        (activity.playerView.parent as View).getLocationInWindow(parentLoc)
        val left = loc[0] - parentLoc[0]
        val top = loc[1] - parentLoc[1]
        activity.setVideoRect(Rect(left, top, left + preview.width, top + preview.height))
    }
}
