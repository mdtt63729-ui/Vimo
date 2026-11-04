package com.atube.app.ui.activities

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.content.ContextCompat
import com.atube.app.R
import com.atube.app.constants.PreferenceKeys
import com.atube.app.databinding.ActivityOnboardingBinding
import com.atube.app.helpers.PreferenceHelper
import com.atube.app.ui.base.BaseActivity

/**
 * First-run Vimo onboarding. The screen reflects real Android permission state:
 * MediaStore is used for modern Downloads access, notifications are runtime-gated
 * on Android 13+, and package installation is a special per-app access.
 */
class OnboardingActivity : BaseActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    private var selectedRegion: String = "sys"
    private var firstResume = true
    private var permissionFlowInProgress = false
    private var continueToInstallSettingsAfterRuntime = false
    private var lastFolderGranted = false
    private var lastStorageGranted = false
    private var lastInstallGranted = false
    private var lastNotificationsGranted = false

    private val runtimePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            permissionFlowInProgress = false
            refreshPermissionStates(animateChanges = true)
            // The combined Grant permissions action continues into the special app-update access.
            if (continueToInstallSettingsAfterRuntime) {
                continueToInstallSettingsAfterRuntime = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !canInstallPackages()) {
                    openInstallPermissionSettings()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, bars.top, view.paddingRight, bars.bottom)
            insets
        }

        setupCountry()
        setupInteractions()
        refreshPermissionStates(animateChanges = false)

        binding.root.alpha = 0f
        binding.root.translationY = 16f
        binding.root.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(360L)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .start()
    }

    override fun onResume() {
        super.onResume()
        if (!firstResume) {
            refreshPermissionStates(animateChanges = true)
        }
        firstResume = false
    }

    private fun setupInteractions() {
        // Each row is independently actionable and uses the platform selectable ripple.
        binding.storageRow.setOnClickListener { requestStoragePermission() }
        binding.folderRow.setOnClickListener { requestFolderPermission() }
        binding.installRow.setOnClickListener { requestInstallPermission() }
        binding.notificationRow.setOnClickListener { requestNotificationPermission() }

        binding.grantPermissionsButton.setOnClickListener {
            requestAllApplicablePermissions()
        }

        binding.finishButton.setOnClickListener {
            PreferenceHelper.putString(PreferenceKeys.REGION, selectedRegion)
            PreferenceHelper.putBoolean(PREF_ONBOARDED, true)
            closeToHome()
        }
    }

    private fun setupCountry() {
        val codes = resources.getStringArray(R.array.region_values)
        val names = resources.getStringArray(R.array.region_entries)
        binding.countryInput.threshold = 0
        binding.countryInput.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, names)
        )
        binding.countryInput.setOnClickListener { binding.countryInput.showDropDown() }
        val current = PreferenceHelper.getString(PreferenceKeys.REGION, "sys")
        val index = codes.indexOf(current).takeIf { it >= 0 } ?: 0
        selectedRegion = codes[index]
        binding.countryInput.setText(names[index], false)
        binding.countryInput.setOnItemClickListener { _, _, position, _ ->
            selectedRegion = codes.getOrElse(position) { "sys" }
        }
    }

    private fun requestAllApplicablePermissions() {
        if (permissionFlowInProgress) return
        val requests = mutableListOf<String>()

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            !hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        ) {
            requests += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            requests += Manifest.permission.POST_NOTIFICATIONS
        }

        if (requests.isNotEmpty()) {
            permissionFlowInProgress = true
            continueToInstallSettingsAfterRuntime = true
            runtimePermissionLauncher.launch(requests.toTypedArray())
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !canInstallPackages()) {
            openInstallPermissionSettings()
        } else {
            refreshPermissionStates(animateChanges = true)
            Toast.makeText(this, "All available permissions are ready", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestStoragePermission() {
        // Android 11 and up cannot write a public folder at all without All files
        // access, so that row does the asking. Android 7 to 10 use the ordinary
        // storage permission, which is requested here.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            refreshPermissionStates(animateChanges = true)
            return
        }
        if (hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            refreshPermissionStates(animateChanges = true)
        } else if (wasAsked(Manifest.permission.WRITE_EXTERNAL_STORAGE) &&
            !shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        ) {
            openAppSettings()
        } else {
            markAsked(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            runtimePermissionLauncher.launch(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE))
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            refreshPermissionStates(animateChanges = true)
            return
        }
        val permission = Manifest.permission.POST_NOTIFICATIONS
        if (hasPermission(permission)) {
            refreshPermissionStates(animateChanges = true)
        } else if (wasAsked(permission) && !shouldShowRequestPermissionRationale(permission)) {
            openAppSettings()
        } else {
            markAsked(permission)
            runtimePermissionLauncher.launch(arrayOf(permission))
        }
    }

    private fun requestInstallPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || canInstallPackages()) {
            refreshPermissionStates(animateChanges = true)
            return
        }
        openInstallPermissionSettings()
    }

    private fun openInstallPermissionSettings() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName")
                )
            )
        }.onFailure {
            openAppSettings()
        }
    }

    private fun openAppSettings() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun canInstallPackages(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()

    private fun wasAsked(permission: String): Boolean =
        getPreferences(MODE_PRIVATE).getBoolean("asked_$permission", false)

    private fun markAsked(permission: String) {
        getPreferences(MODE_PRIVATE).edit().putBoolean("asked_$permission", true).apply()
    }

    /**
     * Asks for All files access, which is what lets Vimo write a real folder at
     * /storage/emulated/0/Vimo. The folder is created the moment access is granted, so
     * the user sees it appear rather than being told it will.
     */
    private fun requestFolderPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            // Android 7 to 10: the ordinary storage permission is enough, and once it is
            // granted the folder is created here so the row shows the tick.
            if (com.atube.app.helpers.DownloadHelper.hasFolderAccess(this)) {
                com.atube.app.helpers.DownloadHelper.ensureVimoFolder()
                refreshPermissionStates(animateChanges = true)
            } else {
                requestStoragePermission()
            }
            return
        }
        if (android.os.Environment.isExternalStorageManager()) {
            com.atube.app.helpers.DownloadHelper.ensureVimoFolder()
            refreshPermissionStates(animateChanges = true)
            return
        }
        runCatching {
            startActivity(
                android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    android.net.Uri.parse("package:$packageName")
                )
            )
        }.onFailure {
            startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    /** True when the Vimo folder can actually be written. */
    private fun folderReady(): Boolean =
        com.atube.app.helpers.DownloadHelper.hasFolderAccess(this) &&
            com.atube.app.helpers.DownloadHelper.ensureVimoFolder()

    private fun refreshPermissionStates(animateChanges: Boolean) {
        // Vimo writes its own Downloads through MediaStore on Android 10+.
        val storageGranted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            hasPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val installGranted = canInstallPackages()
        val notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            hasPermission(Manifest.permission.POST_NOTIFICATIONS)

        updateCheck(binding.storageStatus, storageGranted, animateChanges && !lastStorageGranted)
        updateCheck(binding.installStatus, installGranted, animateChanges && !lastInstallGranted)
        updateCheck(binding.notificationStatus, notificationsGranted, animateChanges && !lastNotificationsGranted)

        val folderGranted = folderReady()
        updateCheck(binding.folderStatus, folderGranted, animateChanges && !lastFolderGranted)
        lastFolderGranted = folderGranted

        lastStorageGranted = storageGranted
        lastInstallGranted = installGranted
        lastNotificationsGranted = notificationsGranted

        val allReady = storageGranted && installGranted && notificationsGranted
        binding.grantPermissionsButton.text =
            if (allReady) "All permissions ready" else "Grant permissions"
        binding.grantPermissionsButton.isEnabled = !allReady
        binding.grantPermissionsButton.alpha = if (allReady) 0.78f else 1f
        binding.finishButton.isEnabled = allReady
    }

    private fun updateCheck(view: ImageView, granted: Boolean, animate: Boolean) {
        view.contentDescription = if (granted) "Permission ready" else "Permission not granted"
        if (granted) {
            if (view.visibility != View.VISIBLE) {
                view.visibility = View.VISIBLE
                if (animate) {
                    view.alpha = 0f
                    view.scaleX = 0.72f
                    view.scaleY = 0.72f
                    view.animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(300L)
                        .setInterpolator(android.view.animation.OvershootInterpolator(0.8f))
                        .start()
                } else {
                    view.alpha = 1f
                    view.scaleX = 1f
                    view.scaleY = 1f
                }
            }
        } else {
            view.animate().cancel()
            view.visibility = View.INVISIBLE
            view.alpha = 0f
            view.scaleX = 0.72f
            view.scaleY = 0.72f
        }
    }

    private fun closeToHome() {
        binding.root.animate()
            .alpha(0f)
            .translationY(-10f)
            .setDuration(190L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                startActivity(Intent(this, MainActivity::class.java))
                finish()
                overridePendingTransition(R.anim.onboarding_home_enter, R.anim.onboarding_exit)
            }
            .start()
    }

    companion object {
        const val PREF_ONBOARDED = "onboarding_complete"
    }
}
