package com.namblue.live

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import com.namblue.live.databinding.ActivityUsernameBinding

/**
 * "Other" screen: type any TikTok username to watch that account's live instead of NamBlue.
 *
 * Only the bare handle is needed — [normalizeUsername] strips a leading `@`, a trailing `/live`,
 * and even a full pasted profile URL down to just the username before launching [PlayerActivity].
 */
class UsernameActivity : Activity() {

    private lateinit var binding: ActivityUsernameBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityUsernameBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)

        binding.usernameInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                watch()
                true
            } else {
                false
            }
        }
        binding.btnWatch.setOnClickListener { watch() }
        binding.usernameInput.requestFocus()
    }

    private fun watch() {
        val username = normalizeUsername(binding.usernameInput.text?.toString().orEmpty())
        if (username.isBlank()) {
            Toast.makeText(this, R.string.enter_username_hint, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(
            Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_USERNAME, username),
        )
        finish()
    }

    companion object {
        /** Reduce any of `name`, `@name`, `name/live`, `https://tiktok.com/@name/live` to `name`. */
        fun normalizeUsername(raw: String): String {
            var s = raw.trim()
            if (s.contains('@')) s = s.substringAfterLast('@')
            s = s.substringBefore('/')
            return s.trim()
        }
    }
}
