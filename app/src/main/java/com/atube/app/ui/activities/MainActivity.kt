package com.atube.app.ui.activities

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewTreeObserver
import android.widget.ScrollView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.widget.SearchView
import androidx.constraintlayout.motion.widget.Key
import androidx.core.content.getSystemService
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.allViews
import androidx.core.view.children
import androidx.core.view.isNotEmpty
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.onNavDestinationSelected
import androidx.navigation.ui.setupWithNavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.recyclerview.widget.RecyclerView
import com.atube.app.BuildConfig
import com.atube.app.NavDirections
import com.atube.app.R
import com.atube.app.api.ytdlp.YtDlpEngine
import com.atube.app.compat.PictureInPictureCompat
import com.atube.app.constants.IntentData
import com.atube.app.constants.PreferenceKeys
import com.atube.app.databinding.ActivityMainBinding
import com.atube.app.db.DatabaseHelper
import com.atube.app.db.obj.SearchHistoryItem
import com.atube.app.enums.ImportFormat
import com.atube.app.enums.SearchType
import com.atube.app.enums.TopLevelDestination
import com.atube.app.extensions.anyChildFocused
import com.atube.app.helpers.ImportHelper
import com.atube.app.helpers.IntentHelper
import com.atube.app.helpers.NavBarHelper
import com.atube.app.helpers.NavigationHelper
import com.atube.app.helpers.NetworkHelper
import com.atube.app.helpers.NetworkQuality
import com.atube.app.helpers.PreferenceHelper
import com.atube.app.helpers.ThemeHelper
import com.atube.app.parcelable.PlayerData
import com.atube.app.ui.dialogs.ErrorDialog
import com.atube.app.ui.dialogs.ImportTempPlaylistDialog
import com.atube.app.ui.extensions.onSystemInsets
import com.atube.app.ui.fragments.DownloadsFragment
import com.atube.app.ui.models.DownloadsViewModel
import com.atube.app.ui.models.PlaylistViewModel
import com.atube.app.ui.models.SearchViewModel
import com.atube.app.ui.models.SubscriptionsViewModel
import com.atube.app.ui.preferences.BackupRestoreSettings
import com.atube.app.ui.preferences.BackupRestoreSettings.Companion.FILETYPE_ANY
import com.atube.app.ui.views.FloatingNavItem
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class MainActivity : AbstractPlayerHostActivity() {
    private lateinit var binding: ActivityMainBinding

    lateinit var navController: NavController
    private var startFragmentId = R.id.homeFragment

    // Rapid-tap protection: prevent duplicate navigation within 200ms
    private var lastNavItemId: Int = -1
    private var lastNavTimeMs: Long = 0L
    private companion object {
        const val NAV_DEBOUNCE_MS = 200L
        const val LOW_NETWORK_WARNING_INTERVAL_MS = 120_000L
        const val LOW_NETWORK_POPUP_VISIBLE_MS = 6_000L
        // Fast, deliberate double tap only. Anything slower is just a second tap.
        const val HOME_DOUBLE_TAP_MS = 280L
    }

    /** Last time Home was tapped, for the double-tap refresh gesture. */
    private var lastHomeTapAt = 0L

    /** Last time the low-network card was shown, so it cannot repeat in a burst. */
    private var lastLowNetworkWarningAt = 0L

    private val subscriptionsViewModel: SubscriptionsViewModel by viewModels()
    private val homeViewModel: com.atube.app.ui.models.HomeViewModel by viewModels()

    // search related stuff
    private lateinit var searchView: SearchView
    private lateinit var searchItem: MenuItem
    private var savedSearchQuery: String? = null
    private var shouldOpenSuggestions = true
    private var currentSearchType: SearchType = SearchType.ONLINE
    private val searchViewModel: SearchViewModel by viewModels()
    private val downloadViewModel: DownloadsViewModel by viewModels()
    private val playlistViewModel: PlaylistViewModel by viewModels()

    // registering for activity results is only possible, this here should have been part of
    // PlaylistOptionsBottomSheet instead if Android allowed us to
    private var playlistExportFormat: ImportFormat = ImportFormat.NEWPIPE
    private var exportPlaylistId: String? = null
    private val createPlaylistsFile = registerForActivityResult(
        ActivityResultContracts.CreateDocument(FILETYPE_ANY)
    ) { uri ->
        if (uri == null) return@registerForActivityResult

        lifecycleScope.launch(Dispatchers.IO) {
            ImportHelper.exportPlaylists(
                this@MainActivity,
                uri,
                playlistExportFormat,
                selectedPlaylistIds = listOf(exportPlaylistId!!)
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // show noInternet Activity if no internet available on app startup
        if (!NetworkHelper.isNetworkAvailable(this)) {
            val noInternetIntent = Intent(this, NoInternetActivity::class.java)
            startActivity(noInternetIntent)
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // give the on-device yt-dlp engine its application context; it starts the
        // Python interpreter lazily, only when it is actually needed
        YtDlpEngine.init(this)

        // manually apply additional padding for edge-to-edge compatibility
        // see https://developer.android.com/develop/ui/views/layout/edge-to-edge
        binding.root.onSystemInsets { _, systemBarInsets ->
            // there's a possibility that the paddings are not being applied properly when
            // exiting from player's fullscreen. Adding OnGlobalLayoutListener serves as
            // a workaround for this issue
            binding.root.viewTreeObserver.addOnGlobalLayoutListener(object :
                ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    with(binding.appBarLayout) {
                        setPadding(
                            paddingLeft,
                            systemBarInsets.top,
                            paddingRight,
                            paddingBottom
                        )
                    }
                    with(binding.bottomNav) {
                        setPadding(
                            paddingLeft,
                            paddingTop,
                            paddingRight,
                            systemBarInsets.bottom
                        )
                    }
                    with(binding.floatingNav) {
                        setPadding(
                            paddingLeft,
                            paddingTop,
                            paddingRight,
                            systemBarInsets.bottom + (8 * resources.displayMetrics.density).toInt()
                        )
                    }
                    binding.root.viewTreeObserver.removeOnGlobalLayoutListener(this)
                }
            })
        }
        // manually update the bottom bar height in the mini player transition
        binding.bottomNav.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateBottomBarKeyFrames(binding.bottomNav)
        }
        // the floating bar sits in the same place, so it needs the same keyframes
        binding.floatingNav.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            updateBottomBarKeyFrames(binding.floatingNav)
        }

        // Automatic update check disabled — feature hidden from settings UI

        // set the action bar for the activity
        setSupportActionBar(binding.toolbar)

        val navHostFragment = binding.fragment.getFragment<NavHostFragment>()
        navController = navHostFragment.navController
        binding.bottomNav.setupWithNavController(navController)

        // Any navigation breaks a double-tap pair, so leaving Home and coming back
        // can never count as the second tap of one.
        navController.addOnDestinationChangedListener { _, _, _ -> lastHomeTapAt = 0L }

        // save start tab fragment id and apply navbar style
        startFragmentId = try {
            NavBarHelper.applyNavBarStyle(binding.bottomNav)
        } catch (_: Exception) {
            R.id.homeFragment
        }

        // standard bottom bar or the floating pill bar, depending on the setting
        setUpNavBarMode()

        // warn from the bottom right when the connection is up but very slow
        setUpNetworkQualityPopup()

        // set default tab as start fragment
        navController.graph = navController.navInflater.inflate(R.navigation.nav).also {
            it.setStartDestination(startFragmentId)
        }

        // Prevent duplicate entries into backstack, if selected item and current
        // visible fragment is different, then navigate to selected item.
        binding.bottomNav.setOnItemReselectedListener {
            if (it.itemId != navController.currentDestination?.id) {
                navigateToBottomSelectedItem(it)
            } else if (it.itemId == R.id.homeFragment && registerHomeDoubleTap()) {
                // second tap on Home within the window: refresh the feed
                refreshHomeFeed()
            } else {
                // a single tap only goes back to the top of the list
                val fragment = navHostFragment.childFragmentManager.fragments.firstOrNull()
                tryScrollToTop(fragment?.requireView())
            }
        }

        binding.bottomNav.setOnItemSelectedListener {
            // Animate the selected item's icon with a spring scale
            animateBottomNavItem(it.itemId)
            navigateToBottomSelectedItem(it)
        }

        if (binding.bottomNav.menu.children.none { it.itemId == startFragmentId }) deselectBottomBarItems()

        binding.toolbar.title = ThemeHelper.getStyledAppName(this)

        // handle error logs
        PreferenceHelper.getErrorLog().ifBlank { null }?.let {
            if (!BuildConfig.DEBUG)
                ErrorDialog().show(supportFragmentManager, null)
        }

        setupSubscriptionsBadge()

        loadIntentData()

        showUserInfoDialogIfNeeded()
    }

    /**
     * Watches the connection and shows the low-network card when the link estimate
     * drops. Debounced, so a flapping connection cannot spam the screen.
     */
    private fun setUpNetworkQualityPopup() {
        binding.networkPopupClose.setOnClickListener { hideNetworkPopup() }

        val connectivityManager = getSystemService<ConnectivityManager>() ?: return
        try {
            connectivityManager.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(
                        network: Network,
                        networkCapabilities: NetworkCapabilities
                    ) {
                        runOnUiThread { maybeWarnAboutLowNetwork() }
                    }

                    override fun onLost(network: Network) {
                        runOnUiThread { hideNetworkPopup() }
                    }
                }
            )
        } catch (_: Throwable) {
            // some OEM builds refuse the callback; the warning is best-effort
        }
    }

    /**
     * Show the low-network card, at most once every two minutes.
     *
     * Screens call this when a load fails while the device is still online, which
     * is the case that used to fall through to the "Retry" page.
     */
    fun maybeWarnAboutLowNetwork() {
        if (NetworkQuality.level(this) != NetworkQuality.Level.LOW) return

        val now = System.currentTimeMillis()
        if (now - lastLowNetworkWarningAt < LOW_NETWORK_WARNING_INTERVAL_MS) return
        lastLowNetworkWarningAt = now

        showNetworkPopup()
    }

    private fun showNetworkPopup() {
        if (!this::binding.isInitialized) return
        val popup = binding.networkPopup
        if (popup.isVisible) return

        popup.visibility = View.VISIBLE
        popup.alpha = 0f
        popup.translationY = 32f * resources.displayMetrics.density
        popup.animate().alpha(1f).translationY(0f).setDuration(220).start()
        popup.postDelayed({ hideNetworkPopup() }, LOW_NETWORK_POPUP_VISIBLE_MS)
    }

    private fun hideNetworkPopup() {
        if (!this::binding.isInitialized) return
        val popup = binding.networkPopup
        if (!popup.isVisible) return

        popup.animate()
            .alpha(0f)
            .translationY(24f * resources.displayMetrics.density)
            .setDuration(180)
            .withEndAction { popup.visibility = View.GONE }
            .start()
    }

    /**
     * Keep the mini player transition's keyframe heights in sync with the bar that
     * is currently on screen. These frame positions are hardcoded in
     * activity_main_scene.xml!
     */
    private fun updateBottomBarKeyFrames(bar: View) {
        val transition = binding.root.getTransition(R.id.bottom_bar_transition)
        transition.keyFrameList.forEach { keyFrame ->
            for (key in keyFrame.getKeyFramesForView(bar.id)) {
                if (key.framePosition == 1) key.setValue(Key.TRANSLATION_Y, bar.height)
            }
            for (key in keyFrame.getKeyFramesForView(binding.container.id)) {
                if (key.framePosition == 100) key.setValue(Key.TRANSLATION_Y, -bar.height)
            }
        }
        binding.root.scene.setTransition(transition)
    }

    /**
     * Switches between the standard bottom bar and the floating pill bar, based on
     * the "Floating navigation bar" setting in Appearance.
     *
     * Both bars read the same menu, so the tabs, their order and their visibility
     * (set in the navigation bar dialog) are shared: turning the setting on swaps
     * the look without touching the navigation graph.
     */
    private fun setUpNavBarMode() {
        val useFloatingBar = PreferenceHelper.getBoolean(PreferenceKeys.FLOATING_NAV_BAR, false)
        val items = binding.bottomNav.menu.children
            .filter { it.isVisible }
            .map { FloatingNavItem(it.itemId, it.title?.toString().orEmpty(), it.icon) }
            .toList()

        if (!useFloatingBar || items.isEmpty()) {
            binding.floatingNav.visibility = View.GONE
            binding.bottomNav.visibility = View.VISIBLE
            return
        }

        binding.floatingNav.setItems(items, startFragmentId)
        binding.floatingNav.onItemSelected = { itemId -> onFloatingNavItemSelected(itemId) }
        binding.floatingNav.visibility = View.VISIBLE
        binding.bottomNav.visibility = View.GONE

        // keep the pill in step with navigation coming from anywhere else
        navController.addOnDestinationChangedListener { _, destination, _ ->
            if (items.any { it.itemId == destination.id }) {
                binding.floatingNav.setSelectedItem(destination.id)
            }
        }
    }

    /**
     * A tap on the floating bar follows exactly the same path as a tap on the
     * bottom bar: reselecting the current tab scrolls it to the top, anything else
     * navigates.
     */
    private fun onFloatingNavItemSelected(itemId: Int) {
        val menuItem = binding.bottomNav.menu.findItem(itemId) ?: return
        if (itemId != navController.currentDestination?.id) {
            navigateToBottomSelectedItem(menuItem)
        } else if (itemId == R.id.homeFragment && registerHomeDoubleTap()) {
            refreshHomeFeed()
        } else {
            val fragment = binding.fragment.getFragment<NavHostFragment>()
                .childFragmentManager.fragments.firstOrNull()
            tryScrollToTop(fragment?.requireView())
        }
        binding.floatingNav.setSelectedItem(itemId)
    }

    /**
     * True when Home was tapped a second time within [HOME_DOUBLE_TAP_MS].
     *
     * A single tap only scrolls back to the top; refreshing on every tap made the
     * feed reload and change the moment the tab was touched.
     */
    private fun registerHomeDoubleTap(): Boolean {
        val now = System.currentTimeMillis()
        val isDoubleTap = lastHomeTapAt != 0L && now - lastHomeTapAt <= HOME_DOUBLE_TAP_MS
        lastHomeTapAt = if (isDoubleTap) 0L else now
        return isDoubleTap
    }

    /** Reload the feed with the skeleton placeholders visible. */
    private fun refreshHomeFeed() {
        if (!this::navController.isInitialized) return
        if (navController.currentDestination?.id != R.id.homeFragment) return
        homeViewModel.loadRecommendationFeed(this, forceRefresh = true)
    }

    /**
     * Deselect all bottom bar items
     */
    private fun deselectBottomBarItems() {
        binding.bottomNav.menu.setGroupCheckable(0, true, false)
        for (child in binding.bottomNav.menu.children) {
            child.isChecked = false
        }
        binding.bottomNav.menu.setGroupCheckable(0, true, true)
    }

    /**
     * Try to find a scroll or recycler view and scroll it back to the top
     */
    private fun tryScrollToTop(view: View?) {
        val scrollView = view?.allViews
            ?.firstOrNull { it is ScrollView || it is NestedScrollView || it is RecyclerView }
        when (scrollView) {
            is ScrollView -> scrollView.smoothScrollTo(0, 0)
            is NestedScrollView -> scrollView.smoothScrollTo(0, 0)
            is RecyclerView -> scrollView.smoothScrollToPosition(0)
        }
    }

    /**
     * Animate the selected bottom nav item's icon with a smooth spring scale.
     * Finds the icon view inside the menu item and applies scale up + settle.
     */
    private fun animateBottomNavItem(itemId: Int) {
        val menuView = binding.bottomNav.getChildAt(0) as? android.view.ViewGroup ?: return
        for (i in 0 until menuView.childCount) {
            val child = menuView.getChildAt(i)
            // Each child is a BottomNavigationItemView
            child.findViewById<android.view.View>(com.google.android.material.R.id.navigation_bar_item_icon_view)
                ?.let { iconView ->
                    // Check if this is the selected item
                    val menuItem = binding.bottomNav.menu.children.elementAtOrNull(i)
                    if (menuItem?.itemId == itemId) {
                        iconView.animate()
                            .scaleX(1.3f)
                            .scaleY(1.3f)
                            .setDuration(100)
                            .withEndAction {
                                iconView.animate()
                                    .scaleX(1f)
                                    .scaleY(1f)
                                    .setDuration(200)
                                    .start()
                            }
                            .start()
                    }
                }
        }
    }

    /**
     * Initialize the notification badge showing the amount of new videos
     */
    private fun setupSubscriptionsBadge() {
        if (!PreferenceHelper.getBoolean(
                PreferenceKeys.NEW_VIDEOS_BADGE,
                false
            )
        ) {
            return
        }

        subscriptionsViewModel.fetchSubscriptions(this)

        subscriptionsViewModel.videoFeed.observe(this) { feed ->
            val lastCheckedFeedTime = PreferenceHelper.getLastCheckedFeedTime(seenByUser = true)
            val lastSeenVideoIndex = feed.orEmpty()
                .filter { !it.isUpcoming }
                .indexOfFirst { it.uploaded <= lastCheckedFeedTime }
            if (lastSeenVideoIndex < 1) return@observe

            binding.bottomNav.getOrCreateBadge(R.id.subscriptionsFragment).apply {
                number = lastSeenVideoIndex
                backgroundColor = ThemeHelper.getThemeColor(
                    this@MainActivity,
                    androidx.appcompat.R.attr.colorPrimary
                )
                badgeTextColor = ThemeHelper.getThemeColor(
                    this@MainActivity,
                    com.google.android.material.R.attr.colorOnPrimary
                )
            }
            // the floating bar draws its own badge
            binding.floatingNav.setBadgeCount(R.id.subscriptionsFragment, lastSeenVideoIndex)
        }
    }

    private fun isSearchInProgress(): Boolean {
        if (!this::navController.isInitialized) return false
        val id = navController.currentDestination?.id ?: return false

        return id in listOf(
            R.id.searchFragment,
            R.id.searchResultFragment,
            R.id.channelFragment,
            R.id.playlistFragment
        )
    }

    private fun addSearchQueryToHistory(query: String) {
        val searchHistoryEnabled =
            PreferenceHelper.getBoolean(PreferenceKeys.SEARCH_HISTORY_TOGGLE, true)
        if (searchHistoryEnabled && query.isNotEmpty()) {
            lifecycleScope.launch(Dispatchers.IO) {
                val newItem = SearchHistoryItem(query.trim())
                DatabaseHelper.addToSearchHistory(newItem)
            }
        }
    }

    override fun invalidateMenu() {
        // Don't invalidate menu when in search in progress
        // this is a workaround as there is bug in android code
        // details of bug: https://issuetracker.google.com/issues/244336571
        if (isSearchInProgress()) {
            return
        }
        super.invalidateMenu()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.action_bar, menu)

        // stuff for the search in the topBar
        val searchItem = menu.findItem(R.id.action_search)
        this.searchItem = searchItem
        searchView = searchItem.actionView as SearchView

        // automatically set a different search icon in the playlists
        navController.addOnDestinationChangedListener { _, destination, _ ->
            currentSearchType = when (destination.id) {
                R.id.downloadsFragment -> SearchType.DOWNLOADS
                R.id.playlistFragment -> SearchType.PLAYLIST
                else -> SearchType.ONLINE
            }
            // clear query in unused page so that they're reset when visiting the page the next time
            if (currentSearchType != SearchType.DOWNLOADS) downloadViewModel.setQuery(null)
            if (currentSearchType != SearchType.PLAYLIST) playlistViewModel.setQuery(null)

            val searchIconResource = when (currentSearchType) {
                SearchType.DOWNLOADS -> R.drawable.ic_download_search
                SearchType.PLAYLIST -> R.drawable.ic_playlist_search
                SearchType.ONLINE -> R.drawable.ic_search
            }

            searchItem.setIcon(searchIconResource)
        }

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean {
                searchView.clearFocus()

                // playlist and download search don't do anything on submit
                // as they search while typing
                if (currentSearchType != SearchType.ONLINE) return true

                // handle inserted YouTube-like URLs and directly open the referenced
                // channel, playlist or video instead of showing search results
                if (query.toHttpUrlOrNull() != null) {
                    val queryIntent = IntentHelper.resolveType(query.toUri())

                    val didNavigate = navigateToMediaByIntent(queryIntent) {
                        navController.popBackStack(R.id.searchFragment, true)
                        searchItem.collapseActionView()
                    }
                    if (didNavigate) return true
                }

                navController.navigate(NavDirections.showSearchResults(query))

                addSearchQueryToHistory(query)

                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                if (!shouldOpenSuggestions) return true

                // Prevent navigation when search view is collapsed
                if (searchView.isIconified ||
                    binding.bottomNav.menu.children.any {
                        it.itemId == navController.currentDestination?.id
                    }
                ) {
                    return true
                }

                // prevent malicious navigation when the search view is getting collapsed
                val destIds = listOf(
                    R.id.searchResultFragment,
                    R.id.channelFragment,
                    R.id.playlistFragment
                )
                if (navController.currentDestination?.id in destIds && newText == null) {
                    return false
                }

                when (currentSearchType) {
                    SearchType.ONLINE -> {
                        if (navController.currentDestination?.id != R.id.searchFragment) {
                            navController.navigate(
                                R.id.searchFragment,
                                bundleOf(IntentData.query to newText)
                            )
                        } else {
                            searchViewModel.setQuery(newText)
                        }
                    }

                    SearchType.PLAYLIST -> {
                        playlistViewModel.setQuery(newText)
                    }

                    SearchType.DOWNLOADS -> {
                        downloadViewModel.setQuery(newText)
                    }
                }

                return true
            }
        })

        searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                if (currentSearchType == SearchType.ONLINE && navController.currentDestination?.id != R.id.searchResultFragment) {
                    searchViewModel.setQuery(null)
                    navController.navigate(R.id.openSearch)
                }
                item.setShowAsAction(
                    MenuItem.SHOW_AS_ACTION_ALWAYS or MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW
                )
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                // Handover back press to `BackPressedDispatcher` if not on a root destination
                if (navController.previousBackStackEntry != null) {
                    this@MainActivity.onBackPressedDispatcher.onBackPressed()
                }

                // Suppress collapsing of search when search in progress.
                return !isSearchInProgress()
            }
        })

        // handle search queries passed by the intent
        if (savedSearchQuery != null) {
            searchItem.expandActionView()
            searchView.setQuery(savedSearchQuery, true)
            savedSearchQuery = null
        }

        return super.onCreateOptionsMenu(menu)
    }

    /**
     * Update the query text in the search bar without opening the search suggestions
     */
    fun setQuerySilent(query: String) {
        if (!this::searchView.isInitialized) return

        shouldOpenSuggestions = false
        searchView.setQuery(query, false)
        shouldOpenSuggestions = true
    }

    /**
     * Update the query text in the search bar and load the search suggestions
     * @param submit whether to immediately load the search results (not suggestions)
     */
    fun setQuery(query: String, submit: Boolean) {
        if (::searchView.isInitialized) searchView.setQuery(query, submit)
    }

    private fun loadIntentData() {
        // If activity is running in PiP mode, then start it in front.
        if (PictureInPictureCompat.isInPictureInPictureMode(this)) {
            val nIntent = Intent(this, MainActivity::class.java)
            nIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(nIntent)
        }

        if (intent?.getBooleanExtra(IntentData.maximizePlayer, false) == true) {
            // attempt to open the current video player fragment
            if (intent?.getBooleanExtra(IntentData.audioOnly, false) == false) {
                runOnPlayerFragment { binding.playerMotionLayout.transitionToStart(); true }
                return
            }

            // if it's an audio only session, attempt to maximize the audio player or create a new one
            if (runOnAudioPlayerFragment { binding.playerMotionLayout.transitionToStart(); true }) return

            val offlinePlayer = intent!!.getBooleanExtra(IntentData.offlinePlayer, false)
            NavigationHelper.openAudioPlayerFragment(this, offlinePlayer = offlinePlayer)
            return
        }

        // navigate to (temporary) playlist or channel if available
        if (navigateToMediaByIntent(intent)) return

        // Get saved search query if available
        intent?.getStringExtra(IntentData.query)?.let {
            savedSearchQuery = it
        }

        // Open the Downloads screen if requested
        if (intent?.getBooleanExtra(IntentData.OPEN_DOWNLOADS, false) == true) {
            navController.navigate(R.id.downloadsFragment)
            return
        }

        // Handle navigation from app shortcuts (Home, Trends, etc.)
        intent?.getStringExtra(IntentData.fragmentToOpen)?.let {
            ShortcutManagerCompat.reportShortcutUsed(this, it)
            when (it) {
                TopLevelDestination.Home.route -> navController.navigate(R.id.homeFragment)
                TopLevelDestination.Trends.route -> navController.navigate(R.id.trendsFragment)
                TopLevelDestination.Subscriptions.route -> navController.navigate(R.id.subscriptionsFragment)
                TopLevelDestination.Library.route -> navController.navigate(R.id.libraryFragment)
        TopLevelDestination.Shorts.route -> navController.navigate(R.id.shortsFragment)
            }
        }

        // Rebind the download service if the user is currently downloading
        if (intent?.getBooleanExtra(IntentData.downloading, false) == true) {
            (supportFragmentManager.fragments.find { it is NavHostFragment })
                ?.childFragmentManager?.fragments?.forEach { fragment ->
                    (fragment as? DownloadsFragment)?.bindDownloadService()
                }
        }
    }

    /**
     * Navigates to the channel, video or playlist provided in the [Intent] if available
     *
     * @return Whether the method handled the event and triggered the navigation to a new fragment
     */
    fun navigateToMediaByIntent(intent: Intent, actionBefore: () -> Unit = {}): Boolean {
        intent.getStringExtra(IntentData.channelId)?.let {
            actionBefore()
            navController.navigate(NavDirections.openChannel(channelId = it))
            return true
        }
        intent.getStringExtra(IntentData.channelName)?.let {
            actionBefore()
            navController.navigate(NavDirections.openChannel(channelName = it))
            return true
        }
        intent.getStringExtra(IntentData.playlistId)?.let {
            actionBefore()
            navController.navigate(NavDirections.openPlaylist(playlistId = it))
            return true
        }
        intent.getStringArrayExtra(IntentData.videoIds)?.let {
            actionBefore()
            ImportTempPlaylistDialog()
                .apply {
                    arguments = bundleOf(
                        IntentData.playlistName to intent.getStringExtra(IntentData.playlistName),
                        IntentData.videoIds to it
                    )
                }
                .show(supportFragmentManager, null)
            return true
        }

        intent.getStringExtra(IntentData.videoId)?.let {
            // the below explained work around only seems to work on Android 11 and above
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && binding.bottomNav.menu.isNotEmpty()) {
                // the bottom navigation bar has to be created before opening the video
                // otherwise the player layout measures aren't calculated properly
                // and the miniplayer is opened at a closed state and overlapping the navigation bar
                binding.bottomNav.viewTreeObserver.addOnGlobalLayoutListener(object :
                    ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        navigationVideo(it)

                        binding.bottomNav.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    }
                })
            } else {
                navigationVideo(it)
            }

            return true
        }

        return false
    }

    private fun navigationVideo(videoId: String) {
        NavigationHelper.navigateVideo(
            context = this@MainActivity,
            playerData = PlayerData(
                videoId = videoId,
                timestamp = intent.getLongExtra(IntentData.timeStamp, 0L)
            ),
        )
    }

    private fun navigateToBottomSelectedItem(item: MenuItem): Boolean {
        // Rapid-tap protection: ignore if same item tapped within debounce window
        val now = System.currentTimeMillis()
        if (item.itemId == lastNavItemId && (now - lastNavTimeMs) < NAV_DEBOUNCE_MS) {
            return false
        }
        lastNavItemId = item.itemId
        lastNavTimeMs = now

        // If already on this destination, don't navigate again
        if (item.itemId == navController.currentDestination?.id) {
            return false
        }

        if (item.itemId == R.id.subscriptionsFragment) {
            binding.bottomNav.removeBadge(R.id.subscriptionsFragment)
            binding.floatingNav.clearBadge(R.id.subscriptionsFragment)
        }

        // Remove focus from search view when navigating to bottom view.
        searchItem.collapseActionView()

        // Use custom NavOptions with fast, smooth fade animations
        // This avoids the default slow slide animations that cause lag
        val navOptions = androidx.navigation.NavOptions.Builder()
            .setLaunchSingleTop(true)
            .setRestoreState(true)
            .setPopUpTo(
                navController.graph.findStartDestination().id,
                inclusive = false,
                saveState = true
            )
            .setEnterAnim(R.animator.nav_default_enter_anim)
            .setExitAnim(R.animator.nav_default_exit_anim)
            .setPopEnterAnim(R.animator.nav_default_pop_enter_anim)
            .setPopExitAnim(R.animator.nav_default_pop_exit_anim)
            .build()

        return try {
            navController.navigate(item.itemId, null, navOptions)
            true
        } catch (e: IllegalArgumentException) {
            // Destination not found, fall back to default
            item.onNavDestinationSelected(navController)
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()

        runOnPlayerFragment {
            onUserLeaveHint()
            true
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        this.intent = intent
        loadIntentData()
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        // don't forward key events to the player while the search text input is used
        if (searchItem.isActionViewExpanded) return false

        if (runOnPlayerFragment { this@runOnPlayerFragment.onKeyUp(keyCode, event) }) {
            return true
        }

        return super.onKeyUp(keyCode, event)
    }

    fun startPlaylistExport(
        playlistId: String,
        playlistName: String,
        format: ImportFormat,
        includeTimestamp: Boolean
    ) {
        playlistExportFormat = format
        exportPlaylistId = playlistId

        val fileName =
            BackupRestoreSettings.getExportFileName(this, format, playlistName, includeTimestamp)
        createPlaylistsFile.launch(fileName)
    }

    private fun showUserInfoDialogIfNeeded() {
        // don't show the update information dialog for debug builds
        if (BuildConfig.DEBUG) return

        val lastShownVersionCode =
            PreferenceHelper.getInt(PreferenceKeys.LAST_SHOWN_INFO_MESSAGE_VERSION_CODE, -1)

        // mapping of version code to info message
        val infoMessages = emptyList<Pair<Int, String>>()

        val message =
            infoMessages.lastOrNull { (versionCode, _) -> versionCode > lastShownVersionCode }?.second
                ?: return

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.update_information)
            .setMessage(message)
            .setNegativeButton(R.string.okay, null)
            .setPositiveButton(R.string.never_show_again) { _, _ ->
                PreferenceHelper.putInt(
                    PreferenceKeys.LAST_SHOWN_INFO_MESSAGE_VERSION_CODE,
                    BuildConfig.VERSION_CODE
                )
            }
            .show()
    }

    override fun minimizePlayerContainerLayout() {
        binding.mainMotionLayout.transitionToEnd()
    }

    override fun maximizePlayerContainerLayout() {
        binding.mainMotionLayout.transitionToStart()
    }

    override fun setPlayerContainerProgress(progress: Float) {
        if (!NavBarHelper.hasTabs()) return

        binding.mainMotionLayout.progress = progress
    }

    /**
     * @return whether the search view focus was cleared successfully
     */
    override fun clearSearchViewFocus(): Boolean {
        if (!this::searchView.isInitialized || !searchView.anyChildFocused()) return false

        searchView.clearFocus()
        return true
    }
}
