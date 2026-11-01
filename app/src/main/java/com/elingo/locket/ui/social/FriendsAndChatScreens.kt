package com.elingo.locket.ui.social

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.elingo.locket.core.model.ChatMessage
import com.elingo.locket.core.model.FriendUser
import com.elingo.locket.ui.SocialUiState
import com.elingo.locket.ui.auth.AccountAvatarCircle
import com.elingo.locket.ui.theme.LocalLocketPalette

@Composable
fun ChatAndFriendsScreen(
    myUid: String,
    myUsername: String,
    socialState: SocialUiState,
    onSearchUsername: (String) -> Unit,
    onFriendAction: (targetUid: String, action: String) -> Unit,
    onOpenConversation: (peerUid: String, convId: String?) -> Unit,
    onCloseConversation: () -> Unit,
    onSendMessage: (receiverUid: String, text: String) -> Unit,
    onReactMessage: (convId: String, msgId: String, emoji: String, remove: Boolean) -> Unit,
    onDeleteMessage: (convId: String, msgId: String) -> Unit
) {
    val palette = LocalLocketPalette.current
    var subTab by remember { mutableIntStateOf(0) } // 0 = Inbox, 1 = Friends

    // Active Direct Message Thread View
    if (!socialState.activePeerUid.isNullOrBlank()) {
        val peerUid = socialState.activePeerUid
        val peer = socialState.chatUsers[peerUid]
            ?: socialState.friends.firstOrNull { it.uid == peerUid }

        DirectMessageThreadView(
            myUid = myUid,
            peerUid = peerUid,
            peer = peer,
            conversationId = socialState.activeConversationId,
            messages = socialState.activeMessages,
            isSending = socialState.isSendingMessage,
            onBack = onCloseConversation,
            onSend = { txt -> onSendMessage(peerUid, txt) },
            onReact = { msgId, emoji, remove ->
                socialState.activeConversationId?.let { cId ->
                    onReactMessage(cId, msgId, emoji, remove)
                }
            },
            onDelete = { msgId ->
                socialState.activeConversationId?.let { cId ->
                    onDeleteMessage(cId, msgId)
                }
            }
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.spec.background)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        // Sub-Tab Selector: Hộp thư | Bạn bè
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            FilterChip(
                selected = subTab == 0,
                onClick = { subTab = 0 },
                label = {
                    val unread = socialState.unreadConversationsCount
                    Text(if (unread > 0) "Tin nhắn ($unread)" else "Tin nhắn")
                }
            )
            FilterChip(
                selected = subTab == 1,
                onClick = { subTab = 1 },
                label = {
                    val reqCount = socialState.requests.incoming.size
                    Text(if (reqCount > 0) "Bạn bè (${socialState.friends.size}) • +$reqCount" else "Bạn bè (${socialState.friends.size})")
                }
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (subTab == 0) {
            ConversationsInboxList(
                socialState = socialState,
                onOpenConversation = onOpenConversation,
                onSwitchToFriends = { subTab = 1 }
            )
        } else {
            FriendsManagementView(
                myUsername = myUsername,
                socialState = socialState,
                onSearchUsername = onSearchUsername,
                onFriendAction = onFriendAction,
                onStartChat = { uid ->
                    subTab = 0
                    onOpenConversation(uid, null)
                }
            )
        }
    }
}

@Composable
private fun ConversationsInboxList(
    socialState: SocialUiState,
    onOpenConversation: (peerUid: String, convId: String?) -> Unit,
    onSwitchToFriends: () -> Unit
) {
    val palette = LocalLocketPalette.current
    if (socialState.conversations.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Chưa có cuộc trò chuyện nào.", color = palette.mutedText)
            Spacer(modifier = Modifier.height(12.dp))
            TextButton(onClick = onSwitchToFriends) {
                Text("Chọn bạn bè để bắt đầu nhắn tin", color = palette.spec.accent, fontWeight = FontWeight.Bold)
            }
        }
        return
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(socialState.conversations, key = { it.id }) { conv ->
            val peer = socialState.chatUsers[conv.peer]
                ?: socialState.friends.firstOrNull { it.uid == conv.peer }
            val name = peer?.fullName ?: "Bạn bè"
            val preview = conv.latest?.cleanBody?.ifBlank { "📷 Đã gửi phản hồi khoảnh khắc" }
                ?: "Chạm để mở tin nhắn"

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(palette.cardContainer)
                    .border(
                        width = 1.dp,
                        color = if (conv.hasUnread) palette.spec.accent else palette.cardBorder,
                        shape = RoundedCornerShape(18.dp)
                    )
                    .clickable { onOpenConversation(conv.peer, conv.id) }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AccountAvatarCircle(
                    avatarUrl = peer?.profilePictureUrl,
                    label = name,
                    sizeDp = 46
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = name,
                        fontWeight = if (conv.hasUnread) FontWeight.ExtraBold else FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = preview,
                        fontSize = 13.sp,
                        color = if (conv.hasUnread) Color.White else palette.mutedText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (conv.hasUnread) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(palette.spec.accent)
                    )
                }
            }
        }
    }
}

@Composable
private fun DirectMessageThreadView(
    myUid: String,
    peerUid: String,
    peer: FriendUser?,
    conversationId: String?,
    messages: List<ChatMessage>,
    isSending: Boolean,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onReact: (msgId: String, emoji: String, remove: Boolean) -> Unit,
    onDelete: (msgId: String) -> Unit
) {
    val palette = LocalLocketPalette.current
    var draft by remember { mutableStateOf("") }
    val peerName = peer?.fullName ?: peerUid.take(8)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.spec.background)
            .imePadding()
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(palette.spec.surface)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lại")
            }
            AccountAvatarCircle(avatarUrl = peer?.profilePictureUrl, label = peerName, sizeDp = 36)
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(peerName, fontWeight = FontWeight.Bold, fontSize = 15.5.sp)
                if (!peer?.username.isNullOrBlank()) {
                    Text("@${peer?.username}", fontSize = 11.5.sp, color = palette.mutedText)
                }
            }
        }

        // Messages List
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages, key = { it.id }) { msg ->
                val isMine = msg.sender == myUid
                val myHeart = msg.reactions.any { it.sender == myUid && it.emoji == "❤️" }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = if (isMine) Alignment.End else Alignment.Start
                ) {
                    if (!msg.thumbnailUrl.isNullOrBlank()) {
                        AsyncImage(
                            model = msg.thumbnailUrl,
                            contentDescription = "Ảnh đính kèm",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(130.dp)
                                .clip(RoundedCornerShape(16.dp))
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }

                    if (msg.cleanBody.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isMine && conversationId != null) {
                                IconButton(
                                    onClick = { onDelete(msg.id) },
                                    modifier = Modifier.size(26.dp)
                                ) {
                                    Icon(
                                        Icons.Default.DeleteOutline,
                                        contentDescription = "Gỡ",
                                        tint = palette.mutedText,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(18.dp))
                                    .background(
                                        if (isMine) palette.spec.accent else palette.cardContainer
                                    )
                                    .clickable(enabled = conversationId != null) {
                                        onReact(msg.id, "❤️", myHeart)
                                    }
                                    .padding(horizontal = 14.dp, vertical = 9.dp)
                            ) {
                                Text(
                                    text = msg.cleanBody,
                                    color = if (isMine) palette.spec.onAccent else Color.White,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    }

                    if (msg.reactions.isNotEmpty()) {
                        Text(
                            text = msg.reactions.joinToString(" ") { it.emoji },
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 2.dp, start = 6.dp, end = 6.dp)
                        )
                    }
                }
            }
        }

        // Composer Row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Nhắn tin cho $peerName...") },
                singleLine = true,
                shape = RoundedCornerShape(50),
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = {
                    if (draft.isNotBlank() && !isSending) {
                        onSend(draft)
                        draft = ""
                    }
                },
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(palette.spec.accent)
            ) {
                if (isSending) {
                    CircularProgressIndicator(
                        color = palette.spec.onAccent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Gửi",
                        tint = palette.spec.onAccent
                    )
                }
            }
        }
    }
}

@Composable
private fun FriendsManagementView(
    myUsername: String,
    socialState: SocialUiState,
    onSearchUsername: (String) -> Unit,
    onFriendAction: (String, String) -> Unit,
    onStartChat: (String) -> Unit
) {
    val context = LocalContext.current
    val palette = LocalLocketPalette.current
    var query by remember { mutableStateOf("") }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // 1. Share Personal Locket Link + @username Search
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(palette.cardContainer)
                    .border(1.dp, palette.cardBorder, RoundedCornerShape(20.dp))
                    .padding(14.dp)
            ) {
                if (myUsername.isNotBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Liên kết của bạn: locket.cam/$myUsername", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        IconButton(onClick = {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, "Kết bạn Locket với mình nhé: https://locket.cam/$myUsername")
                            }
                            context.startActivity(Intent.createChooser(intent, "Chia sẻ liên kết Locket"))
                        }) {
                            Icon(Icons.Default.Share, contentDescription = "Chia sẻ link", tint = palette.spec.accent)
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Tìm bạn qua @username...") },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { onSearchUsername(query) },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = palette.spec.accent,
                            contentColor = palette.spec.onAccent
                        )
                    ) {
                        if (socialState.isSearchingUser) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Search, contentDescription = "Tìm")
                        }
                    }
                }

                socialState.searchedUser?.let { found ->
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        AccountAvatarCircle(avatarUrl = found.profilePictureUrl, label = found.fullName, sizeDp = 40)
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(found.fullName, fontWeight = FontWeight.Bold)
                            if (found.username.isNotBlank()) {
                                Text("@${found.username}", fontSize = 12.sp, color = palette.mutedText)
                            }
                        }
                        Button(
                            onClick = { onFriendAction(found.uid, "send") },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = palette.spec.accent,
                                contentColor = palette.spec.onAccent
                            )
                        ) {
                            Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Kết bạn")
                        }
                    }
                }
            }
        }

        // 2. Incoming Friend Requests
        if (socialState.requests.incoming.isNotEmpty()) {
            item {
                Text(
                    text = "Lời mời kết bạn (${socialState.requests.incoming.size})",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.5.sp
                )
            }
            items(socialState.requests.incoming, key = { "in_${it.uid}" }) { reqUser ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(palette.cardContainer)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AccountAvatarCircle(avatarUrl = reqUser.profilePictureUrl, label = reqUser.fullName, sizeDp = 42)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(reqUser.fullName, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onFriendAction(reqUser.uid, "accept") }) {
                        Icon(Icons.Default.Check, contentDescription = "Đồng ý", tint = Color(0xFF34D399))
                    }
                    IconButton(onClick = { onFriendAction(reqUser.uid, "remove") }) {
                        Icon(Icons.Default.Close, contentDescription = "Từ chối", tint = Color(0xFFFF6B7A))
                    }
                }
            }
        }

        // 3. Friends List
        item {
            Text(
                text = "Danh sách bạn bè (${socialState.friends.size})",
                fontWeight = FontWeight.Bold,
                fontSize = 14.5.sp
            )
        }
        items(socialState.friends, key = { it.uid }) { friend ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(palette.cardContainer)
                    .border(1.dp, palette.cardBorder, RoundedCornerShape(16.dp))
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AccountAvatarCircle(avatarUrl = friend.profilePictureUrl, label = friend.fullName, sizeDp = 44)
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(friend.fullName, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    if (friend.username.isNotBlank()) {
                        Text("@${friend.username}", fontSize = 12.sp, color = palette.mutedText)
                    }
                }
                IconButton(onClick = { onStartChat(friend.uid) }) {
                    Icon(Icons.Default.ChatBubbleOutline, contentDescription = "Nhắn tin", tint = palette.spec.accent)
                }
                IconButton(onClick = { onFriendAction(friend.uid, "remove") }) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = "Xóa bạn", tint = palette.mutedText)
                }
            }
        }
    }
}

