package com.namblue.live

import android.app.Activity
import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import com.namblue.live.databinding.ActivityUsernameBinding

/**
 * "Other" screen: type any TikTok username to watch that account's live instead of NamBlue.
 *
 * Only the bare handle is needed — [normalizeUsername] strips a leading `@`, a trailing `/live`,
 * and even a full pasted profile URL down to just the username before launching [PlayerActivity].
 *
 * Every username watched is saved in [WatchHistory] and shown as a row of buttons under "Xem":
 * OK on one re-opens that live, holding OK (long-press) asks to remove it from the history.
 */
class UsernameActivity : Activity() {

    private lateinit var binding: ActivityUsernameBinding

    /** The open "remove from history?" popup, kept so it can be closed with the screen. */
    private var confirmDialog: Dialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUsernameBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.usernameInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                watchTyped()
                true
            } else {
                false
            }
        }
        binding.btnWatch.setOnClickListener { watchTyped() }

        val history = WatchHistory.load(this)
        renderHistory(history)
        if (history.isEmpty()) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
            binding.usernameInput.requestFocus()
        } else {
            // The on-screen keyboard would cover the history row, so start on the newest entry;
            // UP reaches the input when a new name needs typing.
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN)
            binding.historyList.getChildAt(0)?.requestFocus()
        }
    }

    override fun onDestroy() {
        confirmDialog?.dismiss()
        confirmDialog = null
        super.onDestroy()
    }

    private fun watchTyped() {
        val username = normalizeUsername(binding.usernameInput.text?.toString().orEmpty())
        if (username.isBlank()) {
            Toast.makeText(this, R.string.enter_username_hint, Toast.LENGTH_SHORT).show()
            return
        }
        watch(username)
    }

    private fun watch(username: String) {
        WatchHistory.record(this, username)
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_USERNAME, username),
        )
        finish()
    }

    /** Ask before removing [username]; only the explicit "Xoá" button deletes. */
    private fun confirmDelete(username: String, index: Int) {
        confirmDialog = TvDialogs.confirm(
            activity = this,
            message = getString(R.string.history_confirm_delete, username),
            confirmLabel = getString(R.string.delete),
        ) { deleteFromHistory(username, index) }.apply { setOnDismissListener { confirmDialog = null } }
    }

    private fun deleteFromHistory(username: String, index: Int) {
        val history = WatchHistory.delete(this, username)
        renderHistory(history)
        Toast.makeText(this, getString(R.string.history_deleted, username), Toast.LENGTH_SHORT).show()
        // Keep the D-pad cursor visible: the neighbour that slid into place, else the Watch button.
        val next = binding.historyList.getChildAt(index.coerceAtMost(history.size - 1))
        (next ?: binding.btnWatch).requestFocus()
    }

    private fun renderHistory(history: List<String>) {
        binding.historyList.removeAllViews()
        history.forEachIndexed { index, username ->
            binding.historyList.addView(historyButton(username, index))
        }
        binding.historySection.visibility = if (history.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun historyButton(username: String, index: Int): Button =
        Button(this).apply {
            text = getString(R.string.history_item, username)
            isAllCaps = false
            isFocusable = true
            setTextSize(TypedValue.COMPLEX_UNIT_SP, HISTORY_TEXT_SP)
            setTextColor(getColorStateList(R.color.btn_chooser_text))
            setBackgroundResource(R.drawable.btn_chooser_bg)
            val padH = dp(HISTORY_PAD_H_DP)
            setPadding(padH, 0, padH, 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(HISTORY_HEIGHT_DP),
            ).apply { marginEnd = dp(HISTORY_GAP_DP) }
            setOnClickListener { watch(username) }
            setOnLongClickListener {
                confirmDelete(username, index)
                true
            }
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val HISTORY_TEXT_SP = 22f
        private const val HISTORY_HEIGHT_DP = 64
        private const val HISTORY_PAD_H_DP = 28
        private const val HISTORY_GAP_DP = 16

        /** Reduce any of `name`, `@name`, `name/live`, `https://tiktok.com/@name/live` to `name`. */
        fun normalizeUsername(raw: String): String {
            var s = raw.trim()
            if (s.contains('@')) s = s.substringAfterLast('@')
            s = s.substringBefore('/')
            return s.trim()
        }
    }
}
