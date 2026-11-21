package com.namblue.live

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.GridLayout
import com.namblue.live.databinding.ActivityProfileBinding
import com.namblue.live.databinding.ItemProfilePostBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One creator's profile: header plus their videos and photo posts as a D-pad grid.
 * OK on a post opens [PostPlayerActivity] at that post; reaching the last row loads more.
 * Profiles TikTok hides from logged-out viewers show a login button instead.
 */
class ProfileActivity : Activity() {

    private lateinit var binding: ActivityProfileBinding
    private lateinit var reader: LivePageReader
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val images by lazy { AvatarLoader(targetPx = dp(THUMB_WIDTH_DP)) }
    private val username: String by lazy { intent.getStringExtra(EXTRA_USERNAME).orEmpty() }

    private var posts: List<ProfilePost> = emptyList()
    private var hasMore = true
    private var loading = false
    private var loadJob: Job? = null
    private var moreJob: Job? = null
    /** Set while the user is away logging in or solving TikTok's check; reload on return. */
    private var awayToUnlock = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProfileBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (username.isBlank()) {
            finish()
            return
        }
        reader = LivePageReader(binding.scrapeView).also { it.setUp() }
        WatchHistory.record(this, username, WatchHistory.PROFILES)
        binding.username.text = getString(R.string.history_item, username)
        binding.nickname.text = username
        binding.avatarInitial.text = username.first().uppercase()
        binding.avatar.clipToOutline = true

        load()
    }

    override fun onStart() {
        super.onStart()
        if (::reader.isInitialized) reader.resume()
        // Back from logging in or solving the check: the posts may now be visible.
        if (awayToUnlock) {
            awayToUnlock = false
            load()
        }
    }

    override fun onStop() {
        if (::reader.isInitialized) reader.pause()
        super.onStop()
    }

    override fun onDestroy() {
        uiScope.cancel()
        if (::reader.isInitialized) reader.destroy()
        super.onDestroy()
    }

    // --- Loading ----------------------------------------------------------------------------

    private fun load() {
        loadJob?.cancel()
        moreJob?.cancel()
        posts = emptyList()
        hasMore = true
        loading = true
        binding.grid.removeAllViews()
        binding.btnLogin.visibility = View.GONE
        binding.statusText.setText(R.string.profile_loading)
        loadJob = uiScope.launch {
            val raw = withTimeoutOrNull(LOAD_TIMEOUT_MS) { firstScreen() }
            loading = false
            val header = ProfileDirectory.parseHeader(raw)
            header?.let(::showHeader)
            val found = ProfileDirectory.parsePosts(raw)
            when {
                found.isNotEmpty() -> showPosts(found)
                header?.needsVerification == true -> showVerificationNeeded()
                header?.needsLogin == true -> showLoginNeeded()
                raw == null -> binding.statusText.setText(R.string.profile_failed)
                else -> binding.statusText.setText(R.string.profile_no_posts)
            }
        }
    }

    /** Poll until the grid settles (enough posts, or no growth between polls) or a login wall shows. */
    private suspend fun firstScreen(): String? {
        reader.open(ProfileDirectory.profileUrl(username))
        var previous = -1
        while (true) {
            delay(POLL_INTERVAL_MS)
            val raw = reader.read(ProfileDirectory.SCRAPE_JS)
            val header = ProfileDirectory.parseHeader(raw)
            if (header?.needsLogin == true || header?.needsVerification == true) return raw
            val count = ProfileDirectory.parsePosts(raw).size
            if (count > 0 && (count >= FIRST_SCREEN_TARGET || count == previous)) return raw
            previous = count
        }
    }

    private fun loadMore() {
        if (!hasMore || loading || moreJob?.isActive == true) return
        moreJob = uiScope.launch {
            reader.scrollForMore()
            val known = posts.map { it.id }.toSet()
            val fresh = ProfileDirectory.parsePosts(reader.read(ProfileDirectory.SCRAPE_JS)).filterNot { it.id in known }
            if (fresh.isEmpty()) {
                hasMore = false
                return@launch
            }
            fresh.forEachIndexed { offset, post -> binding.grid.addView(card(post, posts.size + offset)) }
            posts = posts + fresh
            updateCount()
        }
    }

    // --- Rendering --------------------------------------------------------------------------

    private fun showHeader(header: ProfileHeader) {
        header.nickname?.let { binding.nickname.text = it }
        binding.stats.text = if (header.followers != null) {
            getString(R.string.profile_stats, header.followers, header.following ?: "–", header.likes ?: "–")
        } else {
            ""
        }
        binding.bio.text = header.bio.orEmpty()
        header.avatarUrl?.let { url ->
            uiScope.launch {
                images.load(url)?.let {
                    binding.avatar.setImageBitmap(it)
                    binding.avatarInitial.text = ""
                }
            }
        }
    }

    private fun showLoginNeeded() {
        binding.statusText.setText(R.string.profile_needs_login)
        showUnlockButton(getString(R.string.login), WebPlayerActivity.LOGIN_URL)
    }

    /** TikTok wants a captcha solved first; open the page on screen so the user can do it. */
    private fun showVerificationNeeded() {
        binding.statusText.setText(R.string.profile_needs_verification)
        showUnlockButton(getString(R.string.verify), ProfileDirectory.profileUrl(username))
    }

    private fun showUnlockButton(label: String, url: String) {
        binding.btnLogin.text = label
        binding.btnLogin.setOnClickListener {
            awayToUnlock = true
            startActivity(WebPlayerActivity.intent(this, url))
        }
        binding.btnLogin.visibility = View.VISIBLE
        binding.btnLogin.requestFocus()
    }

    private fun showPosts(found: List<ProfilePost>) {
        posts = found
        found.forEachIndexed { index, post -> binding.grid.addView(card(post, index)) }
        updateCount()
        binding.gridScroll.scrollTo(0, 0)
        binding.grid.getChildAt(0)?.requestFocus()
        // A short first screen leaves nothing to scroll to, so fetch more right away.
        if (found.size < binding.grid.columnCount * 2) loadMore()
    }

    private fun updateCount() {
        binding.statusText.text = getString(R.string.profile_posts, posts.size)
    }

    private fun card(post: ProfilePost, index: Int): View {
        val item = ItemProfilePostBinding.inflate(layoutInflater, binding.grid, false)
        item.thumb.clipToOutline = true
        item.views.text = post.views?.let { getString(R.string.post_views, it) }.orEmpty()
        item.views.visibility = if (post.views == null) View.GONE else View.VISIBLE
        item.kind.visibility = if (post.kind == PostKind.PHOTO) View.VISIBLE else View.GONE
        post.thumbUrl?.let { url -> uiScope.launch { images.load(url)?.let(item.thumb::setImageBitmap) } }
        item.root.layoutParams = GridLayout.LayoutParams().apply {
            width = cardWidth
            val gap = dp(CARD_GAP_DP)
            setMargins(gap, gap, gap, gap)
        }
        item.root.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && index >= posts.size - binding.grid.columnCount) loadMore()
        }
        item.root.setOnClickListener { startActivity(PostPlayerActivity.intent(this, posts, index)) }
        return item.root
    }

    /** Fixed card width (a sixth of the content width) so a short grid doesn't stretch. */
    private val cardWidth by lazy {
        val content = resources.displayMetrics.widthPixels - binding.root.getChildAt(1).paddingStart * 2
        content / binding.grid.columnCount - dp(CARD_GAP_DP) * 2
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val EXTRA_USERNAME = "username"
        private const val LOAD_TIMEOUT_MS = 25_000L
        private const val POLL_INTERVAL_MS = 1_200L
        private const val FIRST_SCREEN_TARGET = 12
        private const val THUMB_WIDTH_DP = 140
        private const val CARD_GAP_DP = 6

        fun intent(context: Context, username: String): Intent =
            Intent(context, ProfileActivity::class.java).putExtra(EXTRA_USERNAME, username)
    }
}
