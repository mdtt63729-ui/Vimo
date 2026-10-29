package com.atube.app.ui.activities

import android.os.Bundle
import com.atube.app.databinding.ActivityHelpBinding
import com.atube.app.helpers.IntentHelper
import com.atube.app.ui.base.BaseActivity
import com.google.android.material.card.MaterialCardView

class HelpActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val binding = ActivityHelpBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        setupCard(binding.faq, FAQ_URL)
        setupCard(binding.matrix, MATRIX_URL)
        binding.mastodon.visibility = android.view.View.GONE
        binding.lemmy.visibility = android.view.View.GONE
    }

    private fun setupCard(card: MaterialCardView, link: String) {
        card.setOnClickListener {
            IntentHelper.openLinkFromHref(this, supportFragmentManager, link)
        }
    }

    companion object {
        private const val FAQ_URL = "https://a-tube.app/#faq"
        private const val MATRIX_URL = "https://matrix.to/#/#A-Tube:matrix.org"
        private const val MASTODON_URL = ""
        private const val LEMMY_URL = ""
    }
}
