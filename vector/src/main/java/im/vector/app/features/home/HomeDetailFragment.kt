/*
 * Copyright 2019-2024 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial
 * Please see LICENSE files in the repository root for full details.
 */

package im.vector.app.features.home

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.airbnb.mvrx.UniqueOnly
import com.airbnb.mvrx.activityViewModel
import com.airbnb.mvrx.fragmentViewModel
import com.airbnb.mvrx.withState
import com.google.android.material.badge.BadgeDrawable
import dagger.hilt.android.AndroidEntryPoint
import de.spiritcroc.matrixsdk.util.DbgUtil
import de.spiritcroc.matrixsdk.util.Dimber
import de.spiritcroc.viewpager.reduceDragSensitivity
import im.vector.app.R
import im.vector.app.SelectSpaceFrom
import im.vector.app.SpaceStateHandler
import im.vector.app.core.extensions.commitTransaction
import im.vector.app.core.extensions.restart
import im.vector.app.core.extensions.toMvRxBundle
import im.vector.app.core.platform.OnBackPressed
import im.vector.app.core.platform.StateView
import im.vector.app.core.platform.VectorBaseActivity
import im.vector.app.core.platform.VectorBaseFragment
import im.vector.app.core.platform.VectorMenuProvider
import im.vector.app.core.resources.ColorProvider
import im.vector.app.core.ui.views.CurrentCallsView
import im.vector.app.core.ui.views.CurrentCallsViewPresenter
import im.vector.app.core.ui.views.KeysBackupBanner
import im.vector.app.databinding.FragmentHomeDetailBinding
import im.vector.app.features.call.SharedKnownCallsViewModel
import im.vector.app.features.call.VectorCallActivity
import im.vector.app.features.call.dialpad.DialPadFragment
import im.vector.app.features.call.webrtc.WebRtcCallManager
import im.vector.app.features.home.room.list.RoomListFragment
import im.vector.app.features.home.room.list.RoomListParams
import im.vector.app.features.home.room.list.RoomListSectionBuilder.Companion.SPACE_ID_FOLLOW_APP
import im.vector.app.features.home.room.list.UnreadCounterBadgeView
import im.vector.app.features.home.room.list.home.spacebar.SpaceBarController
import im.vector.app.features.home.room.list.home.spacebar.SpaceBarData
import im.vector.app.features.popup.PopupAlertManager
import im.vector.app.features.popup.VerificationVectorAlert
import im.vector.app.features.settings.VectorLocaleProvider
import im.vector.app.features.settings.VectorPreferences
import im.vector.app.features.settings.VectorSettingsActivity.Companion.EXTRA_DIRECT_ACCESS_SECURITY_PRIVACY_MANAGE_SESSIONS
import im.vector.app.features.themes.ThemeUtils
import im.vector.app.features.workers.signout.BannerState
import im.vector.app.features.workers.signout.ServerBackupStatusAction
import im.vector.app.features.workers.signout.ServerBackupStatusViewModel
import im.vector.lib.strings.CommonStrings
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.crypto.model.DeviceInfo
import org.matrix.android.sdk.api.session.room.RoomSortOrder
import org.matrix.android.sdk.api.session.room.model.RoomSummary
import timber.log.Timber
import javax.inject.Inject
import kotlin.math.abs

@AndroidEntryPoint
class HomeDetailFragment :
        VectorBaseFragment<FragmentHomeDetailBinding>(),
        KeysBackupBanner.Delegate,
        CurrentCallsView.Callback,
        OnBackPressed,
        VectorMenuProvider {

    @Inject lateinit var avatarRenderer: AvatarRenderer
    @Inject lateinit var colorProvider: ColorProvider
    @Inject lateinit var alertManager: PopupAlertManager
    @Inject lateinit var callManager: WebRtcCallManager
    @Inject lateinit var vectorPreferences: VectorPreferences
    @Inject lateinit var spaceStateHandler: SpaceStateHandler
    @Inject lateinit var vectorLocale: VectorLocaleProvider
    @Inject lateinit var spaceBarController: SpaceBarController

    private val DEBUG_VIEW_PAGER = DbgUtil.isDbgEnabled(DbgUtil.DBG_VIEW_PAGER)
    private val viewPagerDimber = Dimber("Home pager", DbgUtil.DBG_VIEW_PAGER)

    private val viewModel: HomeDetailViewModel by fragmentViewModel()
    private val unknownDeviceDetectorSharedViewModel: UnknownDeviceDetectorSharedViewModel by activityViewModel()
    private val unreadMessagesSharedViewModel: UnreadMessagesSharedViewModel by activityViewModel()
    private val serverBackupStatusViewModel: ServerBackupStatusViewModel by activityViewModel()

    private lateinit var sharedActionViewModel: HomeSharedActionViewModel
    private lateinit var sharedCallActionViewModel: SharedKnownCallsViewModel

    // When this changes, restart the activity for changes to apply
    private val shouldShowUnimportantCounterBadge: Boolean by lazy { vectorPreferences.shouldShowUnimportantCounterBadge() }
    private val useAggregateCounts: Boolean by lazy { vectorPreferences.aggregateUnreadRoomCounts() }

    private var hasUnreadRooms = false
        set(value) {
            if (value != field) {
                field = value
                invalidateOptionsMenu()
            }
        }

    private var initialPageSelected = false
    private var pagerSpaces: List<String?>? = null
    private var pagerTab: HomeTab? = null
    private var pagerPagingEnabled: Boolean = false
    private var previousSelectedSpacePair: Pair<RoomSummary?, SelectSpaceFrom>? = null
    private var roomSortOrderSettings: RoomSortOrderSettings? = null

    override fun getMenuRes() = R.menu.room_list

    override fun handleMenuItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_home_mark_all_as_read -> {
                viewModel.handle(HomeDetailAction.MarkAllRoomsRead)
                true
            }
            R.id.menu_room_sort_order_activity -> {
                storeRoomSortOrder(RoomSortOrder.ACTIVITY)
                updateViewPager()
                true
            }
            R.id.menu_room_sort_order_unread -> {
                storeRoomSortOrder(RoomSortOrder.UNREAD_AND_ACTIVITY)
                updateViewPager()
                true
            }
            else -> false
        }
    }

    private fun currentEffectiveSpace(): String? {
        return if (pagerPagingEnabled) {
            getSpaceIdForPageIndex(views.roomListContainerPager.currentItem)
        } else {
            SPACE_ID_FOLLOW_APP
        }
    }

    private fun storeRoomSortOrder(roomSortOrder: RoomSortOrder) {
        val sharedPreferences = context?.let { PreferenceManager.getDefaultSharedPreferences(it) } ?: return
        val space = currentEffectiveSpace()
        val pref = if (space == null) VectorPreferences.SETTINGS_ROOM_SORT_ORDER_NULL else VectorPreferences.SETTINGS_ROOM_SORT_ORDER_NON_NULL
        sharedPreferences.edit {
            putString(pref, roomSortOrder.toString())
        }
    }

    private fun updateViewPager() {
        withState(viewModel) {
            val selectedSpace = it.selectedSpaceIgnoreSwipe ?: return@withState
            spaceStateHandler.persistSelectedSpace()
            setupViewPager(selectedSpace, it.rootSpacesOrdered, it.currentTab)
        }
    }

    override fun handlePrepareMenu(menu: Menu) {
        withState(viewModel) { state ->
            val isRoomList = state.currentTab is HomeTab.RoomList
            menu.findItem(R.id.menu_home_mark_all_as_read).isVisible = isRoomList && hasUnreadRooms
            menu.findItem(R.id.menu_room_sort_order).isVisible = true

            // Room sort order
            val space = currentEffectiveSpace()
            val roomSortOrder =
                    if (space == null) {
                        roomSortOrderSettings?.nullSpace
                    } else {
                        roomSortOrderSettings?.space
                    }
            when (roomSortOrder) {
                RoomSortOrder.ACTIVITY -> menu.findItem(R.id.menu_room_sort_order_activity).isChecked = true
                RoomSortOrder.UNREAD_AND_ACTIVITY -> menu.findItem(R.id.menu_room_sort_order_unread).isChecked = true
                else -> Unit
            }
        }
    }

    override fun getBinding(inflater: LayoutInflater, container: ViewGroup?): FragmentHomeDetailBinding {
        return FragmentHomeDetailBinding.inflate(inflater, container, false)
    }

    private val currentCallsViewPresenter = CurrentCallsViewPresenter()

    private val spaceBarListener = object: SpaceBarController.SpaceBarListener {
        override fun onSpaceBarSelectSpace(space: RoomSummary?) {
            // This is only called for viewpager spaces, so we can switch directly to the selected page,
            // instead of going the spaceStateHandler route (which would support any space (also non-root-spaces))
            val spacePos = getPageIndexForSpaceId(space?.roomId)
            if (spacePos == null) {
                // Fallback, shouldn't happen usually
                spaceStateHandler.setCurrentSpace(space?.roomId, from = SelectSpaceFrom.SELECT)
            } else {
                setCurrentPagerItem(spacePos)
            }
        }
        override fun onSpaceBarLongPressSpace(space: RoomSummary?): Boolean {
            sharedActionViewModel.post(HomeActivitySharedAction.OpenDrawer)
            return true
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sharedActionViewModel = activityViewModelProvider.get(HomeSharedActionViewModel::class.java)
        sharedCallActionViewModel = activityViewModelProvider.get(SharedKnownCallsViewModel::class.java)
        setupBottomNavigationView()
        setupToolbar()
        setupKeysBackupBanner()
        setupActiveCallView()

        checkNotificationTabStatus()

        views.spaceBarRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        val spaceBarAdapter = spaceBarController.also { controller ->
            controller.spaceRoomListener = spaceBarListener
        }.adapter
        views.spaceBarRecyclerView.adapter = spaceBarAdapter

        // Reduce sensitivity of viewpager to avoid scrolling horizontally by accident too easily
        views.roomListContainerPager.reduceDragSensitivity(4)

        // space pager: update spaceStateHandler's current page to update rest of the UI accordingly
        views.roomListContainerPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                viewPagerDimber.i{"Home pager: selected page $position $initialPageSelected"}
                super.onPageSelected(position)
                if (!initialPageSelected) {
                    // Do not apply before we have restored the previous value
                    if (position == 0) {
                        return
                    } else {
                        // User has swiped, store it anyways
                        initialPageSelected = true
                    }
                }
                selectSpaceFromSwipe(position)
            }
        })

        viewModel.onEach(HomeDetailViewState::selectedSpace) { selectedSpace ->
            // While paging is enabled, make title follow the view pager directly
            if (pagerPagingEnabled) {
                return@onEach
            }
            onSpaceChange(selectedSpace)
        }

        viewModel.onEach(HomeDetailViewState::currentTab) { currentTab ->
            updateUIForTab(currentTab)
        }

        viewModel.onEach(HomeDetailViewState::showDialPadTab) { showDialPadTab ->
            updateTabVisibilitySafely(R.id.bottom_action_dial_pad, showDialPadTab)
            checkNotificationTabStatus(showDialPadTab)
        }

        viewModel.observeViewEvents { viewEvent ->
            when (viewEvent) {
                HomeDetailViewEvents.CallStarted -> handleCallStarted()
                is HomeDetailViewEvents.FailToCall -> showFailure(viewEvent.failure)
                HomeDetailViewEvents.Loading -> showLoadingDialog()
            }
        }

        unknownDeviceDetectorSharedViewModel.onEach { state ->
            state.unknownSessions.invoke()?.let { unknownDevices ->
                if (unknownDevices.firstOrNull()?.currentSessionTrust == true) {
                    val uid = PopupAlertManager.REVIEW_LOGIN_UID
                    alertManager.cancelAlert(uid)
                    val olderUnverified = unknownDevices.filter { !it.isNew }
                    val newest = unknownDevices.firstOrNull { it.isNew }?.deviceInfo
                    if (newest != null) {
                        promptForNewUnknownDevices(uid, state, newest)
                    } else if (olderUnverified.isNotEmpty()) {
                        // In this case we prompt to go to settings to review logins
                        promptToReviewChanges(uid, state, olderUnverified.map { it.deviceInfo })
                    }
                }
            }
        }

        unreadMessagesSharedViewModel.onEach { state ->
            val count =
                    UnreadCounterBadgeView.State.Count(
                            count = state.otherSpacesUnread.totalCount,
                            highlighted = state.otherSpacesUnread.isHighlight,
                            unread = state.otherSpacesUnread.unreadCount,
                            markedUnread = false
                    )
            spaceBarController.submitHomeUnreadCounts(count)
            renderDrawerUnreads(count)
        }

        viewModel.onEach(HomeDetailViewState::selectedSpaceIgnoreSwipe,
                HomeDetailViewState::rootSpacesOrdered,
                HomeDetailViewState::currentTab,
                UniqueOnly("HomeDetail_${System.identityHashCode(this)}"))
        { selectedSpace, rootSpacesOrdered, currentTab ->
            if (selectedSpace == null) {
                // Uninitialized
                return@onEach
            }
            setupViewPager(selectedSpace, rootSpacesOrdered , currentTab)
            previousSelectedSpacePair = selectedSpace
        }

        sharedCallActionViewModel
                .liveKnownCalls
                .observe(viewLifecycleOwner) {
                    currentCallsViewPresenter.updateCall(callManager.getCurrentCall(), callManager.getCalls())
                    invalidateOptionsMenu()
                }
    }

    private fun renderDrawerUnreads(count: UnreadCounterBadgeView.State.Count) {
        if (pagerPagingEnabled) {
            // Bottom bar shows unread counts already, so remove unnecessary noise
            views.drawerUnreadCounterBadgeView.render(count.copy(unread = 0))
        } else {
            views.drawerUnreadCounterBadgeView.render(count)
        }
    }

    private fun selectSpaceFromSwipe(position: Int) {
        val selectedId = getSpaceIdForPageIndex(position)
        spaceStateHandler.setCurrentSpace(selectedId, from = SelectSpaceFrom.SWIPE)
        if (pagerPagingEnabled) {
            onSpaceChange(
                    selectedId?.let { viewModel.getRoom(it)?.roomSummary() }
            )
        }
    }

    private fun navigateBack() {
        val previousSpaceId = spaceStateHandler.popSpaceBackstack()
        val parentSpaceId = spaceStateHandler.getCurrentSpace()?.flattenParentIds?.lastOrNull()
        setCurrentSpace(previousSpaceId ?: parentSpaceId)
    }

    private fun setCurrentSpace(spaceId: String?) {
        spaceStateHandler.setCurrentSpace(spaceId, isForwardNavigation = false)
        sharedActionViewModel.post(HomeActivitySharedAction.OnCloseSpace)
    }

    private fun handleCallStarted() {
        dismissLoadingDialog()
        val fragmentTag = HomeTab.DialPad.toFragmentTag()
        (childFragmentManager.findFragmentByTag(fragmentTag) as? DialPadFragment)?.clear()
    }

    override fun onDestroyView() {
        currentCallsViewPresenter.unBind()

        spaceBarController.spaceRoomListener = null

        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()

        if (vectorPreferences.shouldShowUnimportantCounterBadge() != shouldShowUnimportantCounterBadge ||
                vectorPreferences.aggregateUnreadRoomCounts() != useAggregateCounts) {
            activity?.restart()
            return
        }

        //updateTabVisibilitySafely(R.id.bottom_action_notification, vectorPreferences.labAddNotificationTab())
        checkNotificationTabStatus()
        callManager.checkForProtocolsSupportIfNeeded()
        refreshSpaceState()
    }

    private fun refreshSpaceState() {
        /* Upstream impl without care for viewPager
        spaceStateHandler.getCurrentSpace()?.let {
            onSpaceChange(it)
        }
        */

        // Current space/group is not live so at least refresh toolbar on resume
        spaceStateHandler.getCurrentSpace()?.let { currentSpace ->
            // While paging is enabled, make title follow the view pager directly
            if (pagerPagingEnabled) {
                return@let
            }
            onSpaceChange(currentSpace)
        }
    }

    override fun onPause() {
        super.onPause()

        // Persist swiped
        spaceStateHandler.persistSelectedSpace()
    }

    private fun checkNotificationTabStatus(enableDialPad: Boolean? = null) {
        val wasVisible = views.bottomNavigationView.menu.findItem(R.id.bottom_action_notification).isVisible
        val combinedOverview = vectorPreferences.combinedOverview()
        val combinedOverviewWasVisible = views.bottomNavigationView.menu.findItem(R.id.bottom_action_all).isVisible
        views.bottomNavigationView.menu.findItem(R.id.bottom_action_notification).isVisible = vectorPreferences.labAddNotificationTab()
        views.bottomNavigationView.menu.findItem(R.id.bottom_action_people).isVisible = !combinedOverview
        views.bottomNavigationView.menu.findItem(R.id.bottom_action_rooms).isVisible = !combinedOverview
        views.bottomNavigationView.menu.findItem(R.id.bottom_action_all).isVisible = combinedOverview
        if (combinedOverviewWasVisible != combinedOverview) {
            // As we hide it check if it's not the current item!
            withState(viewModel) {
                val menuId = it.currentTab.toMenuId()
                if (combinedOverview) {
                    if (menuId == R.id.bottom_action_people || menuId == R.id.bottom_action_rooms) {
                        viewModel.handle(HomeDetailAction.SwitchTab(HomeTab.RoomList(RoomListDisplayMode.ALL)))
                    }
                } else {
                    if (menuId == R.id.bottom_action_all) {
                        viewModel.handle(HomeDetailAction.SwitchTab(HomeTab.RoomList(RoomListDisplayMode.PEOPLE)))
                    }
                }
            }
        }
        if (wasVisible && !vectorPreferences.labAddNotificationTab()) {
            // As we hide it check if it's not the current item!
            withState(viewModel) {
                if (it.currentTab.toMenuId() == R.id.bottom_action_notification) {
                    if (combinedOverview) {
                        viewModel.handle(HomeDetailAction.SwitchTab(HomeTab.RoomList(RoomListDisplayMode.ALL)))
                    } else {
                        viewModel.handle(HomeDetailAction.SwitchTab(HomeTab.RoomList(RoomListDisplayMode.PEOPLE)))
                    }
                }
            }
        }
        val wasBottomBarVisible = views.bottomNavigationView.isVisible
        val showDialPad = enableDialPad ?: withState(viewModel) { it.showDialPadTab }
        val showTabBar = vectorPreferences.enableOverviewTabs() || showDialPad
        if (wasBottomBarVisible != showTabBar) {
            withState(viewModel) {
                // Update the navigation view if needed (for when we restore the tabs)
                if (showTabBar) {
                    views.bottomNavigationView.selectedItemId = it.currentTab.toMenuId()
                    views.bottomNavigationView.isVisible = true
                } else {
                    views.bottomNavigationView.isVisible = false
                }

            }
        }
    }

    private fun promptForNewUnknownDevices(uid: String, state: UnknownDevicesState, newest: DeviceInfo) {
        val user = state.myMatrixItem
        alertManager.postVectorAlert(
                VerificationVectorAlert(
                        uid = uid,
                        title = getString(CommonStrings.new_session),
                        description = getString(CommonStrings.verify_this_session, newest.displayName ?: newest.deviceId ?: ""),
                        iconId = R.drawable.ic_shield_warning
                ).apply {
                    viewBinder = VerificationVectorAlert.ViewBinder(user, avatarRenderer)
                    colorInt = colorProvider.getColorFromAttribute(com.google.android.material.R.attr.colorPrimary)
                    contentAction = Runnable {
                        (weakCurrentActivity?.get() as? VectorBaseActivity<*>)?.let { vectorBaseActivity ->
                            vectorBaseActivity.navigator
                                    .requestSessionVerification(vectorBaseActivity, newest.deviceId ?: "")
                        }
                        unknownDeviceDetectorSharedViewModel.handle(
                                UnknownDeviceDetectorSharedViewModel.Action.IgnoreNewLogin(newest.deviceId?.let { listOf(it) }.orEmpty())
                        )
                    }
                    dismissedAction = Runnable {
                        unknownDeviceDetectorSharedViewModel.handle(
                                UnknownDeviceDetectorSharedViewModel.Action.IgnoreNewLogin(newest.deviceId?.let { listOf(it) }.orEmpty())
                        )
                    }
                }
        )
    }

    private fun promptToReviewChanges(uid: String, state: UnknownDevicesState, oldUnverified: List<DeviceInfo>) {
        val user = state.myMatrixItem
        alertManager.postVectorAlert(
                VerificationVectorAlert(
                        uid = uid,
                        title = getString(CommonStrings.review_unverified_sessions_title),
                        description = getString(CommonStrings.review_unverified_sessions_description),
                        iconId = R.drawable.ic_shield_warning
                ).apply {
                    viewBinder = VerificationVectorAlert.ViewBinder(user, avatarRenderer)
                    colorInt = colorProvider.getColorFromAttribute(com.google.android.material.R.attr.colorPrimary)
                    contentAction = Runnable {
                        (weakCurrentActivity?.get() as? VectorBaseActivity<*>)?.let { activity ->
                            // mark as ignored to avoid showing it again
                            unknownDeviceDetectorSharedViewModel.handle(
                                    UnknownDeviceDetectorSharedViewModel.Action.IgnoreDevice(oldUnverified.mapNotNull { it.deviceId })
                            )
                            activity.navigator.openSettings(activity, EXTRA_DIRECT_ACCESS_SECURITY_PRIVACY_MANAGE_SESSIONS)
                        }
                    }
                    dismissedAction = Runnable {
                        unknownDeviceDetectorSharedViewModel.handle(
                                UnknownDeviceDetectorSharedViewModel.Action.IgnoreDevice(oldUnverified.mapNotNull { it.deviceId })
                        )
                    }
                }
        )
    }

    private fun onSpaceChange(spaceSummary: RoomSummary?) {
        if (pagerPagingEnabled) {
            spaceBarController.selectSpace(spaceSummary)
        }
        if (spaceSummary == null) {
            views.groupToolbarSpaceTitleView.isVisible = false
        } else {
            views.groupToolbarSpaceTitleView.isVisible = true
            views.groupToolbarSpaceTitleView.text = spaceSummary.displayName
        }
    }

    private fun setCurrentPagerItem(index: Int, smoothScroll: Boolean? = null) {
        // Do not smooth scroll large distances to avoid loading unnecessary many room list
        val safeSmoothScroll = smoothScroll ?: (abs(index - views.roomListContainerPager.currentItem) <= 1)
        views.roomListContainerPager.setCurrentItem(index, safeSmoothScroll)
        spaceBarController.scrollToSpacePosition(index)
    }

    private fun setupKeysBackupBanner() {
        serverBackupStatusViewModel.handle(ServerBackupStatusAction.OnBannerDisplayed)
        serverBackupStatusViewModel
                .onEach {
                    when (val banState = it.bannerState.invoke()) {
                        is BannerState.Setup,
                        BannerState.BackingUp,
                        BannerState.Hidden -> views.homeKeysBackupBanner.render(banState, false)
                        null -> views.homeKeysBackupBanner.render(BannerState.Hidden, false)
                        else -> Unit /* No op? */
                    }
                }
        views.homeKeysBackupBanner.delegate = this
    }

    private fun setupActiveCallView() {
        currentCallsViewPresenter.bind(views.currentCallsView, this)
    }

    private fun setupToolbar() {
        setupToolbar(views.groupToolbar)
                .setTitle(null)

        views.homeToolbarContent.debouncedClicks {
            withState(viewModel) { viewState ->
                viewState.selectedSpace?.let {
                    sharedActionViewModel.post(HomeActivitySharedAction.ShowSpaceSettings(it.roomId))
                }
            }
        }
    }

    private fun setupBottomNavigationView() {
        val combinedOverview = vectorPreferences.combinedOverview()
        views.bottomNavigationView.menu.findItem(R.id.bottom_action_notification).isVisible = vectorPreferences.labAddNotificationTab()
        views.bottomNavigationView.menu.findItem(R.id.bottom_action_people).isVisible = !combinedOverview
        views.bottomNavigationView.menu.findItem(R.id.bottom_action_rooms).isVisible = !combinedOverview
        views.bottomNavigationView.menu.findItem(R.id.bottom_action_all).isVisible = combinedOverview
        views.bottomNavigationView.setOnItemSelectedListener {
            val tab = when (it.itemId) {
                R.id.bottom_action_people -> HomeTab.RoomList(RoomListDisplayMode.PEOPLE)
                R.id.bottom_action_rooms -> HomeTab.RoomList(RoomListDisplayMode.ROOMS)
                R.id.bottom_action_notification -> HomeTab.RoomList(RoomListDisplayMode.NOTIFICATIONS)
                R.id.bottom_action_all -> HomeTab.RoomList(RoomListDisplayMode.ALL)
                else -> HomeTab.DialPad
            }
            viewModel.handle(HomeDetailAction.SwitchTab(tab))
            true
        }
    }

    private fun updateUIForTab(tab: HomeTab) {
        views.bottomNavigationView.menu.findItem(tab.toMenuId()).isChecked = true
        views.groupToolbarTitleView.setText(tab.titleRes)
        //updateSelectedFragment(tab)
        invalidateOptionsMenu()
    }

    private fun HomeTab.toFragmentTag() = "FRAGMENT_TAG_$this"

    /*
    private fun updateSelectedFragment(tab: HomeTab) {
        val fragmentTag = tab.toFragmentTag()
        val fragmentToShow = childFragmentManager.findFragmentByTag(fragmentTag)
        childFragmentManager.commitTransaction {
            childFragmentManager.fragments
                    .filter { it != fragmentToShow }
                    .forEach {
                        detach(it)
                    }
            if (fragmentToShow == null) {
                when (tab) {
                    is HomeTab.RoomList -> {
                        val params = RoomListParams(tab.displayMode)
                        add(R.id.roomListContainer, RoomListFragment::class.java, params.toMvRxBundle(), fragmentTag)
                    }
                    is HomeTab.DialPad -> {
                        add(R.id.roomListContainer, createDialPadFragment(), fragmentTag)
                    }
                }
            } else {
                if (tab is HomeTab.DialPad) {
                    (fragmentToShow as? DialPadFragment)?.applyCallback()
                }
                attach(fragmentToShow)
            }
        }
    }
     */

    private fun setupViewPager(selectedSpacePair: Pair<RoomSummary?, SelectSpaceFrom>, spaces: List<RoomSummary>?, tab: HomeTab) {
        val selectedSpace = selectedSpacePair.first
        val oldAdapter = views.roomListContainerPager.adapter as? FragmentStateAdapter
        val pagingAllowed = vectorPreferences.enableSpacePager() && tab is HomeTab.RoomList
        if (pagingAllowed && spaces == null) {
            viewPagerDimber.i{"Home pager: Skip initial setup, root spaces not known yet"}
            views.roomListContainerStateView.state = StateView.State.Loading
            return
        } else {
            views.roomListContainerStateView.state = StateView.State.Content
        }
        viewPagerDimber.i{"Home pager: setup, old adapter: $oldAdapter"}
        val unsafeSpaces = spaces?.map { it.roomId } ?: listOf()
        val selectedSpaceId = selectedSpace?.roomId
        val selectedIndex = if (previousSelectedSpacePair == selectedSpacePair && tab != pagerTab) {
            // Stick with previously selected space for tab changes
            views.roomListContainerPager.currentItem
        } else {
            getPageIndexForSpaceId(selectedSpaceId, unsafeSpaces)
        }
        val pagingEnabled = pagingAllowed && unsafeSpaces.isNotEmpty() && selectedIndex != null
        if (pagingEnabled) {
            views.spaceBarRecyclerView.isVisible = true
            spaceBarController.submitData(SpaceBarData(spaces?.let { listOf<RoomSummary?>(null) + it }, selectedSpace))
        } else {
            views.spaceBarRecyclerView.isVisible = false
        }
        val safeSpaces = if (pagingEnabled) unsafeSpaces else listOf()
        val newRoomSortOrderSettings = loadRoomSortOrderSettings()
        // Check if we need to recreate the adapter for a new tab
        if (oldAdapter != null) {
            val changed = pagerTab != tab || pagerSpaces != safeSpaces || pagerPagingEnabled != pagingEnabled || roomSortOrderSettings != newRoomSortOrderSettings
            viewPagerDimber.i{ "has changed: $changed (${pagerTab != tab} ${pagerSpaces != safeSpaces} ${pagerPagingEnabled != pagingEnabled} ${roomSortOrderSettings != newRoomSortOrderSettings} $selectedIndex ${selectedSpacePair.second} ${views.roomListContainerPager.currentItem}) | $safeSpaces" }
            if (!changed) {
                // No need to re-setup pager, just check for selected page
                if (pagingEnabled) {
                    // Prioritize the actually displayed space for all space changes except for SELECT ones, to avoid
                    // unexpected page changes onResume for old updates
                    if (selectedSpacePair.second != SelectSpaceFrom.SELECT) {
                        // Tell the rest of the UI that we want to keep displaying the current space
                        viewPagerDimber.i { "Discard space change from ${selectedSpacePair.second} (${selectedSpaceId}/$selectedIndex), persist own (${views.roomListContainerPager.currentItem})" }
                        if (views.roomListContainerPager.currentItem != selectedIndex) {
                            selectSpaceFromSwipe(views.roomListContainerPager.currentItem)
                        }
                        return
                    }
                    if (selectedIndex != null) {
                        // Somehow, currentItem sometimes claims to be 0 after tab changes even if it is not right after that, so enforce setting that either way
                        if (selectedIndex != views.roomListContainerPager.currentItem || selectedIndex == 0) {
                            // post() mitigates a case where we could end up in an endless loop circling around the same few spaces
                            views.roomListContainerPager.post {
                                setCurrentPagerItem(selectedIndex)
                            }
                        }
                        return
                    }
                } else {
                    // Nothing to change
                    return
                }
            } else {
                // Clean up old fragments
                val transaction = childFragmentManager.beginTransaction()
                childFragmentManager.fragments
                        .forEach {
                            transaction.detach(it)
                        }
                transaction.commit()
            }
        }
        // In case the last space change was caused by swiping, we don't want to lose it
        spaceStateHandler.persistSelectedSpace()
        pagerSpaces = safeSpaces
        pagerTab = tab
        roomSortOrderSettings = newRoomSortOrderSettings
        if (pagerPagingEnabled != pagingEnabled) {
            pagerPagingEnabled = pagingEnabled
            // Update counts which depend on pagerPagingEnabled
            withState(unreadMessagesSharedViewModel) { state ->
                renderDrawerUnreads(
                    UnreadCounterBadgeView.State.Count(
                            count = state.otherSpacesUnread.totalCount,
                            highlighted = state.otherSpacesUnread.isHighlight,
                            unread = state.otherSpacesUnread.unreadCount,
                            markedUnread = false
                    )
                )
            }
        }
        initialPageSelected = false

        // OFFSCREEN_PAGE_LIMIT_DEFAULT: default recyclerview caching mechanism instead of explicit fixed prefetching
        //views.roomListContainerPager.offscreenPageLimit = 2

        val adapter = object: FragmentStateAdapter(this@HomeDetailFragment) {
            override fun getItemCount(): Int {
                if (!pagingEnabled) {
                    return 1
                }
                return when (tab) {
                    is HomeTab.DialPad -> 1
                    else -> safeSpaces.size + 1
                }
            }

            override fun createFragment(position: Int): Fragment {
                viewPagerDimber.i{"Home pager: create fragment for position $position"}
                return when (tab) {
                    is HomeTab.DialPad -> createDialPadFragment()
                    is HomeTab.RoomList -> {
                        val params = if (pagingEnabled) {
                            val spaceId = getSpaceIdForPageIndex(position)
                            viewPagerDimber.i{"Home pager: position $position -> space $spaceId"}
                            RoomListParams(tab.displayMode, spaceId, getRoomSortOrder(spaceId)).toMvRxBundle()
                        } else {
                            viewPagerDimber.i{"Home pager: paging disabled; position $position -> follow"}
                            RoomListParams(tab.displayMode, SPACE_ID_FOLLOW_APP, getRoomSortOrder(SPACE_ID_FOLLOW_APP)).toMvRxBundle()
                        }
                        childFragmentManager.fragmentFactory.instantiate(activity!!.classLoader, RoomListFragment::class.java.name).apply {
                            arguments = params
                        }
                    }
                }
            }
        }

        views.roomListContainerPager.adapter = adapter
        if (pagingEnabled) {
            // May be better than viewPager.post()? https://stackoverflow.com/a/57516428
            val pagerRecyclerView = views.roomListContainerPager.getChildAt(0)
            pagerRecyclerView.apply {
                viewTreeObserver.addOnGlobalLayoutListener(object: ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        viewTreeObserver.removeOnGlobalLayoutListener(this)
                        if (initialPageSelected) {
                            return
                        }
                        try {
                            viewPagerDimber.i{"Home pager: set initial page $selectedIndex"}
                            setCurrentPagerItem(selectedIndex ?: 0, false)
                            initialPageSelected = true
                        } catch (e: Exception) {
                            Timber.e("Home pager: Could not set initial page after creating adapter: $e")
                        }
                    }
                })
            }
        } else {
            // Set title, in case we missed it while paging
            onSpaceChange(selectedSpace)
        }
    }

    private fun getPageIndexForSpaceId(spaceId: String?, spaces: List<String?>? = pagerSpaces): Int? {
        if (spaceId == null) {
            return 0
        }
        val indexInList = spaces?.indexOf(spaceId)
        return when (indexInList) {
            null, -1 -> null
            else -> indexInList + 1
        }
    }

    private fun getSpaceIdForPageIndex(position: Int, spaces: List<String?>? = pagerSpaces): String? {
        if (spaces == null) {
            Timber.e(Exception("getSpaceIdForPageIndex: null spaces!"))
            return null
        }
        if (DEBUG_VIEW_PAGER && position > 0 && spaces[position-1] == null) {
            Timber.e(Exception("getSpaceIdForPageIndex: null space!"))
        }
        return if (position == 0) null else spaces[position-1]
    }

    data class RoomSortOrderSettings(val nullSpace: RoomSortOrder, val space: RoomSortOrder)

    private fun loadRoomSortOrderSettings(): RoomSortOrderSettings? {
        val sharedPreferences = context?.let { PreferenceManager.getDefaultSharedPreferences(it) } ?: return null
        val default = RoomSortOrder.ACTIVITY
        return RoomSortOrderSettings(
                tryOrNull { sharedPreferences.getString(VectorPreferences.SETTINGS_ROOM_SORT_ORDER_NULL, null)?.let { RoomSortOrder.valueOf(it) } } ?: default,
                tryOrNull { sharedPreferences.getString(VectorPreferences.SETTINGS_ROOM_SORT_ORDER_NON_NULL, null)?.let { RoomSortOrder.valueOf(it) } } ?: default,
        )
    }

    private fun getRoomSortOrder(space: String?): RoomSortOrder {
        return (
                if (space == null)
                    roomSortOrderSettings?.nullSpace
                else
                    roomSortOrderSettings?.space
                ) ?: RoomSortOrder.ACTIVITY
    }

    private fun createDialPadFragment(): Fragment {
        val fragment = childFragmentManager.fragmentFactory.instantiate(vectorBaseActivity.classLoader, DialPadFragment::class.java.name)
        return (fragment as DialPadFragment).apply {
            arguments = Bundle().apply {
                putBoolean(DialPadFragment.EXTRA_ENABLE_DELETE, true)
                putBoolean(DialPadFragment.EXTRA_ENABLE_OK, true)
                putString(DialPadFragment.EXTRA_REGION_CODE, vectorLocale.applicationLocale.country)
            }
            applyCallback()
        }
    }

    private fun updateTabVisibilitySafely(tabId: Int, isVisible: Boolean) {
        val wasVisible = views.bottomNavigationView.menu.findItem(tabId).isVisible
        views.bottomNavigationView.menu.findItem(tabId).isVisible = isVisible
        if (wasVisible && !isVisible) {
            // As we hide it check if it's not the current item!
            withState(viewModel) {
                if (it.currentTab.toMenuId() == tabId) {
                    val combinedOverview = vectorPreferences.combinedOverview()
                    if (combinedOverview) {
                        viewModel.handle(HomeDetailAction.SwitchTab(HomeTab.RoomList(RoomListDisplayMode.PEOPLE)))
                    } else {
                        viewModel.handle(HomeDetailAction.SwitchTab(HomeTab.RoomList(RoomListDisplayMode.ALL)))
                    }
                }
            }
        }
    }

    /* ==========================================================================================
     * KeysBackupBanner Listener
     * ========================================================================================== */

    override fun onCloseClicked() {
        serverBackupStatusViewModel.handle(ServerBackupStatusAction.OnBannerClosed)
    }

    override fun setupKeysBackup() {
        navigator.openKeysBackupSetup(requireActivity(), false)
    }

    override fun recoverKeysBackup() {
        navigator.openKeysBackupManager(requireActivity())
    }

    override fun invalidate() = withState(viewModel) {
        views.bottomNavigationView.getOrCreateBadge(R.id.bottom_action_people).render(it.notificationCountPeople, it.notificationHighlightPeople)
        views.bottomNavigationView.getOrCreateBadge(R.id.bottom_action_rooms).render(it.notificationCountRooms, it.notificationHighlightRooms)
        views.bottomNavigationView.getOrCreateBadge(R.id.bottom_action_notification).render(it.notificationCountCatchup, it.notificationHighlightCatchup)
        views.bottomNavigationView.getOrCreateBadge(R.id.bottom_action_all).render(it.notificationCountCatchup, it.notificationHighlightCatchup)
        views.syncStateView.render(
                it.syncState,
                it.incrementalSyncRequestState,
                it.pushCounter,
                vectorPreferences.developerShowDebugInfo()
        )

        hasUnreadRooms = it.hasUnreadMessages
    }

    private fun BadgeDrawable.render(count: Int, highlight: Boolean) {
        isVisible = count > 0
        number = count
        maxCharacterCount = 3
        badgeTextColor = ThemeUtils.getColor(requireContext(), com.google.android.material.R.attr.colorOnPrimary)
        backgroundColor = if (highlight) {
            ThemeUtils.getColor(requireContext(), com.google.android.material.R.attr.colorError)
        } else {
            ThemeUtils.getColor(requireContext(), im.vector.lib.ui.styles.R.attr.vctr_unread_background)
        }
    }

    private fun HomeTab.toMenuId() = when (this) {
        is HomeTab.DialPad -> R.id.bottom_action_dial_pad
        is HomeTab.RoomList -> when (displayMode) {
            RoomListDisplayMode.PEOPLE -> R.id.bottom_action_people
            RoomListDisplayMode.ROOMS -> R.id.bottom_action_rooms
            RoomListDisplayMode.ALL -> R.id.bottom_action_all
            else -> R.id.bottom_action_notification
        }
    }

    override fun onTapToReturnToCall() {
        callManager.getCurrentCall()?.let { call ->
            VectorCallActivity.newIntent(
                    context = requireContext(),
                    callId = call.callId,
                    signalingRoomId = call.signalingRoomId,
                    otherUserId = call.mxCall.opponentUserId,
                    isIncomingCall = !call.mxCall.isOutgoing,
                    isVideoCall = call.mxCall.isVideoCall,
                    mode = null
            ).let {
                startActivity(it)
            }
        }
    }

    private fun DialPadFragment.applyCallback(): DialPadFragment {
        callback = object : DialPadFragment.Callback {
            override fun onOkClicked(formatted: String?, raw: String?) {
                if (raw.isNullOrEmpty()) return
                viewModel.handle(HomeDetailAction.StartCallWithPhoneNumber(raw))
            }
        }
        return this
    }

    override fun onBackPressed(toolbarButton: Boolean) = if (vectorPreferences.spaceBackNavigation() && spaceStateHandler.getCurrentSpace() != null) {
        try {
            navigateBack()
            true
        } catch (e: NoSuchElementException) {
            false
        }
    } else {
        false
    }
}
