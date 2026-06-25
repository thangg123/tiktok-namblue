package com.namblue.live

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import com.namblue.live.databinding.ActivityChooserBinding

/**
 * Launcher screen: a black screen with two D-pad-focusable buttons (TikTok / Facebook).
 * Pick one with the remote's OK button to open the fullscreen player for that source.
 *
 * No touch is assumed — this is built for an Android TV remote (D-pad + OK). The TikTok
 * button is focused by default; LEFT/RIGHT moves between them.
 */
class ChooserActivity : Activity() {

    private lateinit var binding: ActivityChooserBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChooserBinding.inflate(layoutInflater)
        setContentView(binding.root)
        goFullscreen()

        binding.btnTiktok.setOnClickListener { startActivity(Intent(this, PlayerActivity::class.java)) }
        binding.btnFacebook.setOnClickListener { startActivity(Intent(this, FacebookWebActivity::class.java)) }
        binding.btnTiktok.requestFocus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            goFullscreen()
            // A TV screen must always show where the cursor is — keep a button focused.
            if (currentFocus == null) binding.btnTiktok.requestFocus()
        }
    }

    @Suppress("DEPRECATION")
    private fun goFullscreen() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }
}
