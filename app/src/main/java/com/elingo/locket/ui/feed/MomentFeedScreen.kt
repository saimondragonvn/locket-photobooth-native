package com.elingo.locket.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.elingo.locket.core.model.FriendUser
import com.elingo.locket.core.model.Moment
import com.elingo.locket.core.model.MomentActivity
import com.elingo.locket.ui.FeedUiState
import com.elingo.locket.ui.auth.AccountAvatarCircle
import com.elingo.locket.ui.theme.LocalLocketPalette
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun MomentFeedScreen(
    myUid: String,
    feedState: FeedUiState,
    friends: List<FriendUser>,
    onSelectFilter: (String) -> Unit,
    onToggleGridMode: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onReact: (Moment, String) -> Unit,
    onReply: (Moment, String) -> Unit,
    onMarkViewed: (Moment) -> Unit,
    onOpenActivity: (String) -> Unit,
    onCloseActivity: () -> Unit,
    onDeleteMoment: (Moment) -> Unit,
    onDownloadMoment: (Moment) -> Unit,
    onShareMoment: (Moment) -> Unit,
    onMomentMediaError: (Moment, String?) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val palette = LocalLocketPalette.current
    val scope = rememberCoroutineScope()

    val pagerState = rememberPagerState(pageCount = { feedState.moments.size })

    // Single shared Hardware ExoPlayer instance for the entire VerticalPager to prevent MediaCodec exhaustion!
    val sharedExoPlayer = remember {
        ExoPlayer.Builder(context.applicationContext).build().apply {
            repeatMode = Player.REPEAT_MODE_ONE
            playWhenReady = true
        }
    }

    val settledIndex = pagerState.settledPage
    val settledMoment = feedState.moments.getOrNull(settledIndex)

    DisposableEffect(sharedExoPlayer, settledMoment?.id) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val active = settledMoment
                if (active != null && active.isVideo) {
                    onMomentMediaError(active, active.videoUrl)
                }
            }
        }
        sharedExoPlayer.addListener(listener)
        onDispose {
            sharedExoPlayer.removeListener(listener)
        }
    }

    DisposableEffect(Unit) {
        onDispose { sharedExoPlayer.release() }
    }

    LaunchedEffect(settledMoment?.id, feedState.isGridMode) {
        if (feedState.isGridMode || settledMoment == null || !settledMoment.isVideo) {
            sharedExoPlayer.stop()
            sharedExoPlayer.clearMediaItems()
        } else {
            val videoUrl = settledMoment.videoUrl.orEmpty()
            sharedExoPlayer.setMediaItem(MediaItem.fromUri(videoUrl))
            sharedExoPlayer.prepare()
            sharedExoPlayer.playWhenReady = true
        }

        if (settledMoment != null) {
            onMarkViewed(settledMoment)
        }
        if (settledIndex >= feedState.moments.size - 3 && !feedState.nextCursor.isNullOrBlank()) {
            onLoadMore()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.spec.background)
    ) {
        // 1. Top Filter Bar + Grid/Pager Switch + Refresh
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = feedState.filterKey == FeedUiState.FILTER_ALL,
                    onClick = { onSelectFilter(FeedUiState.FILTER_ALL) },
                    label = { Text("Tất cả") }
                )
                FilterChip(
                    selected = feedState.filterKey == FeedUiState.FILTER_PRIVATE,
                    onClick = { onSelectFilter(FeedUiState.FILTER_PRIVATE) },
                    label = { Text("🔒 Riêng tư") }
                )
                friends.forEach { f ->
                    FilterChip(
                        selected = feedState.filterKey == f.uid,
                        onClick = { onSelectFilter(f.uid) },
                        label = { Text(f.firstName.ifBlank { f.fullName }) }
                    )
                }
            }

            IconButton(onClick = onToggleGridMode) {
                Icon(
                    imageVector = if (feedState.isGridMode) Icons.Default.ViewAgenda else Icons.Default.GridView,
                    contentDescription = "Đổi chế độ xem"
                )
            }
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = "Tải lại")
            }
        }

        // 2. Content Area
        when {
            feedState.isLoading && feedState.moments.isEmpty() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = palette.spec.accent)
                }
            }
            feedState.moments.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (feedState.filterKey == FeedUiState.FILTER_PRIVATE)
                            "Kho riêng tư chưa có ảnh hoặc video nào."
                        else
                            "Chưa có khoảnh khắc Locket nào trong mục này.",
                        color = palette.mutedText
                    )
                }
            }
            feedState.isGridMode -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(feedState.moments, key = { _, m -> m.id }) { index, moment ->
                        if (index >= feedState.moments.size - 4 && !feedState.nextCursor.isNullOrBlank()) {
                            LaunchedEffect(index) { onLoadMore() }
                        }
                        Box(
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(14.dp))
                                .background(palette.cardContainer)
                                .clickable {
                                    onToggleGridMode()
                                    scope.launch { pagerState.scrollToPage(index) }
                                }
                        ) {
                            val tileMediaUrl = moment.thumbnailUrl ?: moment.videoUrl
                            AsyncImage(
                                model = tileMediaUrl,
                                contentDescription = moment.caption,
                                contentScale = ContentScale.Crop,
                                onError = { onMomentMediaError(moment, tileMediaUrl) },
                                modifier = Modifier.fillMaxSize()
                            )
                            if (moment.isVideo) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Video",
                                    tint = Color.White,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(6.dp)
                                        .size(18.dp)
                                        .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                                        .padding(2.dp)
                                )
                            }
                        }
                    }
                }
            }
            else -> {
                VerticalPager(
                    state = pagerState,
                    beyondViewportPageCount = 1,
                    modifier = Modifier.fillMaxSize()
                ) { pageIdx ->
                    val moment = feedState.moments[pageIdx]
                    val author = feedState.usersMap[moment.user]
                        ?: friends.firstOrNull { it.uid == moment.user }
                    val isMine = moment.isPrivate || moment.user.isBlank() || moment.user == myUid

                    SingleMomentCardPage(
                        moment = moment,
                        author = author,
                        isMine = isMine,
                        isSettledActivePage = (pageIdx == settledIndex),
                        sharedPlayer = sharedExoPlayer,
                        onReact = { emoji -> onReact(moment, emoji) },
                        onReply = { txt -> onReply(moment, txt) },
                        onOpenActivity = { onOpenActivity(moment.id) },
                        onDelete = { onDeleteMoment(moment) },
                        onDownload = { onDownloadMoment(moment) },
                        onShare = { onShareMoment(moment) },
                        onMediaError = { failedUrl -> onMomentMediaError(moment, failedUrl) }
                    )
                }
            }
        }
    }

    if (feedState.activeActivityMomentId != null) {
        MomentActivityBottomSheet(
            activity = feedState.activeActivity,
            onDismiss = onCloseActivity
        )
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun SingleMomentCardPage(
    moment: Moment,
    author: FriendUser?,
    isMine: Boolean,
    isSettledActivePage: Boolean,
    sharedPlayer: ExoPlayer,
    onReact: (String) -> Unit,
    onReply: (String) -> Unit,
    onOpenActivity: () -> Unit,
    onDelete: () -> Unit,
    onDownload: () -> Unit,
    onShare: () -> Unit,
    onMediaError: (String?) -> Unit = {}
) {
    val palette = LocalLocketPalette.current
    var replyText by remember(moment.id) { mutableStateOf("") }
    val emojis = listOf("❤️", "🔥", "😂", "😮", "🥺", "👏")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 1:1 Square Locket Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(36.dp))
                .background(Color(0xFF121318))
                .border(1.5.dp, palette.cardBorder, RoundedCornerShape(36.dp))
        ) {
            val cardThumbUrl = moment.thumbnailUrl ?: moment.videoUrl
            AsyncImage(
                model = cardThumbUrl,
                contentDescription = moment.caption,
                contentScale = ContentScale.Crop,
                onError = { onMediaError(cardThumbUrl) },
                modifier = Modifier.fillMaxSize()
            )

            if (moment.isVideo && isSettledActivePage) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                            player = sharedPlayer
                        }
                    },
                    update = { it.player = sharedPlayer },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Overlay Caption Pill
            val displayTxt = moment.overlay.text.ifBlank { moment.caption }
            if (displayTxt.isNotBlank() || !moment.overlay.icon.isNullOrBlank()) {
                val colors = moment.overlay.backgroundColors
                val brush = if (colors.size >= 2) {
                    val c1 = runCatching { Color(android.graphics.Color.parseColor(colors[0])) }.getOrDefault(Color(0xB3000000))
                    val c2 = runCatching { Color(android.graphics.Color.parseColor(colors[1])) }.getOrDefault(c1)
                    Brush.horizontalGradient(listOf(c1, c2))
                } else {
                    Brush.horizontalGradient(listOf(Color(0xB3000000), Color(0xB3000000)))
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(horizontal = 18.dp, vertical = 16.dp)
                        .clip(RoundedCornerShape(50))
                        .background(brush)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    if (!moment.overlay.icon.isNullOrBlank()) {
                        Text(moment.overlay.icon, fontSize = 14.sp)
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = displayTxt,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Author + Timestamp + Actions Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val authorName = when {
                moment.isPrivate -> "Kho riêng tư của bạn 🔒"
                isMine -> "Bạn"
                else -> author?.fullName ?: "Bạn bè"
            }
            AccountAvatarCircle(
                avatarUrl = author?.profilePictureUrl,
                label = authorName,
                sizeDp = 36
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(authorName, fontWeight = FontWeight.Bold, fontSize = 14.5.sp)
                Text(formatMomentTimestamp(moment.dateMillis), fontSize = 12.sp, color = palette.mutedText)
            }

            if (isMine && !moment.isPrivate) {
                IconButton(onClick = onOpenActivity) {
                    Icon(Icons.Default.Visibility, contentDescription = "Lượt xem")
                }
            }
            IconButton(onClick = onDownload) {
                Icon(Icons.Default.Download, contentDescription = "Lưu về máy")
            }
            IconButton(onClick = onShare) {
                Icon(Icons.Default.Share, contentDescription = "Chia sẻ")
            }
            if (isMine) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "Xóa", tint = Color(0xFFFF6B7A))
                }
            }
        }

        // Reactions & Quick Reply Bar for Friend Moments
        if (!isMine && !moment.isPrivate) {
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                emojis.forEach { emoji ->
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(palette.cardContainer)
                            .clickable { onReact(emoji) }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(emoji, fontSize = 18.sp)
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = replyText,
                    onValueChange = { replyText = it },
                    placeholder = { Text("Gửi tin nhắn phản hồi...", fontSize = 13.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = {
                        if (replyText.isNotBlank()) {
                            onReply(replyText)
                            replyText = ""
                        }
                    },
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(palette.spec.accent)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Gửi",
                        tint = palette.spec.onAccent
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MomentActivityBottomSheet(
    activity: MomentActivity?,
    onDismiss: () -> Unit
) {
    val palette = LocalLocketPalette.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = palette.spec.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Text("Hoạt động khoảnh khắc", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(modifier = Modifier.height(10.dp))
            if (activity == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = palette.spec.accent)
                }
            } else {
                Text(
                    text = "👁 ${activity.views} lượt xem  •  ❤️ ${activity.reactions} cảm xúc",
                    color = palette.spec.accent,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(12.dp))
                if (activity.people.isEmpty()) {
                    Text("Chưa có bạn bè nào xem khoảnh khắc này.", color = palette.mutedText)
                } else {
                    activity.people.forEach { person ->
                        val u = activity.users[person.user]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AccountAvatarCircle(
                                avatarUrl = u?.profilePictureUrl,
                                label = u?.fullName ?: person.user,
                                sizeDp = 38
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = u?.fullName ?: "Bạn bè",
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = person.reactions.joinToString(" ") { it.emoji }.ifBlank { "Đã xem" },
                                fontSize = 15.sp
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
        }
    }
}

private fun formatMomentTimestamp(epochMs: Long): String {
    if (epochMs <= 0L) return "Vừa xong"
    return runCatching {
        Instant.ofEpochMilli(epochMs)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("HH:mm • dd/MM/yyyy"))
    }.getOrDefault("Vừa xong")
}

