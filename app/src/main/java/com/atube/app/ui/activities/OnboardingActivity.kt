package com.atube.app.ui.activities

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.atube.app.R
import com.atube.app.constants.PreferenceKeys
import com.atube.app.databinding.ActivityOnboardingBinding
import com.atube.app.helpers.PreferenceHelper

/**
 * First run: the permissions the app actually needs, and the country whose feed
 * the user wants. Both are answered here, before the app opens, so nothing has to
 * be hunted for in settings afterwards.
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    private var selectedRegion: String = "sys"

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshButtons()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupCountry()

        binding.storageButton.setOnClickListener { requestStorage() }
        binding.installButton.setOnClickListener {
            // the app updates itself, which Android only allows once the user has
            // said yes to installing from this source
            runCatching {
                startActivity(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        android.net.Uri.parse("package:$packageName")
                    )
                )
            }
        }
        binding.notificationButton.setOnClickListener { requestNotifications() }

        binding.finishButton.setOnClickListener {
            PreferenceHelper.putString(PreferenceKeys.REGION, selectedRegion)
            PreferenceHelper.putBoolean(PREF_ONBOARDED, true)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }

        refreshButtons()
    }

    private fun setupCountry() {
        val codes = resources.getStringArray(R.array.region_values)
        val names = resources.getStringArray(R.array.region_entries)
        binding.countryInput.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
        )
        val current = PreferenceHelper.getString(PreferenceKeys.REGION, "sys")
        val index = codes.indexOf(current).takeIf { it >= 0 } ?: 0
        selectedRegion = codes[index]
        binding.countryInput.setText(names[index], false)

        binding.countryInput.setOnItemClickListener { _, _, position, _ ->
            selectedRegion = codes.getOrElse(position) { "sys" }
        }
    }

    private fun requestStorage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // nothing to ask for on 13+: downloads go to app storage
            refreshButtons()
            return
        }
        permissionLauncher.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        } else {
            refreshButtons()
        }
    }

    private fun refreshButtons() {
        val storageGranted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        val installAllowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            packageManager.canRequestPackageInstalls()
        } else true
        binding.installButton.isVisible = !installAllowed

        binding.storageButton.isVisible = !storageGranted
        binding.notificationButton.isVisible = !notificationsGranted
    }

    companion object {
        const val PREF_ONBOARDED = "onboarding_complete"
    }
}
