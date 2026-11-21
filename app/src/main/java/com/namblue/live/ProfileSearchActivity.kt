package com.namblue.live

import android.app.Activity
import android.app.Dialog
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * "Trang cá nhân": find a TikTok account and open its profile.
 *
 * A typed username opens straight away (no login needed); free text is searched on TikTok's
 * user search, which only answers logged-in visitors. Opened profiles are kept under
 * "Đã xem gần đây"; holding OK on one asks to remove it.
 */
class ProfileSearchActivity : Activity() {

    private lateinit var binding: ActivityProfileSearchBinding
    private lateinit var reader: LivePageReader
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val avatars by lazy { AvatarLoader(targetPx = dp(AVATAR_DP)) }
    private var searchJob: Job? = null
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

    private fun find() {
        val query = binding.queryInput.text?.toString()?.trim().orEmpty()
        if (query.isEmpty()) {
            Toast.makeText(this, R.string.profile_search_hint, Toast.LENGTH_SHORT).show()
            return
        }
        val handle = UsernameActivity.normalizeUsername(query).takeIf(ProfileDirectory::isUsername)
        binding.resultGrid.removeAllViews()
        handle?.let { addCard(ItemLiveChannelBinding.inflate(layoutInflater, binding.resultGrid, false), directHit(it)) }

        if (!reader.isLoggedIn()) {
            binding.statusText.text = ""
            if (handle != null) {
                binding.resultGrid.getChildAt(0)?.requestFocus()
            } else {
                promptLogin()
            }
            return
        }
        search(query, keepFirst = handle != null)
    }

    private fun directHit(username: String) =
        ProfileHit(username, getString(R.string.profile_open_direct), avatarUrl = null, followers = null)

    private fun search(query: String, keepFirst: Boolean) {
        val url = ProfileDirectory.userSearchUrl(query) ?: return
        searchJob?.cancel()
        binding.statusText.setText(R.string.profile_searching)
        searchJob = uiScope.launch {
            val hits = withTimeoutOrNull(SEARCH_TIMEOUT_MS) { searchResults(url) }.orEmpty()
            val shown = if (keepFirst) hits.filterNot { it.username.equals(directUsername(), ignoreCase = true) } else hits
            shown.forEach { addCard(ItemLiveChannelBinding.inflate(layoutInflater, binding.resultGrid, false), it) }
            binding.statusText.text = if (shown.isEmpty() && !keepFirst) {
                getString(R.string.profile_no_results)
            } else {
                getString(R.string.profile_results, binding.resultGrid.childCount)
            }
            binding.resultGrid.getChildAt(0)?.requestFocus()
        }
    }

    private fun directUsername(): String? =
        UsernameActivity.normalizeUsername(binding.queryInput.text?.toString().orEmpty())

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

    private fun promptLogin() {
        dialog = TvDialogs.confirm(this, getString(R.string.profile_search_needs_login), getString(R.string.login)) {
            startActivity(WebPlayerActivity.intent(this, WebPlayerActivity.LOGIN_URL))
        }
    }

    private fun addCard(item: ItemLiveChannelBinding, hit: ProfileHit) {
        item.avatar.clipToOutline = true
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
        item.root.layoutParams = GridLayout.LayoutParams().apply {
            width = cardWidth
            val gap = dp(CARD_GAP_DP)
            setMargins(gap, gap, gap, gap)
        }
        item.root.setOnClickListener { open(hit.username) }
        binding.resultGrid.addView(item.root)
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
        const val POLL_INTERVAL_MS = 1_200L
        const val AVATAR_DP = 60
        const val CARD_GAP_DP = 6
        const val CHIP_TEXT_SP = 18f
        const val CHIP_HEIGHT_DP = 52
        const val CHIP_PAD_H_DP = 22
        const val CHIP_GAP_DP = 12
    }
}
