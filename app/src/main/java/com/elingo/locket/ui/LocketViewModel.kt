package com.elingo.locket.ui

import android.Manifest
import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.elingo.locket.AppContainer
import com.elingo.locket.LocketApplication
import com.elingo.locket.core.database.OutboxEntity
import com.elingo.locket.core.model.CaptionOverlayConfig
import com.elingo.locket.core.model.CaptionPreset
import com.elingo.locket.core.model.ChatMessage
import com.elingo.locket.core.model.Conversation
import com.elingo.locket.core.model.DonateMeResponse
import com.elingo.locket.core.model.DonorLeaderboardItem
import com.elingo.locket.core.model.FriendRequestsData
import com.elingo.locket.core.model.FriendUser
import com.elingo.locket.core.model.Moment
import com.elingo.locket.core.model.MomentActivity
import com.elingo.locket.core.model.NearbyPlace
import com.elingo.locket.core.model.StreakAnchor
import com.elingo.locket.core.model.StreakState
import com.elingo.locket.core.model.WeatherInfo
import com.elingo.locket.core.network.LocketApiException
import com.elingo.locket.media.processor.PhotoProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class RootTab {
    CAMERA,
    FEED,
    MEMORIES,
    CHAT,
    PROFILE
}

data class FeedUiState(
    val filterKey: String = FILTER_ALL, // "all", "private", or friend uid
    val isGridMode: Boolean = false,
    val moments: List<Moment> = emptyList(),
    val usersMap: Map<String, FriendUser> = emptyMap(),
    val nextCursor: String? = null,
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val activeActivity: MomentActivity? = null,
    val activeActivityMomentId: String? = null
) {
    companion object {
        const val FILTER_ALL = "all"
        const val FILTER_PRIVATE = "private"
    }
}

data class SocialUiState(
    val friends: List<FriendUser> = emptyList(),
    val meProfile: FriendUser? = null,
    val requests: FriendRequestsData = FriendRequestsData(),
    val searchedUser: FriendUser? = null,
    val isSearchingUser: Boolean = false,
    val conversations: List<Conversation> = emptyList(),
    val chatUsers: Map<String, FriendUser> = emptyMap(),
    val activeConversationId: String? = null,
    val activePeerUid: String? = null,
    val activeMessages: List<ChatMessage> = emptyList(),
    val isSendingMessage: Boolean = false
) {
    val unreadConversationsCount: Int
        get() = conversations.count { it.hasUnread }
}

data class OverlayLookupState(
    val presets: List<CaptionPreset> = emptyList(),
    val weather: WeatherInfo? = null,
    val places: List<NearbyPlace> = emptyList(),
    val isLoadingLocation: Boolean = false
)

data class SupporterSheetState(
    val donateMe: DonateMeResponse? = null,
    val leaderboard: List<DonorLeaderboardItem> = emptyList(),
    val isLoading: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
class LocketViewModel(
    application: Application,
    private val container: AppContainer
) : AndroidViewModel(application) {

    private val sessionManager = container.sessionManager
    private val apiClient = container.apiClient
    private val outboxManager = container.outboxManager
    private val outboxDao = container.database.outboxDao()

    val session = sessionManager.session
    val savedAccounts = sessionManager.savedAccounts
    val supporterStatus = sessionManager.supporterStatus
    val themeId = sessionManager.themeId
    val isGlassStyle = sessionManager.isGlassStyle
    val showCardsButton = sessionManager.showCardsButton
    val showAccountsButton = sessionManager.showAccountsButton

    private val _totalOutboxCount = MutableStateFlow(0)
    val totalOutboxCount: StateFlow<Int> = _totalOutboxCount.asStateFlow()

    private val _activeTab = MutableStateFlow(RootTab.CAMERA)
    val activeTab: StateFlow<RootTab> = _activeTab.asStateFlow()

    private val _isLoggingIn = MutableStateFlow(false)
    val isLoggingIn: StateFlow<Boolean> = _isLoggingIn.asStateFlow()

    private val _bannerMessage = MutableStateFlow<String?>(null)
    val bannerMessage: StateFlow<String?> = _bannerMessage.asStateFlow()

    private val _feedState = MutableStateFlow(FeedUiState())
    val feedState: StateFlow<FeedUiState> = _feedState.asStateFlow()

    private val _allMomentsForMemories = MutableStateFlow<List<Moment>>(emptyList())
    val allMomentsForMemories: StateFlow<List<Moment>> = _allMomentsForMemories.asStateFlow()

    private val _streakState = MutableStateFlow(StreakState())
    val streakState: StateFlow<StreakState> = _streakState.asStateFlow()

    private val _socialState = MutableStateFlow(SocialUiState())
    val socialState: StateFlow<SocialUiState> = _socialState.asStateFlow()

    private val _overlayLookup = MutableStateFlow(OverlayLookupState())
    val overlayLookup: StateFlow<OverlayLookupState> = _overlayLookup.asStateFlow()

    private val _supporterSheet = MutableStateFlow(SupporterSheetState())
    val supporterSheet: StateFlow<SupporterSheetState> = _supporterSheet.asStateFlow()

    val outboxItems: StateFlow<List<OutboxEntity>> = session
        .flatMapLatest { active ->
            if (active == null) flowOf(emptyList()) else outboxDao.observeForUser(active.localId)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    private var chatThreadPollJob: Job? = null
    private var inboxPollJob: Job? = null

    init {
        // React to background Outbox upload completions
        viewModelScope.launch {
            outboxManager.completedEvents.collect { event ->
                showBanner(if (event.isPrivate) "Đã lưu vào Kho riêng tư 🔒" else "Đã đăng khoảnh khắc lên Locket ✨")
                val currentFilter = _feedState.value.filterKey
                if (event.moment != null) {
                    val shouldPrepend = (event.isPrivate && currentFilter == FeedUiState.FILTER_PRIVATE) ||
                        (!event.isPrivate && currentFilter == FeedUiState.FILTER_ALL)
                    if (shouldPrepend) {
                        _feedState.update { state ->
                            state.copy(moments = listOf(event.moment) + state.moments.filterNot { it.id == event.moment.id })
                        }
                    }
                    if (!event.isPrivate) {
                        _allMomentsForMemories.update { list ->
                            listOf(event.moment) + list.filterNot { it.id == event.moment.id }
                        }
                    }
                } else {
                    refreshFeed(reset = true)
                }
                recomputeStreak(event.streak, _streakState.value.totalLockets?.plus(1))
            }
        }

        // Bootstrap data whenever active session changes
        viewModelScope.launch {
            var lastUid: String? = null
            session.collect { s ->
                if (s != null && s.localId != lastUid) {
                    lastUid = s.localId
                    val (cachedAnchor, cachedLockets) = sessionManager.loadStreakAnchor(s.localId)
                    recomputeStreak(cachedAnchor, cachedLockets)
                    refreshEverything()
                    startInboxPolling()
                } else if (s == null) {
                    lastUid = null
                    inboxPollJob?.cancel()
                    chatThreadPollJob?.cancel()
                    _feedState.value = FeedUiState()
                    _socialState.value = SocialUiState()
                    _allMomentsForMemories.value = emptyList()
                }
            }
        }
    }

    fun selectTab(tab: RootTab) {
        _activeTab.value = tab
        if (tab == RootTab.FEED && _feedState.value.moments.isEmpty()) {
            refreshFeed(reset = true)
        } else if (tab == RootTab.CHAT) {
            refreshConversations()
        }
    }

    fun showBanner(message: String?) {
        _bannerMessage.value = message
    }

    fun dismissBanner() {
        _bannerMessage.value = null
    }

    // =========================================================================
    // 1. AUTHENTICATION & MULTI-ACCOUNT
    // =========================================================================

    fun login(email: String, password: String) {
        if (email.isBlank() || password.isBlank()) {
            showBanner("Vui lòng nhập đầy đủ Email và Mật khẩu Locket")
            return
        }
        viewModelScope.launch {
            _isLoggingIn.value = true
            try {
                apiClient.login(email.trim(), password)
                _activeTab.value = RootTab.CAMERA
                showBanner("Đăng nhập thành công!")
            } catch (e: Throwable) {
                showBanner(e.localizedMessage ?: "Đăng nhập thất bại")
            } finally {
                _isLoggingIn.value = false
            }
        }
    }

    fun switchAccount(uid: String) {
        val switched = sessionManager.switchToSavedAccount(uid)
        if (switched != null) {
            showBanner("Đã chuyển sang ${switched.label}")
            outboxManager.triggerWorker()
        }
    }

    fun removeSavedAccount(uid: String) {
        sessionManager.removeSavedAccount(uid)
    }

    fun logout() {
        sessionManager.logoutCurrentSession()
    }

    // =========================================================================
    // 2. PARALLEL BOOTSTRAP, FEED, PRIVATE VAULT & STREAK ENGINE
    // =========================================================================

    fun refreshEverything() {
        val uid = session.value?.localId ?: return
        viewModelScope.launch {
            coroutineScope {
                launch { refreshFeed(reset = true) }
                launch { refreshFriendsAndRequests(forceRefresh = false) }
                launch { refreshConversations() }
                launch { loadCaptionPresetsIfNeeded() }
                launch { apiClient.syncDonateStatus() }
                launch { runCatching { apiClient.upgradeLegacyStoredCredentials() } }
                launch {
                    runCatching { apiClient.getMemoriesStats() }.onSuccess { (anchor, total) ->
                        sessionManager.saveStreakAnchor(uid, anchor, total)
                        recomputeStreak(anchor, total)
                    }
                }
            }
        }
    }

    fun setFeedFilter(filterKey: String) {
        if (_feedState.value.filterKey == filterKey && _feedState.value.moments.isNotEmpty()) return
        _feedState.update { it.copy(filterKey = filterKey, moments = emptyList(), nextCursor = null) }
        refreshFeed(reset = true)
    }

    fun toggleFeedGridMode() {
        _feedState.update { it.copy(isGridMode = !it.isGridMode) }
    }

    fun refreshFeed(reset: Boolean = true) {
        if (session.value == null) return
        val current = _feedState.value
        if (reset && current.isLoading) return
        if (!reset && (current.isLoadingMore || current.nextCursor.isNullOrBlank())) return

        viewModelScope.launch {
            _feedState.update {
                if (reset) it.copy(isLoading = true) else it.copy(isLoadingMore = true)
            }
            try {
                val cursor = if (reset) null else current.nextCursor
                val page = when (current.filterKey) {
                    FeedUiState.FILTER_PRIVATE -> apiClient.getPrivateMoments(limit = 30, cursor = cursor)
                    FeedUiState.FILTER_ALL -> apiClient.getFeed(limit = 25, cursor = cursor, userUid = null)
                    else -> apiClient.getFeed(limit = 25, cursor = cursor, userUid = current.filterKey)
                }

                _feedState.update { prev ->
                    val mergedMoments = if (reset) {
                        page.moments
                    } else {
                        val existingIds = prev.moments.mapTo(HashSet()) { it.id }
                        prev.moments + page.moments.filterNot { it.id in existingIds }
                    }
                    prev.copy(
                        moments = mergedMoments,
                        usersMap = prev.usersMap + page.users,
                        nextCursor = page.nextCursor,
                        isLoading = false,
                        isLoadingMore = false
                    )
                }

                if (current.filterKey == FeedUiState.FILTER_ALL) {
                    _allMomentsForMemories.update { old ->
                        val merged = if (reset) page.moments else (old + page.moments).distinctBy { it.id }
                        merged
                    }
                    val uid = session.value?.localId
                    if (uid != null) {
                        val (anchor, total) = sessionManager.loadStreakAnchor(uid)
                        recomputeStreak(anchor, total)
                    }
                }
            } catch (e: Throwable) {
                _feedState.update { it.copy(isLoading = false, isLoadingMore = false) }
                showBanner(e.localizedMessage ?: "Không tải được bảng tin")
            }
        }
    }

    /**
     * Exact native port of Streak Calculator (module 2831 in 941.e2a6ca53.chunk.js).
     */
    private fun recomputeStreak(anchorOverride: StreakAnchor?, totalLockets: Int?) {
        val myUid = session.value?.localId ?: return
        val zone = ZoneId.systemDefault()
        val fmt = DateTimeFormatter.BASIC_ISO_DATE
        val today = LocalDate.now(zone)
        val todayInt = today.format(fmt).toInt()
        val yesterdayInt = today.minusDays(1).format(fmt).toInt()

        val myPostDays = _allMomentsForMemories.value
            .asSequence()
            .filter { !it.isPrivate && (it.user.isBlank() || it.user == myUid) && it.dateMillis > 0L }
            .map { Instant.ofEpochMilli(it.dateMillis).atZone(zone).toLocalDate() }
            .toSet()

        val postedTodayInFeed = today in myPostDays

        if (anchorOverride != null && anchorOverride.lastUpdatedYyyyMmDd > 0) {
            val anchorYmd = anchorOverride.lastUpdatedYyyyMmDd
            val postedToday = (anchorYmd == todayInt) || postedTodayInFeed
            val count = when {
                anchorYmd == todayInt -> anchorOverride.count
                anchorYmd == yesterdayInt && postedTodayInFeed -> anchorOverride.count + 1
                anchorYmd == yesterdayInt -> anchorOverride.count
                postedTodayInFeed -> 1
                else -> 0
            }
            val nextCaptionDay = when {
                postedToday -> count.coerceAtLeast(1)
                anchorYmd == yesterdayInt -> anchorOverride.count + 1
                else -> 1
            }
            _streakState.value = StreakState(
                count = count,
                nextCaptionDay = nextCaptionDay,
                postedToday = postedToday,
                lastYyyyMmDd = anchorYmd,
                totalLockets = totalLockets ?: _streakState.value.totalLockets
            )
            return
        }

        // Fallback streak walk across consecutive local calendar days
        var streak = 0
        var cursorDay = if (postedTodayInFeed) today else today.minusDays(1)
        while (cursorDay in myPostDays) {
            streak++
            cursorDay = cursorDay.minusDays(1)
        }
        _streakState.value = StreakState(
            count = streak,
            nextCaptionDay = if (postedTodayInFeed) streak.coerceAtLeast(1) else streak + 1,
            postedToday = postedTodayInFeed,
            lastYyyyMmDd = if (postedTodayInFeed) todayInt else null,
            totalLockets = totalLockets ?: _streakState.value.totalLockets
        )
    }

    // =========================================================================
    // 3. NON-BLOCKING OUTBOX UPLOAD QUEUE
    // =========================================================================

    fun enqueueMediaToOutbox(
        file: File,
        isVideo: Boolean,
        isPrivate: Boolean,
        filterId: String,
        caption: String,
        overlay: CaptionOverlayConfig,
        recipients: List<String>,
        perks: List<String> = emptyList(),
        alreadyPrepared: Boolean = false
    ) {
        viewModelScope.launch {
            try {
                outboxManager.enqueueMoment(
                    sourceFile = file,
                    isVideo = isVideo,
                    isPrivate = isPrivate,
                    filterId = filterId,
                    caption = caption,
                    overlay = overlay,
                    recipients = recipients,
                    perks = perks,
                    alreadyPrepared = alreadyPrepared
                )
                showBanner(
                    if (isVideo) "Đang xử lý GPU & tải video dưới nền..."
                    else "Đang gửi ảnh Locket dưới nền..."
                )
            } catch (e: Throwable) {
                showBanner(e.localizedMessage ?: "Không thể xếp hàng tải lên")
            }
        }
    }

    fun retryOutboxItem(id: String) {
        viewModelScope.launch {
            outboxManager.retryFailedItem(id)
        }
    }

    fun cancelOutboxItem(id: String) {
        viewModelScope.launch {
            outboxManager.cancelAndDeleteItem(id)
        }
    }

    // =========================================================================
    // 4. MOMENT INTERACTIONS, DOWNLOAD & SHARE
    // =========================================================================

    fun reactToMoment(moment: Moment, emoji: String) {
        if (moment.isPrivate) return
        viewModelScope.launch {
            runCatching { apiClient.reactToMoment(moment.id, emoji) }
                .onSuccess { showBanner("Đã thả $emoji") }
                .onFailure { showBanner(it.localizedMessage ?: "Không thả được cảm xúc") }
        }
    }

    fun replyToMoment(moment: Moment, text: String) {
        if (moment.isPrivate || text.isBlank() || moment.user.isBlank()) return
        viewModelScope.launch {
            runCatching { apiClient.commentOnMoment(moment.id, moment.user, text.trim()) }
                .onSuccess {
                    showBanner("Đã gửi phản hồi tới bạn bè 💬")
                    refreshConversations()
                }
                .onFailure { showBanner(it.localizedMessage ?: "Không gửi được phản hồi") }
        }
    }

    fun markMomentViewed(moment: Moment) {
        val myUid = session.value?.localId ?: return
        if (moment.isPrivate || moment.user == myUid || moment.id.isBlank()) return
        viewModelScope.launch {
            apiClient.markMomentViewed(moment.id, moment.date)
        }
    }

    fun pruneGoneMoment(momentId: String) {
        if (momentId.isBlank()) return
        _feedState.update { st -> st.copy(moments = st.moments.filterNot { it.id == momentId }) }
        _allMomentsForMemories.update { list -> list.filterNot { it.id == momentId } }
    }

    fun onMomentMediaError(moment: Moment, failedUrl: String?) {
        if (moment.isPrivate || moment.localStatus != null || failedUrl.isNullOrBlank()) return
        viewModelScope.launch {
            if (apiClient.checkFirebaseMediaGone(failedUrl)) {
                pruneGoneMoment(moment.id)
            }
        }
    }

    fun openMomentActivity(momentId: String) {
        viewModelScope.launch {
            _feedState.update { it.copy(activeActivityMomentId = momentId, activeActivity = null) }
            runCatching { apiClient.getMomentActivity(momentId) }
                .onSuccess { act ->
                    _feedState.update { it.copy(activeActivity = act) }
                }
                .onFailure { err ->
                    _feedState.update { it.copy(activeActivityMomentId = null, activeActivity = null) }
                    if ((err as? LocketApiException)?.errorCode == "MOMENT_GONE") {
                        pruneGoneMoment(momentId)
                    } else {
                        showBanner(err.localizedMessage ?: "Không tải được lượt xem")
                    }
                }
        }
    }

    fun closeMomentActivity() {
        _feedState.update { it.copy(activeActivityMomentId = null, activeActivity = null) }
    }

    fun deleteMoment(moment: Moment) {
        val myUid = session.value?.localId ?: return
        viewModelScope.launch {
            val result = runCatching {
                if (moment.isPrivate) {
                    apiClient.deletePrivateMoment(moment.id)
                } else {
                    apiClient.deleteMoment(moment.id, moment.user.ifBlank { myUid })
                }
            }
            result.onSuccess {
                _feedState.update { st -> st.copy(moments = st.moments.filterNot { it.id == moment.id }) }
                _allMomentsForMemories.update { list -> list.filterNot { it.id == moment.id } }
                showBanner("Đã xóa khoảnh khắc")
            }.onFailure {
                showBanner(it.localizedMessage ?: "Xóa thất bại")
            }
        }
    }

    fun saveOrShareMomentMedia(context: Context, moment: Moment, shareAfterDownload: Boolean) {
        val mediaUrl = moment.videoUrl?.takeIf { it.isNotBlank() } ?: moment.thumbnailUrl
        if (mediaUrl.isNullOrBlank()) {
            showBanner("Khoảnh khắc này chưa có đường dẫn tệp")
            return
        }
        viewModelScope.launch {
            showBanner(if (shareAfterDownload) "Đang chuẩn bị tệp để chia sẻ..." else "Đang lưu về máy...")
            try {
                val ext = if (moment.isVideo) "mp4" else "jpg"
                val mime = if (moment.isVideo) "video/mp4" else "image/jpeg"
                val shareDir = File(context.cacheDir, "shared").apply { mkdirs() }
                val downloaded = File(shareDir, "locket_${moment.id.takeLast(8)}.$ext")
                apiClient.downloadMediaToFile(mediaUrl, moment.isPrivate, downloaded)

                if (shareAfterDownload) {
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        downloaded
                    )
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = mime
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "Chia sẻ Locket"))
                } else {
                    saveFileToGallery(context, downloaded, mime, moment.isVideo)
                    showBanner("Đã lưu vào Thư viện máy!")
                }
            } catch (e: Throwable) {
                showBanner(e.localizedMessage ?: "Không tải được tệp về máy")
            }
        }
    }

    private suspend fun saveFileToGallery(
        context: Context,
        file: File,
        mimeType: String,
        isVideo: Boolean
    ) = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val collection = if (isVideo) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val folder = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/LocketPhotobooth")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val itemUri = resolver.insert(collection, values)
            ?: throw IllegalStateException("Không tạo được mục lưu trữ trong Thư viện")

        resolver.openOutputStream(itemUri)?.use { out ->
            file.inputStream().use { input -> input.copyTo(out) }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(itemUri, values, null, null)
        }
    }

    // =========================================================================
    // 5. CAPTION PRESETS, GPS WEATHER & NEARBY PLACES
    // =========================================================================

    private suspend fun loadCaptionPresetsIfNeeded() {
        if (_overlayLookup.value.presets.isNotEmpty()) return
        runCatching { apiClient.getCaptionPresets() }.onSuccess { list ->
            _overlayLookup.update { it.copy(presets = list) }
        }
    }

    fun fetchLocationOverlays(context: Context) {
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) {
            showBanner("Hãy cấp quyền Vị trí để gắn Thời tiết & Địa điểm quanh đây")
            return
        }

        viewModelScope.launch {
            _overlayLookup.update { it.copy(isLoadingLocation = true) }
            try {
                val loc = resolveBestLastKnownLocation(context)
                if (loc == null) {
                    showBanner("Chưa lấy được tọa độ GPS, hãy bật Định vị trên điện thoại")
                    _overlayLookup.update { it.copy(isLoadingLocation = false) }
                    return@launch
                }
                coroutineScope {
                    val weatherDef = async { runCatching { apiClient.getCaptionWeather(loc.latitude, loc.longitude) }.getOrNull() }
                    val placesDef = async { runCatching { apiClient.getCaptionPlaces(loc.latitude, loc.longitude) }.getOrDefault(emptyList()) }
                    _overlayLookup.update {
                        it.copy(
                            weather = weatherDef.await() ?: it.weather,
                            places = placesDef.await(),
                            isLoadingLocation = false
                        )
                    }
                }
            } catch (e: Throwable) {
                _overlayLookup.update { it.copy(isLoadingLocation = false) }
                showBanner(e.localizedMessage ?: "Không tải được dữ liệu vị trí")
            }
        }
    }

    private fun resolveBestLastKnownLocation(context: Context): Location? {
        return runCatching {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = lm.getProviders(true)
            var best: Location? = null
            for (p in providers) {
                @Suppress("MissingPermission")
                val candidate = lm.getLastKnownLocation(p) ?: continue
                if (best == null || candidate.accuracy < best.accuracy) {
                    best = candidate
                }
            }
            best
        }.getOrNull()
    }

    // =========================================================================
    // 6. FRIENDS, @USERNAME SEARCH & DIRECT MESSAGES (CHAT)
    // =========================================================================

    fun refreshFriendsAndRequests(forceRefresh: Boolean = true) {
        if (session.value == null) return
        viewModelScope.launch {
            coroutineScope {
                val fDef = async { runCatching { apiClient.getFriends(forceRefresh) }.getOrNull() }
                val rDef = async { runCatching { apiClient.getFriendRequests(forceRefresh) }.getOrNull() }
                val friendsPair = fDef.await()
                val reqs = rDef.await()
                _socialState.update { prev ->
                    prev.copy(
                        friends = friendsPair?.first ?: prev.friends,
                        meProfile = friendsPair?.second ?: prev.meProfile,
                        requests = reqs ?: prev.requests
                    )
                }
            }
        }
    }

    fun searchUserByUsername(username: String) {
        if (username.isBlank()) return
        viewModelScope.launch {
            _socialState.update { it.copy(isSearchingUser = true, searchedUser = null) }
            runCatching { apiClient.getUserByUsername(username) }
                .onSuccess { user ->
                    _socialState.update { it.copy(isSearchingUser = false, searchedUser = user) }
                    if (user == null) showBanner("Không tìm thấy người dùng @${username.removePrefix("@")}")
                }
                .onFailure {
                    _socialState.update { it.copy(isSearchingUser = false) }
                    showBanner(it.localizedMessage ?: "Lỗi tìm kiếm người dùng")
                }
        }
    }

    fun performFriendAction(targetUid: String, action: String) {
        viewModelScope.launch {
            runCatching { apiClient.performFriendAction(targetUid, action) }
                .onSuccess {
                    val msg = when (action) {
                        "send" -> "Đã gửi lời mời kết bạn"
                        "accept" -> "Đã chấp nhận lời mời kết bạn"
                        "remove" -> "Đã xóa khỏi danh sách bạn bè"
                        "block" -> "Đã chặn người dùng"
                        else -> "Đã cập nhật bạn bè"
                    }
                    showBanner(msg)
                    refreshFriendsAndRequests(forceRefresh = true)
                }
                .onFailure { showBanner(it.localizedMessage ?: "Thao tác thất bại") }
        }
    }

    private fun startInboxPolling() {
        inboxPollJob?.cancel()
        inboxPollJob = viewModelScope.launch {
            while (isActive) {
                delay(15_000L)
                if (session.value != null) {
                    runCatching { apiClient.getConversations() }.onSuccess { (convs, users) ->
                        _socialState.update { it.copy(conversations = convs, chatUsers = it.chatUsers + users) }
                    }
                }
            }
        }
    }

    fun refreshConversations() {
        if (session.value == null) return
        viewModelScope.launch {
            runCatching { apiClient.getConversations() }.onSuccess { (convs, users) ->
                _socialState.update { it.copy(conversations = convs, chatUsers = it.chatUsers + users) }
            }
        }
    }

    fun openChatConversation(peerUid: String, conversationId: String?) {
        chatThreadPollJob?.cancel()
        val resolvedConvId = conversationId
            ?: _socialState.value.conversations.firstOrNull { it.peer == peerUid }?.id

        _socialState.update {
            it.copy(
                activePeerUid = peerUid,
                activeConversationId = resolvedConvId,
                activeMessages = emptyList()
            )
        }

        if (resolvedConvId != null) {
            chatThreadPollJob = viewModelScope.launch {
                while (isActive) {
                    runCatching { apiClient.getMessages(resolvedConvId, limit = 40) }.onSuccess { (msgs, _) ->
                        _socialState.update { it.copy(activeMessages = msgs.sortedBy { m -> m.createdAt }) }
                        apiClient.markConversationRead(resolvedConvId)
                    }
                    delay(4_000L)
                }
            }
        }
    }

    fun closeChatConversation() {
        chatThreadPollJob?.cancel()
        _socialState.update {
            it.copy(activeConversationId = null, activePeerUid = null, activeMessages = emptyList())
        }
        refreshConversations()
    }

    fun sendDirectMessage(receiverUid: String, text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            _socialState.update { it.copy(isSendingMessage = true) }
            runCatching { apiClient.sendMessage(receiverUid, trimmed) }
                .onSuccess { newConvId ->
                    _socialState.update { it.copy(isSendingMessage = false) }
                    val convId = newConvId ?: _socialState.value.activeConversationId
                    if (!convId.isNullOrBlank()) {
                        openChatConversation(receiverUid, convId)
                    }
                    refreshConversations()
                }
                .onFailure {
                    _socialState.update { it.copy(isSendingMessage = false) }
                    showBanner(it.localizedMessage ?: "Gửi tin nhắn thất bại")
                }
        }
    }

    fun reactToChatMessage(conversationId: String, messageId: String, emoji: String, remove: Boolean) {
        viewModelScope.launch {
            runCatching { apiClient.reactToMessage(conversationId, messageId, emoji, remove) }
                .onSuccess {
                    runCatching { apiClient.getMessages(conversationId, limit = 40) }.onSuccess { (msgs, _) ->
                        _socialState.update { it.copy(activeMessages = msgs.sortedBy { m -> m.createdAt }) }
                    }
                }
        }
    }

    fun deleteChatMessage(conversationId: String, messageId: String) {
        viewModelScope.launch {
            runCatching { apiClient.deleteMessage(conversationId, messageId) }
                .onSuccess {
                    _socialState.update { st ->
                        st.copy(activeMessages = st.activeMessages.filterNot { it.id == messageId })
                    }
                }
                .onFailure { showBanner(it.localizedMessage ?: "Không gỡ được tin nhắn") }
        }
    }

    // =========================================================================
    // 7. PROFILE, 14 THEMES & LEGITIMATE SUPPORTER STATUS
    // =========================================================================

    fun updateAccountName(firstName: String, lastName: String) {
        if (firstName.isBlank()) {
            showBanner("Tên không được để trống")
            return
        }
        viewModelScope.launch {
            runCatching { apiClient.changeAccountName(firstName, lastName) }
                .onSuccess {
                    showBanner("Đã cập nhật tên hiển thị!")
                    refreshFriendsAndRequests(forceRefresh = true)
                }
                .onFailure { showBanner(it.localizedMessage ?: "Đổi tên thất bại") }
        }
    }

    fun updateAvatarFromUri(context: Context, uri: Uri) {
        viewModelScope.launch {
            showBanner("Đang nén 1:1 & cập nhật ảnh đại diện...")
            try {
                val raw = PhotoProcessor.copyUriToTempFile(context, uri, "avatar")
                val square = PhotoProcessor.processImageFile(context, raw, filterId = "none", forPrivate = false)
                apiClient.changeAvatar(square)
                raw.delete()
                if (square.exists()) square.delete()
                showBanner("Đã đổi ảnh đại diện!")
                refreshFriendsAndRequests(forceRefresh = true)
            } catch (e: Throwable) {
                showBanner(e.localizedMessage ?: "Đổi ảnh đại diện thất bại")
            }
        }
    }

    fun setTheme(id: String) = sessionManager.setTheme(id)

    fun setGlassStyle(glass: Boolean) = sessionManager.setGlassStyle(glass)

    fun setShowCardsButton(show: Boolean) = sessionManager.setShowCardsButton(show)

    fun setShowAccountsButton(show: Boolean) = sessionManager.setShowAccountsButton(show)

    fun refreshTotalOutboxCount() {
        viewModelScope.launch(Dispatchers.IO) {
            _totalOutboxCount.value = runCatching { outboxDao.countAllItems() }.getOrDefault(0)
        }
    }

    fun clearAllDeviceData(context: Context) {
        viewModelScope.launch {
            inboxPollJob?.cancel()
            chatThreadPollJob?.cancel()
            withContext(Dispatchers.IO) {
                runCatching { outboxDao.deleteAll() }
                runCatching { File(context.filesDir, "outbox_media").deleteRecursively() }
                runCatching { File(context.cacheDir, "coil_media_cache").deleteRecursively() }
                runCatching { File(context.cacheDir, "shared").deleteRecursively() }
            }
            sessionManager.clearAllDeviceData()
            _feedState.value = FeedUiState()
            _socialState.value = SocialUiState()
            _allMomentsForMemories.value = emptyList()
            _streakState.value = StreakState()
            _totalOutboxCount.value = 0
            showBanner("Đã xoá toàn bộ dữ liệu trên thiết bị này")
        }
    }

    fun loadSupporterDetails() {
        viewModelScope.launch {
            _supporterSheet.update { it.copy(isLoading = true) }
            coroutineScope {
                val meDef = async { runCatching { apiClient.getDonateMe() }.getOrNull() }
                val listDef = async { runCatching { apiClient.getDonateList() }.getOrDefault(emptyList()) }
                launch { apiClient.syncDonateStatus() }
                _supporterSheet.update {
                    it.copy(
                        donateMe = meDef.await() ?: it.donateMe,
                        leaderboard = listDef.await(),
                        isLoading = false
                    )
                }
            }
        }
    }

    fun updateDonorDisplayName(displayName: String) {
        if (displayName.isBlank()) return
        viewModelScope.launch {
            runCatching { apiClient.setDonateDisplayName(displayName) }
                .onSuccess {
                    showBanner("Đã lưu tên vinh danh Supporter")
                    loadSupporterDetails()
                }
                .onFailure { showBanner(it.localizedMessage ?: "Không lưu được tên") }
        }
    }

    companion object {
        fun factory(application: LocketApplication): ViewModelProvider.Factory {
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    return LocketViewModel(application, application.container) as T
                }
            }
        }
    }
}

