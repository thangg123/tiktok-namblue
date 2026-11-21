package com.namblue.live

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import androidx.media3.common.util.UnstableApi
import com.namblue.live.databinding.ActivityLiveListBinding
import com.namblue.live.databinding.ItemLiveChannelBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Đang LIVE": browse who is live on TikTok, YouTube-on-TV style.
 *
 * - The channel under the remote's cursor plays (with sound) in the top pane; OK opens it full screen.
 * - Chips switch section: Đề xuất, TikTok's own categories (read off the page), or a keyword search.
 * - Reaching the last row loads more, by scrolling the hidden page so TikTok appends cards.
 */
@UnstableApi
class LiveListActivity : Activity() {

    /** One list the screen can show; [query] is set only for keyword searches. */
    private data class Section(val label: String, val url: String, val includeSidebar: Boolean, val query: String? = null)

    private lateinit var binding: ActivityLiveListBinding
    private lateinit var reader: LivePageReader
    private lateinit var preview: LivePreview
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val avatars by lazy { AvatarLoader(targetPx = dp(AVATAR_DP)) }

    private val recommended by lazy { Section(getString(R.string.section_recommended), LiveDirectory.PAGE_URL, includeSidebar = true) }
    private var categories: List<LiveCategory> = emptyList()
    private var section: Section? = null
    private var channels: List<LiveChannel> = emptyList()
    private var focused: LiveChannel? = null
    private var hasMore = true
    private var loadingSection = false
    private var loadJob: Job? = null
    private var moreJob: Job? = null
    private var dialog: Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLiveListBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.previewFrame.clipToOutline = true
        reader = LivePageReader(binding.scrapeView).also { it.setUp() }
        preview = LivePreview(this, binding.previewView, uiScope, ::showPreviewState, ::onProfile)
        renderChips()
        load(recommended)
    }

    override fun onStart() {
        super.onStart()
        reader.resume()
        preview.start()
        focused?.let {
            preview.reset()
            preview.show(it.username)
        }
    }

    override fun onStop() {
        preview.stop()
        reader.pause()
        super.onStop()
    }

    override fun onDestroy() {
        dialog?.dismiss()
        uiScope.cancel()
        reader.destroy()
        super.onDestroy()
    }

    // --- Sections ---------------------------------------------------------------------------

    private fun load(target: Section) {
        loadJob?.cancel()
        moreJob?.cancel()
        section = target
        channels = emptyList()
        hasMore = true
        binding.grid.removeAllViews()
        clearHero()
        renderChips()
        binding.statusText.setText(R.string.live_list_loading)
        loadingSection = true
        loadJob = uiScope.launch {
            val raw = withTimeoutOrNull(LOAD_TIMEOUT_MS) { firstScreen(target) }
            loadingSection = false
            showChannels(LiveDirectory.parse(raw, target.includeSidebar), target)
            // A short first screen leaves nothing to scroll to, so fetch more right away.
            if (channels.isNotEmpty() && channels.size < binding.grid.columnCount * 2) loadMore()
            if (target == recommended && categories.isEmpty()) readCategories()
        }
    }

    /** The category tabs render after the first channels, so keep looking for a few seconds. */
    private suspend fun readCategories() {
        repeat(CATEGORY_ATTEMPTS) {
            val found = LiveDirectory.parseCategories(reader.read())
            if (found.isNotEmpty()) {
                categories = found.take(MAX_CATEGORIES)
                renderChips()
                return
            }
            delay(POLL_INTERVAL_MS)
        }
    }

    /**
     * Open [target]'s page and poll until its cards have settled: enough to fill the screen, or
     * the count stopped growing between two polls (pages render their cards in waves).
     */
    private suspend fun firstScreen(target: Section): String? {
        reader.open(target.url)
        var previous = 0
        while (true) {
            delay(POLL_INTERVAL_MS)
            val raw = reader.read()
            val count = LiveDirectory.parse(raw, target.includeSidebar).size
            if (count > 0 && (count >= FIRST_SCREEN_TARGET || count == previous)) return raw
            previous = count
        }
    }

    private fun showChannels(found: List<LiveChannel>, target: Section) {
        if (found.isEmpty()) {
            binding.statusText.text = when {
                target.query != null -> getString(R.string.search_empty, target.query)
                else -> getString(R.string.live_list_failed)
            }
            (chips().firstOrNull { it.isSelected } ?: chips().firstOrNull())?.requestFocus()
            return
        }
        channels = found
        found.forEachIndexed { index, channel -> binding.grid.addView(card(channel, index)) }
        updateCount()
        binding.gridScroll.scrollTo(0, 0)
        binding.grid.getChildAt(0)?.requestFocus()
    }

    private fun loadMore() {
        val target = section ?: return
        if (!hasMore || loadingSection || moreJob?.isActive == true) return
        binding.statusText.setText(R.string.live_list_loading_more)
        moreJob = uiScope.launch {
            reader.scrollForMore()
            val known = channels.map { it.username.lowercase() }.toSet()
            val fresh = LiveDirectory.parse(reader.read(), target.includeSidebar)
                .filterNot { it.username.lowercase() in known }
            if (fresh.isEmpty()) {
                hasMore = false
                binding.statusText.text = getString(R.string.live_list_end, channels.size)
                return@launch
            }
            fresh.forEachIndexed { offset, channel -> binding.grid.addView(card(channel, channels.size + offset)) }
            channels = channels + fresh
            updateCount()
        }
    }

    private fun updateCount() {
        val label = section?.label.orEmpty()
        binding.statusText.text = getString(R.string.live_list_section_count, label, channels.size)
    }

    // --- Search -----------------------------------------------------------------------------

    private fun startSearch() {
        if (!reader.isLoggedIn()) {
            dialog = TvDialogs.confirm(this, getString(R.string.search_login_prompt), getString(R.string.login)) {
                startActivity(WebPlayerActivity.intent(this, LOGIN_URL))
            }
            return
        }
        dialog = TvDialogs.search(this, section?.query.orEmpty()) { query ->
            val url = LiveDirectory.searchUrl(query) ?: return@search
            load(Section(getString(R.string.section_search_query, query), url, includeSidebar = false, query = query))
        }
    }

    // --- Chips ------------------------------------------------------------------------------

    private fun renderChips() {
        val active = section
        val focusedLabel = (currentFocus as? Button)?.takeIf { it.parent == binding.chipRow }?.text
        binding.chipRow.removeAllViews()
        val searchLabel = active?.query?.let { active.label } ?: getString(R.string.section_search)
        addChip(searchLabel, selected = active?.query != null) { startSearch() }
        addChip(recommended.label, selected = active == recommended) { load(recommended) }
        categories.forEach { category ->
            val target = Section(category.label, category.url, includeSidebar = false)
            addChip(category.label, selected = active == target) { load(target) }
        }
        focusedLabel?.let { label -> chips().firstOrNull { it.text == label }?.requestFocus() }
    }

    private fun addChip(label: CharSequence, selected: Boolean, onClick: () -> Unit) {
        val chip = Button(this).apply {
            text = label
            isAllCaps = false
            isSelected = selected
            isFocusable = true
            setTextSize(TypedValue.COMPLEX_UNIT_SP, CHIP_TEXT_SP)
            setTextColor(getColorStateList(R.color.chip_text))
            setBackgroundResource(R.drawable.chip_bg)
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(CHIP_PAD_H_DP), 0, dp(CHIP_PAD_H_DP), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(CHIP_HEIGHT_DP))
                .apply { marginEnd = dp(CHIP_GAP_DP) }
            setOnClickListener { onClick() }
        }
        binding.chipRow.addView(chip)
    }

    private fun chips(): List<Button> =
        (0 until binding.chipRow.childCount).mapNotNull { binding.chipRow.getChildAt(it) as? Button }

    // --- Cards & hero -----------------------------------------------------------------------

    private fun card(channel: LiveChannel, index: Int): View {
        val item = ItemLiveChannelBinding.inflate(layoutInflater, binding.grid, false)
        item.avatar.clipToOutline = true
        bindCard(item, channel)
        item.root.layoutParams = GridLayout.LayoutParams().apply {
            width = cardWidth
            val gap = dp(CARD_GAP_DP)
            setMargins(gap, gap, gap, gap)
        }
        // Look the channel up on focus: details may have been filled in since the card was made.
        item.root.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) onCardFocused(channels.getOrNull(index) ?: channel, index)
        }
        item.root.setOnClickListener { watch(channel.username) }
        return item.root
    }

    private fun bindCard(item: ItemLiveChannelBinding, channel: LiveChannel) {
        item.nickname.text = channel.nickname
        item.detail.text = channel.viewers?.let { getString(R.string.live_list_viewers, it) }
            ?: getString(R.string.history_item, channel.username)
        // Until (or unless) a photo arrives, the circle shows the nickname's first letter.
        item.avatarInitial.text = channel.nickname.firstOrNull { it.isLetterOrDigit() }?.uppercase().orEmpty()
        channel.avatarUrl?.let { url ->
            uiScope.launch {
                avatars.load(url)?.let {
                    item.avatar.setImageBitmap(it)
                    item.avatarInitial.text = ""
                }
            }
        }
    }

    /** Fill the previewed card's gaps from the profile the preview's lookup returned. */
    private fun onProfile(username: String, profile: LiveProfile) {
        val index = channels.indexOfFirst { it.username == username }
        if (index < 0) return
        val filled = LiveProfiles.merge(channels[index], profile)
        if (filled == channels[index]) return
        channels = channels.toMutableList().also { it[index] = filled }.toList()
        binding.grid.getChildAt(index)?.let { bindCard(ItemLiveChannelBinding.bind(it), filled) }
        if (focused?.username == username) {
            focused = filled
            showHero(filled)
        }
    }

    private fun onCardFocused(channel: LiveChannel, index: Int) {
        focused = channel
        showHero(channel)
        preview.show(channel.username)
        if (index >= channels.size - binding.grid.columnCount) loadMore()
    }

    private fun showHero(channel: LiveChannel) {
        binding.heroBadge.visibility = View.VISIBLE
        binding.heroHint.visibility = View.VISIBLE
        binding.heroNickname.text = channel.nickname
        binding.heroUsername.text = getString(R.string.history_item, channel.username)
        binding.heroDetail.text = listOfNotNull(
            channel.viewers?.let { getString(R.string.live_list_viewers, it) },
            channel.title,
        ).joinToString("  ·  ")
    }

    private fun clearHero() {
        focused = null
        preview.reset()
        binding.heroBadge.visibility = View.INVISIBLE
        binding.heroHint.visibility = View.INVISIBLE
        binding.heroNickname.text = ""
        binding.heroUsername.text = ""
        binding.heroDetail.text = ""
        binding.previewStatus.text = ""
    }

    private fun showPreviewState(state: LivePreview.State) {
        val text = when (state) {
            LivePreview.State.LOADING -> getString(R.string.preview_loading)
            LivePreview.State.PLAYING -> null
            LivePreview.State.OFFLINE -> getString(R.string.preview_offline)
            LivePreview.State.RESTRICTED -> getString(R.string.preview_restricted)
            LivePreview.State.FAILED -> getString(R.string.preview_failed)
        }
        binding.previewStatus.text = text.orEmpty()
        binding.previewStatus.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    private fun watch(username: String) {
        WatchHistory.record(this, username)
        startActivity(Intent(this, PlayerActivity::class.java).putExtra(PlayerActivity.EXTRA_USERNAME, username))
    }

    /** Fixed card width (a sixth of the content width) so a short list doesn't stretch its cards. */
    private val cardWidth by lazy {
        val content = resources.displayMetrics.widthPixels - binding.root.getChildAt(1).paddingStart * 2
        content / binding.grid.columnCount - dp(CARD_GAP_DP) * 2
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val LOGIN_URL = "https://www.tiktok.com/login/qrcode"
        const val LOAD_TIMEOUT_MS = 25_000L
        const val POLL_INTERVAL_MS = 1_000L
        const val MAX_CATEGORIES = 14
        const val CATEGORY_ATTEMPTS = 8
        const val FIRST_SCREEN_TARGET = 12
        const val AVATAR_DP = 60
        const val CARD_GAP_DP = 6
        const val CHIP_TEXT_SP = 17f
        const val CHIP_HEIGHT_DP = 48
        const val CHIP_PAD_H_DP = 22
        const val CHIP_GAP_DP = 12
    }
}
