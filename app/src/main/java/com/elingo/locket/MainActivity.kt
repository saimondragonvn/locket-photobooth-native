package com.elingo.locket

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CardGiftcard
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.filled.PhotoAlbum
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.elingo.locket.ui.LocketViewModel
import com.elingo.locket.ui.RootTab
import com.elingo.locket.ui.auth.AccountSwitcherSheet
import com.elingo.locket.ui.auth.LoginScreen
import com.elingo.locket.ui.camera.CameraStudioScreen
import com.elingo.locket.ui.feed.MomentFeedScreen
import com.elingo.locket.ui.legal.OnlineCardsSheet
import com.elingo.locket.ui.memories.MemoriesScreen
import com.elingo.locket.ui.profile.ProfileAndSettingsScreen
import com.elingo.locket.ui.social.ChatAndFriendsScreen
import com.elingo.locket.ui.theme.LocalLocketPalette
import com.elingo.locket.ui.theme.LocketAppTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    private val viewModel: LocketViewModel by viewModels {
        LocketViewModel.factory(application as LocketApplication)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)

        setContent {
            val themeId by viewModel.themeId.collectAsState()
            val isGlass by viewModel.isGlassStyle.collectAsState()

            LocketAppTheme(themeId = themeId, isGlassStyle = isGlass) {
                LocketRootApp(viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun LocketRootApp(viewModel: LocketViewModel) {
    val context = LocalContext.current
    val palette = LocalLocketPalette.current

    val session by viewModel.session.collectAsState()
    val savedAccounts by viewModel.savedAccounts.collectAsState()
    val supporterStatus by viewModel.supporterStatus.collectAsState()
    val supporterSheet by viewModel.supporterSheet.collectAsState()
    val activeTab by viewModel.activeTab.collectAsState()
    val isLoggingIn by viewModel.isLoggingIn.collectAsState()
    val bannerMessage by viewModel.bannerMessage.collectAsState()
    val feedState by viewModel.feedState.collectAsState()
    val allMoments by viewModel.allMomentsForMemories.collectAsState()
    val streakState by viewModel.streakState.collectAsState()
    val socialState by viewModel.socialState.collectAsState()
    val overlayLookup by viewModel.overlayLookup.collectAsState()
    val outboxItems by viewModel.outboxItems.collectAsState()
    val themeId by viewModel.themeId.collectAsState()
    val isGlass by viewModel.isGlassStyle.collectAsState()
    val showCardsButton by viewModel.showCardsButton.collectAsState()
    val showAccountsButton by viewModel.showAccountsButton.collectAsState()
    val totalOutboxCount by viewModel.totalOutboxCount.collectAsState()

    var showAccountSwitcher by remember { mutableStateOf(false) }
    var showOnlineCardsSheet by remember { mutableStateOf(false) }
    var isAddingAnotherAccount by remember { mutableStateOf(false) }

    var hasCameraPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasMicPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        hasCameraPerm = result[Manifest.permission.CAMERA]
            ?: (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        hasMicPerm = result[Manifest.permission.RECORD_AUDIO]
            ?: (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }

    val requestHardwarePermissions = {
        val perms = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
        permissionLauncher.launch(perms)
    }

    LaunchedEffect(session != null) {
        if (session != null && !hasCameraPerm) {
            requestHardwarePermissions()
        }
    }

    LaunchedEffect(bannerMessage) {
        if (bannerMessage != null) {
            delay(3200L)
            viewModel.dismissBanner()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val activeSession = session
        if (activeSession == null || isAddingAnotherAccount) {
            LoginScreen(
                isLoggingIn = isLoggingIn,
                savedAccounts = savedAccounts,
                onLogin = { email, password ->
                    isAddingAnotherAccount = false
                    viewModel.login(email, password)
                },
                onQuickSwitchAccount = { uid ->
                    isAddingAnotherAccount = false
                    viewModel.switchAccount(uid)
                },
                onRemoveSavedAccount = { uid -> viewModel.removeSavedAccount(uid) }
            )
        } else {
            Scaffold(
                containerColor = palette.spec.background,
                topBar = {
                    if (showCardsButton || showAccountsButton) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(palette.spec.surface.copy(alpha = 0.92f))
                                .statusBarsPadding()
                                .padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Image(
                                    painter = painterResource(id = R.drawable.locket_brand_icon),
                                    contentDescription = "Locket Photobooth",
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier
                                        .size(26.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Locket Photobooth",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                            }

                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (showCardsButton) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .background(palette.cardContainer)
                                            .border(1.dp, palette.cardBorder, RoundedCornerShape(50))
                                            .clickable { showOnlineCardsSheet = true }
                                            .padding(horizontal = 10.dp, vertical = 5.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CardGiftcard,
                                            contentDescription = "Thiệp Online",
                                            tint = palette.spec.accent,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Thiệp", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                                if (showAccountsButton) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(50))
                                            .background(palette.cardContainer)
                                            .border(1.dp, palette.cardBorder, RoundedCornerShape(50))
                                            .clickable { showAccountSwitcher = true }
                                            .padding(horizontal = 10.dp, vertical = 5.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.SwapHoriz,
                                            contentDescription = "Chuyển tài khoản",
                                            tint = palette.spec.accent,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Tài khoản", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    }
                },
                bottomBar = {
                    NavigationBar(
                        containerColor = palette.spec.surface,
                        tonalElevation = 0.dp
                    ) {
                        val navColors = NavigationBarItemDefaults.colors(
                            selectedIconColor = palette.spec.onAccent,
                            selectedTextColor = palette.spec.accent,
                            indicatorColor = palette.spec.accent,
                            unselectedIconColor = palette.mutedText,
                            unselectedTextColor = palette.mutedText
                        )

                        NavigationBarItem(
                            selected = activeTab == RootTab.CAMERA,
                            onClick = { viewModel.selectTab(RootTab.CAMERA) },
                            icon = { Icon(Icons.Default.CameraAlt, contentDescription = "Camera") },
                            label = { Text("Ống kính", fontSize = 11.sp) },
                            colors = navColors
                        )
                        NavigationBarItem(
                            selected = activeTab == RootTab.FEED,
                            onClick = { viewModel.selectTab(RootTab.FEED) },
                            icon = { Icon(Icons.Default.PhotoAlbum, contentDescription = "Bảng tin") },
                            label = { Text("Bảng tin", fontSize = 11.sp) },
                            colors = navColors
                        )
                        NavigationBarItem(
                            selected = activeTab == RootTab.MEMORIES,
                            onClick = { viewModel.selectTab(RootTab.MEMORIES) },
                            icon = { Icon(Icons.Default.CalendarMonth, contentDescription = "Kỷ niệm") },
                            label = { Text("Kỷ niệm", fontSize = 11.sp) },
                            colors = navColors
                        )
                        NavigationBarItem(
                            selected = activeTab == RootTab.CHAT,
                            onClick = { viewModel.selectTab(RootTab.CHAT) },
                            icon = {
                                BadgedBox(
                                    badge = {
                                        val unread = socialState.unreadConversationsCount + socialState.requests.incoming.size
                                        if (unread > 0) {
                                            Badge { Text(unread.toString()) }
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.ChatBubbleOutline, contentDescription = "Tin nhắn")
                                }
                            },
                            label = { Text("Bạn bè", fontSize = 11.sp) },
                            colors = navColors
                        )
                        NavigationBarItem(
                            selected = activeTab == RootTab.PROFILE,
                            onClick = { viewModel.selectTab(RootTab.PROFILE) },
                            icon = { Icon(Icons.Default.PersonOutline, contentDescription = "Hồ sơ") },
                            label = { Text("Hồ sơ", fontSize = 11.sp) },
                            colors = navColors
                        )
                    }
                }
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    when (activeTab) {
                        RootTab.CAMERA -> CameraStudioScreen(
                            hasCameraPermission = hasCameraPerm,
                            hasMicPermission = hasMicPerm,
                            friends = socialState.friends,
                            streakState = streakState,
                            supporterStatus = supporterStatus,
                            outboxItems = outboxItems,
                            overlayLookup = overlayLookup,
                            onRequestPermissions = requestHardwarePermissions,
                            onRequestLocationOverlays = { viewModel.fetchLocationOverlays(context) },
                            onSendToOutbox = { file, isVid, isPriv, filterId, cap, ov, recips, perks, prepared ->
                                viewModel.enqueueMediaToOutbox(
                                    file = file,
                                    isVideo = isVid,
                                    isPrivate = isPriv,
                                    filterId = filterId,
                                    caption = cap,
                                    overlay = ov,
                                    recipients = recips,
                                    perks = perks,
                                    alreadyPrepared = prepared
                                )
                            },
                            onRetryOutbox = { viewModel.retryOutboxItem(it) },
                            onCancelOutbox = { viewModel.cancelOutboxItem(it) },
                            onJumpToFeed = { viewModel.selectTab(RootTab.FEED) },
                            onShowMessage = { viewModel.showBanner(it) }
                        )

                        RootTab.FEED -> MomentFeedScreen(
                            myUid = activeSession.localId,
                            feedState = feedState,
                            friends = socialState.friends,
                            onSelectFilter = { viewModel.setFeedFilter(it) },
                            onToggleGridMode = { viewModel.toggleFeedGridMode() },
                            onRefresh = { viewModel.refreshFeed(reset = true) },
                            onLoadMore = { viewModel.refreshFeed(reset = false) },
                            onReact = { m, e -> viewModel.reactToMoment(m, e) },
                            onReply = { m, t -> viewModel.replyToMoment(m, t) },
                            onMarkViewed = { viewModel.markMomentViewed(it) },
                            onOpenActivity = { viewModel.openMomentActivity(it) },
                            onCloseActivity = { viewModel.closeMomentActivity() },
                            onDeleteMoment = { viewModel.deleteMoment(it) },
                            onDownloadMoment = { viewModel.saveOrShareMomentMedia(context, it, shareAfterDownload = false) },
                            onShareMoment = { viewModel.saveOrShareMomentMedia(context, it, shareAfterDownload = true) },
                            onMomentMediaError = { m, url -> viewModel.onMomentMediaError(m, url) }
                        )

                        RootTab.MEMORIES -> MemoriesScreen(
                            moments = allMoments,
                            streakState = streakState
                        )

                        RootTab.CHAT -> ChatAndFriendsScreen(
                            myUid = activeSession.localId,
                            myUsername = activeSession.username,
                            socialState = socialState,
                            onSearchUsername = { viewModel.searchUserByUsername(it) },
                            onFriendAction = { uid, act -> viewModel.performFriendAction(uid, act) },
                            onOpenConversation = { peer, cId -> viewModel.openChatConversation(peer, cId) },
                            onCloseConversation = { viewModel.closeChatConversation() },
                            onSendMessage = { peer, txt -> viewModel.sendDirectMessage(peer, txt) },
                            onReactMessage = { cId, mId, em, rem -> viewModel.reactToChatMessage(cId, mId, em, rem) },
                            onDeleteMessage = { cId, mId -> viewModel.deleteChatMessage(cId, mId) }
                        )

                        RootTab.PROFILE -> ProfileAndSettingsScreen(
                            session = activeSession,
                            supporterStatus = supporterStatus,
                            supporterSheetState = supporterSheet,
                            activeThemeId = themeId,
                            isGlassStyle = isGlass,
                            showCardsButton = showCardsButton,
                            showAccountsButton = showAccountsButton,
                            pendingOutboxCount = totalOutboxCount,
                            onChangeAvatar = { viewModel.updateAvatarFromUri(context, it) },
                            onUpdateName = { f, l -> viewModel.updateAccountName(f, l) },
                            onSelectTheme = { viewModel.setTheme(it) },
                            onToggleGlass = { viewModel.setGlassStyle(it) },
                            onToggleShowCards = { viewModel.setShowCardsButton(it) },
                            onToggleShowAccounts = { viewModel.setShowAccountsButton(it) },
                            onOpenAccountSwitcher = { showAccountSwitcher = true },
                            onOpenOnlineCards = { showOnlineCardsSheet = true },
                            onLoadSupporterDetails = { viewModel.loadSupporterDetails() },
                            onSaveDonorDisplayName = { viewModel.updateDonorDisplayName(it) },
                            onPrepareClearDeviceData = { viewModel.refreshTotalOutboxCount() },
                            onClearAllDeviceData = { viewModel.clearAllDeviceData(context) },
                            onLogout = { viewModel.logout() }
                        )
                    }
                }
            }
        }

        // Top Floating Toast Banner
        AnimatedVisibility(
            visible = !bannerMessage.isNullOrBlank(),
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color(0xEE1F2028))
                    .clickable { viewModel.dismissBanner() }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text(
                    text = bannerMessage.orEmpty(),
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.5.sp
                )
            }
        }
    }

    if (showAccountSwitcher) {
        AccountSwitcherSheet(
            activeUid = session?.localId,
            savedAccounts = savedAccounts,
            onSwitchAccount = { viewModel.switchAccount(it) },
            onAddAnotherAccount = { isAddingAnotherAccount = true },
            onRemoveAccount = { viewModel.removeSavedAccount(it) },
            onDismiss = { showAccountSwitcher = false }
        )
    }

    if (showOnlineCardsSheet) {
        OnlineCardsSheet(
            onDismiss = { showOnlineCardsSheet = false }
        )
    }
}
