package com.namblue.live

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.widget.Button
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
 * OK on a post opens [PostPlayerActivity] at that post.
 *
 * Opens instantly from TikTok's creator embed ([ProfileEmbed], latest posts, no bot check).
 * Older posts come from the full page ([ProfileDirectory]): loaded automatically when logged in
 * to TikTok, otherwise offered through a "Xem bài cũ hơn" tile that asks for a login first.
 */
class ProfileActivity : Activity() {

    private lateinit var binding: ActivityProfileBinding
    private lateinit var reader: LivePageReader
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val images by lazy { AvatarLoader(targetPx = dp(THUMB_WIDTH_DP)) }
    private val username: String by lazy { intent.getStringExtra(EXTRA_USERNAME).orEmpty() }

    private var posts: List<ProfilePost> = emptyList()
    /** True once the full page (WebView) is in use, which is what can load older posts. */
    private var webMode = false
    private var olderCard: View? = null
    private var hasMore = true
    private var loading = false
    private var loadJob: Job? = null
    private var moreJob: Job? = null
    /** Set while the user is away logging in or solving TikTok's check; reload on return. */
    private var awayToUnlock = false

    /** Set while the user is away on the login page opened by "Xem bài cũ hơn". */
    private var awayToLogin = false
    private var dialog: Dialog? = null

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
        // Back from logging in or solving the check: the full page may now show its posts.
        if (awayToUnlock) {
            awayToUnlock = false
            loadFromWeb()
        }
        if (awayToLogin) {
            awayToLogin = false
            if (reader.isLoggedIn()) loadFromWeb() else binding.statusText.setText(R.string.profile_older_not_logged_in)
        }
    }

    override fun onStop() {
        if (::reader.isInitialized) reader.pause()
        super.onStop()
    }

    override fun onDestroy() {
        dialog?.dismiss()
        uiScope.cancel()
        if (::reader.isInitialized) reader.destroy()
        super.onDestroy()
    }

    // --- Loading ----------------------------------------------------------------------------
    //
    // First choice is TikTok's creator embed: one plain request, no bot check, latest ~10 posts.
    // Older posts (and creators who block embedding) need the full page in the hidden WebView,
    // which TikTok may guard with a login wall or a captcha — so that only runs on request.

    private fun load() {
        loadJob?.cancel()
        moreJob?.cancel()
        posts = emptyList()
        webMode = false
        hasMore = true
        binding.grid.removeAllViews()
        hideAction()
        binding.statusText.setText(R.string.profile_loading)
        loadJob = uiScope.launch {
            when (val result = ProfileEmbed.load(username)) {
                is ProfileEmbed.Result.Ok -> {
                    showHeader(result.header)
                    showPosts(result.posts)
                    if (reader.isLoggedIn()) {
                        // Logged in: bring in the older posts straight away, no button needed.
                        loadFromWeb(quietly = true)
                    } else {
                        binding.grid.addView(olderPostsCard())
                        if (result.posts.isEmpty()) binding.grid.getChildAt(0)?.requestFocus()
                    }
                }
                ProfileEmbed.Result.Unavailable -> {
                    binding.statusText.setText(R.string.profile_embed_unavailable)
                    showAction(getString(R.string.profile_open_full)) { requestFullPage() }
                }
                ProfileEmbed.Result.Unreadable -> {
                    binding.statusText.setText(R.string.profile_failed)
                    showAction(getString(R.string.profile_open_full)) { requestFullPage() }
                }
            }
        }
    }

    /**
     * The full page is what TikTok guards with a captcha, far less so for logged-in visitors —
     * so ask for a (one-time, QR) login first, and load right after it.
     */
    private fun requestFullPage() {
        if (reader.isLoggedIn()) {
            loadFromWeb()
            return
        }
        dialog = TvDialogs.confirm(this, getString(R.string.profile_older_login_prompt), getString(R.string.login)) {
            awayToLogin = true
            startActivity(WebPlayerActivity.intent(this, WebPlayerActivity.LOGIN_URL))
        }
    }

    /**
     * Switch to the full profile page for older posts; it may ask for login or a captcha.
     * [quietly] = started automatically behind the latest posts, so a captcha prompt must not
     * pull the cursor away from the grid the user is browsing.
     */
    private fun loadFromWeb(quietly: Boolean = false) {
        loadJob?.cancel()
        webMode = true
        loading = true
        hideAction()
        removeOlderPostsCard()
        binding.statusText.setText(R.string.profile_loading_full)
        loadJob = uiScope.launch {
            val raw = withTimeoutOrNull(LOAD_TIMEOUT_MS) { firstScreen() }
            loading = false
            val header = ProfileDirectory.parseHeader(raw)
            if (posts.isEmpty()) header?.let(::showHeader)
            val fresh = newPosts(ProfileDirectory.parsePosts(raw))
            when {
                fresh.isNotEmpty() -> appendPosts(fresh)
                header?.needsVerification == true -> showVerificationNeeded(takeFocus = !quietly)
                header?.needsLogin == true -> showLoginNeeded()
                raw == null -> binding.statusText.setText(R.string.profile_failed)
                else -> loadMore() // the full page starts with the posts we already show
            }
        }
    }

    /** Poll until the grid settles (enough posts, or no growth between polls) or a wall shows. */
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

    /** Full-page mode only: scroll the page so TikTok appends older posts. */
    private fun loadMore() {
        if (!webMode || !hasMore || loading || moreJob?.isActive == true) return
        moreJob = uiScope.launch {
            reader.scrollForMore()
            val fresh = newPosts(ProfileDirectory.parsePosts(reader.read(ProfileDirectory.SCRAPE_JS)))
            if (fresh.isEmpty()) {
                hasMore = false
                binding.statusText.text = getString(R.string.profile_no_more, posts.size)
                return@launch
            }
            appendPosts(fresh)
        }
    }

    private fun newPosts(found: List<ProfilePost>): List<ProfilePost> {
        val known = posts.map { it.id }.toSet()
        return found.filterNot { it.id in known }
    }

    private fun appendPosts(fresh: List<ProfilePost>) {
        val firstNew = posts.size
        fresh.forEachIndexed { offset, post -> binding.grid.addView(card(post, firstNew + offset)) }
        posts = posts + fresh
        updateCount()
        val focus = currentFocus
        if (focus == null || !focus.isShown) binding.grid.getChildAt(firstNew)?.requestFocus()
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
        showAction(getString(R.string.login)) { openToUnlock(WebPlayerActivity.LOGIN_URL) }
    }

    /** TikTok wants a captcha solved first; open the page on screen so the user can do it. */
    private fun showVerificationNeeded(takeFocus: Boolean = true) {
        binding.statusText.setText(R.string.profile_needs_verification)
        showAction(getString(R.string.verify), takeFocus) { openToUnlock(ProfileDirectory.profileUrl(username)) }
    }

    private fun openToUnlock(url: String) {
        awayToUnlock = true
        startActivity(WebPlayerActivity.intent(this, url))
    }

    private fun showAction(label: String, takeFocus: Boolean = true, onClick: () -> Unit) {
        binding.btnLogin.text = label
        binding.btnLogin.setOnClickListener { onClick() }
        binding.btnLogin.visibility = View.VISIBLE
        if (takeFocus || currentFocus == null) binding.btnLogin.requestFocus()
    }

    private fun hideAction() {
        binding.btnLogin.visibility = View.GONE
    }

    /** Last tile of the embed grid: asks for older posts from the full page. */
    private fun olderPostsCard(): View = Button(this).apply {
        text = getString(R.string.profile_older_posts)
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, OLDER_TEXT_SP)
        setTextColor(getColorStateList(R.color.btn_chooser_text))
        setBackgroundResource(R.drawable.btn_chooser_bg)
        layoutParams = GridLayout.LayoutParams().apply {
            width = cardWidth
            height = dp(OLDER_CARD_HEIGHT_DP)
            val gap = dp(CARD_GAP_DP)
            setMargins(gap, gap, gap, gap)
        }
        setOnClickListener { requestFullPage() }
    }.also { olderCard = it }

    private fun removeOlderPostsCard() {
        olderCard?.let(binding.grid::removeView)
        olderCard = null
    }

    private fun showPosts(found: List<ProfilePost>) {
        posts = found
        found.forEachIndexed { index, post -> binding.grid.addView(card(post, index)) }
        updateCount()
        binding.gridScroll.scrollTo(0, 0)
        binding.grid.getChildAt(0)?.requestFocus()
    }

    private fun updateCount() {
        val text = if (webMode) R.string.profile_posts else R.string.profile_posts_latest
        binding.statusText.text = getString(text, posts.size)
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
        private const val OLDER_CARD_HEIGHT_DP = 194
        private const val OLDER_TEXT_SP = 18f

        fun intent(context: Context, username: String): Intent =
            Intent(context, ProfileActivity::class.java).putExtra(EXTRA_USERNAME, username)
    }
}
