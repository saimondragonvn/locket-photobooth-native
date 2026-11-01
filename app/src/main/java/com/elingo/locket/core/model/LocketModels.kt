package com.elingo.locket.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
data class LocketSession(
    val localId: String,
    val idToken: String,
    val refreshToken: String = "",
    val email: String = "",
    val displayName: String = "",
    val username: String = "",
    val photoUrl: String = "",
    val expiresInSeconds: Long = 3600L,
    val expiresAtMillis: Long = System.currentTimeMillis() + 3600_000L
) {
    fun isExpiringWithin(marginMs: Long): Boolean {
        return System.currentTimeMillis() >= (expiresAtMillis - marginMs)
    }
}

@Serializable
data class SavedAccount(
    val uid: String,
    val email: String = "",
    val name: String = "",
    val username: String = "",
    val avatar: String = "",
    val session: LocketSession,
    val encryptedEmail: String? = null,
    val encryptedPassword: String? = null
) {
    val label: String
        get() = name.ifBlank { username.ifBlank { email.ifBlank { "Tài khoản Locket" } } }
}

@Serializable
data class FriendUser(
    val uid: String = "",
    @SerialName("first_name") val firstName: String = "",
    @SerialName("last_name") val lastName: String = "",
    val username: String = "",
    @SerialName("profile_picture_url") val profilePictureUrl: String = ""
) {
    val fullName: String
        get() = "$firstName $lastName".trim()
            .ifBlank { username.ifBlank { uid.take(8).ifBlank { "Bạn bè" } } }

    val initials: String
        get() {
            val f = firstName.trim().firstOrNull()?.uppercase() ?: ""
            val l = lastName.trim().firstOrNull()?.uppercase() ?: ""
            return (f + l).ifBlank { username.trim().firstOrNull()?.uppercase() ?: "?" }
        }
}

@Serializable
data class CaptionOverlayConfig(
    val noteType: String = "standard", // standard, caption, gradient, review, time, weather, location, streak
    val icon: String? = null,
    val textColor: String? = null,
    val colors: List<String> = emptyList(),
    val payload: JsonObject? = null,
    val previewText: String? = null
)

@Serializable
data class OverlayDisplay(
    val text: String = "",
    val icon: String? = null,
    val textColor: String = "#FFFFFF",
    val backgroundColors: List<String> = emptyList()
)

@Serializable
data class Moment(
    val id: String,
    val user: String = "",
    val date: String = "",
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    @SerialName("video_url") val videoUrl: String? = null,
    val caption: String = "",
    val overlay: OverlayDisplay = OverlayDisplay(),
    val isPrivate: Boolean = false,
    val cursor: String? = null,
    val localStatus: String? = null,
    val localProgress: Int = 0,
    val localError: String? = null,
    val recipients: List<String> = emptyList()
) {
    val isVideo: Boolean
        get() = !videoUrl.isNullOrBlank()

    val dateMillis: Long
        get() = runCatching { java.time.Instant.parse(date).toEpochMilli() }
            .getOrElse { date.toLongOrNull() ?: 0L }
}

@Serializable
data class StreakAnchor(
    val count: Int = 0,
    @SerialName("last_updated_yyyymmdd") val lastUpdatedYyyyMmDd: Int = 0
)

@Serializable
data class StreakState(
    val count: Int = 0,
    val nextCaptionDay: Int = 1,
    val postedToday: Boolean = false,
    val lastYyyyMmDd: Int? = null,
    val totalLockets: Int? = null
)

@Serializable
data class FeedPage(
    val moments: List<Moment> = emptyList(),
    val users: Map<String, FriendUser> = emptyMap(),
    val me: FriendUser? = null,
    val nextCursor: String? = null
)

@Serializable
data class FriendRequestsData(
    val incoming: List<FriendUser> = emptyList(),
    val outgoing: List<FriendUser> = emptyList()
)

@Serializable
data class MessageReaction(
    val sender: String = "",
    val emoji: String = "",
    @SerialName("created_at") val createdAt: String = ""
)

@Serializable
data class ChatMessage(
    val id: String,
    val sender: String = "",
    val body: String = "",
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    @SerialName("reply_moment") val replyMoment: String? = null,
    @SerialName("client_token") val clientToken: String? = null,
    val reactions: List<MessageReaction> = emptyList(),
    val isPending: Boolean = false,
    val isFailed: Boolean = false
) {
    val cleanBody: String
        get() = if (body == "\u200b") "" else body
}

@Serializable
data class Conversation(
    val id: String,
    val peer: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    @SerialName("unread_count") val unreadCount: Int = 0,
    @SerialName("is_read") val isRead: Boolean = true,
    @SerialName("other_last_read_at") val otherLastReadAt: String? = null,
    val latest: ChatMessage? = null
) {
    val hasUnread: Boolean
        get() = !isRead || unreadCount > 0
}

@Serializable
data class MomentActivityPerson(
    val user: String = "",
    val reactions: List<MessageReaction> = emptyList(),
    @SerialName("last_at") val lastAt: String = ""
)

@Serializable
data class MomentActivity(
    val views: Int = 0,
    val reactions: Int = 0,
    val people: List<MomentActivityPerson> = emptyList(),
    val users: Map<String, FriendUser> = emptyMap()
)

@Serializable
data class CaptionPreset(
    val id: String,
    val label: String,
    val icon: String? = null,
    val textColor: String = "#FFFFFF",
    val colors: List<String> = emptyList(),
    val payload: JsonElement? = null
)

@Serializable
data class WeatherInfo(
    val tempC: Double? = null,
    val description: String = "",
    val icon: String = "⛅",
    val label: String = ""
)

@Serializable
data class NearbyPlace(
    val name: String,
    val address: String = ""
)

@Serializable
data class PerkTier(
    val id: String,
    val minTotal: Long,
    val name: String
)

@Serializable
data class SupporterStatus(
    val totalDonatedVnd: Long = 0L,
    val tiers: List<PerkTier> = listOf(PerkTier("booth-1", 10_000L, "Photobooth đợt 1"))
) {
    fun canUseTier(tierId: String?): Boolean {
        if (tierId.isNullOrBlank()) return true
        val minRequired = tiers.firstOrNull { it.id == tierId }?.minTotal ?: Long.MAX_VALUE
        return totalDonatedVnd >= minRequired
    }
}

@Serializable
data class DonateMeResponse(
    val qrUrl: String? = null,
    val transferContent: String = "",
    val bankName: String = "",
    val accountNumber: String = "",
    val accountName: String = "",
    val donorTotal: Long = 0L,
    val donorCount: Int = 0,
    val donorDisplayName: String = "",
    val donorCode: String = ""
)

@Serializable
data class DonorLeaderboardItem(
    val displayName: String,
    val isMe: Boolean = false
)

