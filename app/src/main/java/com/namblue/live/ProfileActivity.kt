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
        reader = LivePageReader(binding.scrapeView).also {
            it.setUp()
            // Keep a copy of the post lists the full page fetches: every cover, count and type.
            it.addStartScript(ProfileFeed.CAPTURE_JS)
        }
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
            val screen = withTimeoutOrNull(LOAD_TIMEOUT_MS) { firstScreen() }
            loading = false
            val header = ProfileDirectory.parseHeader(screen?.raw)
            if (posts.isEmpty()) header?.let(::showHeader)
            screen?.hasMore?.let { hasMore = it }
            screen?.let { enrich(it.posts) }
            val fresh = newPosts(screen?.posts.orEmpty())
            when {
                fresh.isNotEmpty() -> appendPosts(fresh)
                header?.needsVerification == true -> showVerificationNeeded(takeFocus = !quietly)
                header?.needsLogin == true -> showLoginNeeded()
                screen == null -> binding.statusText.setText(R.string.profile_failed)
                else -> loadMore() // the full page starts with the posts we already show
            }
        }
    }

    /** What the full page shows: its raw dump (for the header and walls) and its posts. */
    private class WebScreen(val raw: String?, val posts: List<ProfilePost>, val hasMore: Boolean?)

    /**
     * The page's posts: from its captured post-list data when there is some (covers for every
     * post, an exact end-of-list flag), else from the rendered grid.
     */
    private suspend fun readWebScreen(): WebScreen {
        val raw = reader.read(ProfileDirectory.SCRAPE_JS)
        val feed = ProfileFeed.parse(reader.read(ProfileFeed.READ_JS))
        return if (feed.posts.isNotEmpty()) {
            WebScreen(raw, feed.posts, feed.hasMore)
        } else {
            WebScreen(raw, ProfileDirectory.parsePosts(raw), hasMore = null)
        }
    }

    /** Poll until the posts settle (enough of them, or no growth between polls) or a wall shows. */
    private suspend fun firstScreen(): WebScreen {
        reader.open(ProfileDirectory.profileUrl(username))
        var previous = -1
        while (true) {
            delay(POLL_INTERVAL_MS)
            val screen = readWebScreen()
            val header = ProfileDirectory.parseHeader(screen.raw)
            if (header?.needsLogin == true || header?.needsVerification == true) return screen
            val count = screen.posts.size
            if (count > 0 && (count >= FIRST_SCREEN_TARGET || count == previous)) return screen
            previous = count
        }
    }

    /**
     * Full-page mode only: scroll the page so TikTok fetches the next posts, then wait for them —
     * a page of posts can take several seconds. Only TikTok's own "no more" flag ends the list;
     * a slow page just leaves it to be tried again on the next visit to the last row.
     */
    private fun loadMore() {
        if (!webMode || !hasMore || loading || moreJob?.isActive == true) return
        binding.statusText.setText(R.string.profile_loading_full)
        moreJob = uiScope.launch {
            reader.scrollForMore()
            repeat(MORE_ATTEMPTS) {
                val screen = readWebScreen()
                screen.hasMore?.let { hasMore = it }
                enrich(screen.posts)
                val fresh = newPosts(screen.posts)
                if (fresh.isNotEmpty()) {
                    appendPosts(fresh)
                    return@launch
                }
                if (!hasMore) {
                    binding.statusText.text = getString(R.string.profile_no_more, posts.size)
                    return@launch
                }
                delay(POLL_INTERVAL_MS)
            }
            updateCount()
        }
    }

    /**
     * The first posts came from the embed, which has no album pictures; once the logged-in post
     * list arrives, give those posts its pictures and music so the player can show the albums.
     */
    private fun enrich(found: List<ProfilePost>) {
        val details = found.filter { it.images.isNotEmpty() || it.musicUrl != null }.associateBy { it.id }
        if (details.isEmpty()) return
        posts = posts.map { post ->
            details[post.id]?.let { post.copy(images = it.images, musicUrl = it.musicUrl) } ?: post
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
        private const val MORE_ATTEMPTS = 6
        private const val THUMB_WIDTH_DP = 140
        private const val CARD_GAP_DP = 6
        private const val OLDER_CARD_HEIGHT_DP = 194
        private const val OLDER_TEXT_SP = 18f

        fun intent(context: Context, username: String): Intent =
            Intent(context, ProfileActivity::class.java).putExtra(EXTRA_USERNAME, username)
    }
}
