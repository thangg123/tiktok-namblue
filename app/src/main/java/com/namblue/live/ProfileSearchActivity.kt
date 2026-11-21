package com.namblue.live

import android.app.Activity
import android.app.Dialog
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.Toast
import com.namblue.live.databinding.ActivityProfileSearchBinding
import com.namblue.live.databinding.ItemLiveChannelBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Trang cá nhân": find a TikTok account and open its profile.
 *
 * Type a name or username: matching accounts appear as cards with photo and followers, found
 * without a login through TikTok's search suggestions ([ProfileSuggest]), plus TikTok's own user
 * search when logged in. Opened profiles are kept under "Đã xem gần đây"; holding OK on one asks
 * to remove it.
 */
class ProfileSearchActivity : Activity() {

    private lateinit var binding: ActivityProfileSearchBinding
    private lateinit var reader: LivePageReader
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val avatars by lazy { AvatarLoader(targetPx = dp(AVATAR_DP)) }
    private var searchJob: Job? = null

    /** Lower-cased usernames already shown for the current search, so none appears twice. */
    private var shown: Set<String> = emptySet()
    private var dialog: Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileSearchBinding.inflate(layoutInflater)
        setContentView(binding.root)
        reader = LivePageReader(binding.scrapeView).also { it.setUp() }

        binding.queryInput.setOnEditorActionListener { _, actionId, _ ->
            val isSubmit = actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE
            if (isSubmit) find()
            isSubmit
        }
        binding.btnFind.setOnClickListener { find() }
    }

    override fun onStart() {
        super.onStart()
        reader.resume()
        // Profiles opened from here are recorded there, so refresh the row on every return.
        renderHistory(WatchHistory.load(this, WatchHistory.PROFILES))
        if (currentFocus == null) {
            (binding.historyList.getChildAt(0) ?: binding.queryInput).requestFocus()
        }
    }

    override fun onStop() {
        reader.pause()
        super.onStop()
    }

    override fun onDestroy() {
        dialog?.dismiss()
        uiScope.cancel()
        reader.destroy()
        super.onDestroy()
    }

    // --- Find -------------------------------------------------------------------------------

    /**
     * Shows accounts matching the typed text: the exact username (if it is one), then accounts
     * guessed from TikTok's search suggestions and confirmed through the creator embed, then —
     * when logged in — TikTok's own user search. Cards appear as each one is confirmed.
     */
    private fun find() {
        val query = binding.queryInput.text?.toString()?.trim().orEmpty()
        if (query.isEmpty()) {
            Toast.makeText(this, R.string.profile_search_hint, Toast.LENGTH_SHORT).show()
            return
        }
        hideKeyboard()
        searchJob?.cancel()
        binding.resultGrid.removeAllViews()
        shown = emptySet()
        binding.statusText.setText(R.string.profile_searching)
        val typed = UsernameActivity.normalizeUsername(query).takeIf(ProfileDirectory::isUsername)
        searchJob = uiScope.launch {
            typed?.let { showDirectHit(it) }
            showSuggested(query)
            if (reader.isLoggedIn()) showTikTokSearch(query)
            binding.statusText.text = if (shown.isEmpty()) {
                getString(R.string.profile_no_results)
            } else {
                getString(R.string.profile_results, shown.size)
            }
        }
    }

    /**
     * The exact-username card, kept even when TikTok won't embed the account (audience controls)
     * since the full page may still open. Photo, name and followers come from the embed — cached,
     * so opening the profile next costs nothing.
     */
    private suspend fun showDirectHit(username: String) {
        val item = addHit(ProfileHit(username, getString(R.string.profile_open_direct), avatarUrl = null, followers = null))
        val header = (ProfileEmbed.load(username) as? ProfileEmbed.Result.Ok)?.header ?: return
        bindHit(item, ProfileHit(username, header.nickname ?: username, header.avatarUrl, header.followers))
    }

    /**
     * Accounts guessed from TikTok's suggestions, checked two at a time, then shown most
     * followed first — a guessed handle is often a small namesake of the account people mean.
     */
    private suspend fun showSuggested(query: String) {
        val candidates = ProfileSuggest.candidates(query, ProfileSuggest.load(query))
            .filterNot { it.lowercase() in shown }
        val found = candidates.chunked(PARALLEL_CHECKS).flatMap { batch ->
            coroutineScope { batch.map { name -> async { name to ProfileEmbed.load(name) } }.awaitAll() }
                .mapNotNull { (name, result) -> (result as? ProfileEmbed.Result.Ok)?.header?.let { name to it } }
        }
        found.sortedByDescending { (_, header) -> header.followerCount ?: 0L }
            .filterNot { (name, _) -> name.lowercase() in shown }
            .forEach { (name, header) -> addHit(ProfileHit(name, header.nickname ?: name, header.avatarUrl, header.followers)) }
    }

    /** TikTok's real user search; only answers logged-in visitors. */
    private suspend fun showTikTokSearch(query: String) {
        val url = ProfileDirectory.userSearchUrl(query) ?: return
        val hits = withTimeoutOrNull(SEARCH_TIMEOUT_MS) { searchResults(url) }.orEmpty()
        hits.filterNot { it.username.lowercase() in shown }.forEach { addHit(it) }
    }

    /** Poll the search page until its result list stops growing. */
    private suspend fun searchResults(url: String): List<ProfileHit> {
        reader.open(url)
        var previous = emptyList<ProfileHit>()
        while (true) {
            delay(POLL_INTERVAL_MS)
            val hits = ProfileDirectory.parseUsers(reader.read(ProfileDirectory.SCRAPE_JS))
            if (hits.isNotEmpty() && hits.size == previous.size) return hits
            previous = hits
        }
    }

    /** The on-screen keyboard covers the results, so put it away once a search starts. */
    private fun hideKeyboard() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(binding.queryInput.windowToken, 0)
        binding.btnFind.requestFocus()
    }

    /** Add a result card; the first one takes the cursor away from the search box. */
    private fun addHit(hit: ProfileHit): ItemLiveChannelBinding {
        shown = shown + hit.username.lowercase()
        val item = addCard(hit)
        if (shown.size == 1 && (currentFocus == binding.queryInput || currentFocus == binding.btnFind)) {
            item.root.requestFocus()
        }
        return item
    }

    private fun addCard(hit: ProfileHit): ItemLiveChannelBinding {
        val item = ItemLiveChannelBinding.inflate(layoutInflater, binding.resultGrid, false)
        item.avatar.clipToOutline = true
        bindHit(item, hit)
        item.root.layoutParams = GridLayout.LayoutParams().apply {
            width = cardWidth
            val gap = dp(CARD_GAP_DP)
            setMargins(gap, gap, gap, gap)
        }
        item.root.setOnClickListener { open(hit.username) }
        binding.resultGrid.addView(item.root)
        return item
    }

    private fun bindHit(item: ItemLiveChannelBinding, hit: ProfileHit) {
        item.nickname.text = hit.nickname
        item.detail.text = listOfNotNull(
            getString(R.string.history_item, hit.username),
            hit.followers?.let { getString(R.string.profile_followers, it) },
        ).joinToString("  ·  ")
        item.avatarInitial.text = hit.username.firstOrNull()?.uppercase().orEmpty()
        hit.avatarUrl?.let { url ->
            uiScope.launch {
                avatars.load(url)?.let {
                    item.avatar.setImageBitmap(it)
                    item.avatarInitial.text = ""
                }
            }
        }
    }

    private fun open(username: String) {
        startActivity(ProfileActivity.intent(this, username))
    }

    // --- History ----------------------------------------------------------------------------

    private fun renderHistory(history: List<String>) {
        binding.historyList.removeAllViews()
        history.forEachIndexed { index, username -> binding.historyList.addView(historyChip(username, index)) }
        binding.historySection.visibility = if (history.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun historyChip(username: String, index: Int): Button = Button(this).apply {
        text = getString(R.string.history_item, username)
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, CHIP_TEXT_SP)
        setTextColor(getColorStateList(R.color.chip_text))
        setBackgroundResource(R.drawable.chip_bg)
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(CHIP_PAD_H_DP), 0, dp(CHIP_PAD_H_DP), 0)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(CHIP_HEIGHT_DP))
            .apply { marginEnd = dp(CHIP_GAP_DP) }
        setOnClickListener { open(username) }
        setOnLongClickListener {
            confirmDelete(username, index)
            true
        }
    }

    private fun confirmDelete(username: String, index: Int) {
        dialog = TvDialogs.confirm(
            activity = this,
            message = getString(R.string.profile_history_confirm_delete, username),
            confirmLabel = getString(R.string.delete),
        ) {
            val history = WatchHistory.delete(this, username, WatchHistory.PROFILES)
            renderHistory(history)
            Toast.makeText(this, getString(R.string.history_deleted, username), Toast.LENGTH_SHORT).show()
            (binding.historyList.getChildAt(index.coerceAtMost(history.size - 1)) ?: binding.queryInput).requestFocus()
        }
    }

    /** Fixed card width (a sixth of the content width) so a short result list doesn't stretch. */
    private val cardWidth by lazy {
        val content = resources.displayMetrics.widthPixels - binding.root.getChildAt(1).paddingStart * 2
        content / binding.resultGrid.columnCount - dp(CARD_GAP_DP) * 2
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val SEARCH_TIMEOUT_MS = 20_000L
        const val PARALLEL_CHECKS = 2
        const val POLL_INTERVAL_MS = 1_200L
        const val AVATAR_DP = 60
        const val CARD_GAP_DP = 6
        const val CHIP_TEXT_SP = 18f
        const val CHIP_HEIGHT_DP = 52
        const val CHIP_PAD_H_DP = 22
        const val CHIP_GAP_DP = 12
    }
}
