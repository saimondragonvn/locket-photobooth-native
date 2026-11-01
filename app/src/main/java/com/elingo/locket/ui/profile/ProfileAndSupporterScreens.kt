package com.elingo.locket.ui.profile

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.elingo.locket.BuildConfig
import com.elingo.locket.core.model.LocketSession
import com.elingo.locket.core.model.SupporterStatus
import com.elingo.locket.ui.SupporterSheetState
import com.elingo.locket.ui.auth.AccountAvatarCircle
import com.elingo.locket.ui.theme.LocalLocketPalette
import com.elingo.locket.ui.theme.LocketThemes
import java.text.NumberFormat
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ProfileAndSettingsScreen(
    session: LocketSession,
    supporterStatus: SupporterStatus,
    supporterSheetState: SupporterSheetState,
    activeThemeId: String,
    isGlassStyle: Boolean,
    onChangeAvatar: (Uri) -> Unit,
    onUpdateName: (firstName: String, lastName: String) -> Unit,
    onSelectTheme: (String) -> Unit,
    onToggleGlass: (Boolean) -> Unit,
    onOpenAccountSwitcher: () -> Unit,
    onLoadSupporterDetails: () -> Unit,
    onSaveDonorDisplayName: (String) -> Unit,
    onLogout: () -> Unit
) {
    val palette = LocalLocketPalette.current
    var showEditName by remember { mutableStateOf(false) }
    var showSupporterSheet by remember { mutableStateOf(false) }

    val nameParts = remember(session.displayName) {
        val split = session.displayName.trim().split(" ", limit = 2)
        (split.getOrNull(0).orEmpty()) to (split.getOrNull(1).orEmpty())
    }
    var firstName by remember(session.displayName) { mutableStateOf(nameParts.first) }
    var lastName by remember(session.displayName) { mutableStateOf(nameParts.second) }

    val avatarPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) onChangeAvatar(uri)
    }

    val vndFormat = remember { NumberFormat.getInstance(Locale("vi", "VN")) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.spec.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Account Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(palette.cardContainer)
                .border(1.dp, palette.cardBorder, RoundedCornerShape(24.dp))
                .padding(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier.clickable {
                    avatarPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            ) {
                AccountAvatarCircle(
                    avatarUrl = session.photoUrl,
                    label = session.displayName.ifBlank { session.email },
                    sizeDp = 82
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(palette.spec.accent),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "Đổi ảnh đại diện",
                        tint = palette.spec.onAccent,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = session.displayName.ifBlank { "Người dùng Locket" },
                fontWeight = FontWeight.ExtraBold,
                fontSize = 20.sp
            )
            if (session.username.isNotBlank()) {
                Text("@${session.username}", color = palette.spec.accent, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            }
            if (session.email.isNotBlank()) {
                Text(session.email, color = palette.mutedText, fontSize = 12.5.sp)
            }

            Spacer(modifier = Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { showEditName = !showEditName }) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Đổi tên")
                }
                OutlinedButton(onClick = onOpenAccountSwitcher) {
                    Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Đổi tài khoản")
                }
            }

            if (showEditName) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = firstName,
                        onValueChange = { firstName = it },
                        label = { Text("Họ / Tên đệm") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = lastName,
                        onValueChange = { lastName = it },
                        label = { Text("Tên") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        onUpdateName(firstName, lastName)
                        showEditName = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = palette.spec.accent,
                        contentColor = palette.spec.onAccent
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Lưu tên hiển thị", fontWeight = FontWeight.Bold)
                }
            }
        }

        // 2. Legitimate Supporter Status Card (Synced from POST /locket/donate/status)
        val hasBooth1 = supporterStatus.canUseTier("booth-1")
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(palette.cardContainer)
                .border(
                    1.dp,
                    if (hasBooth1) palette.spec.accent else palette.cardBorder,
                    RoundedCornerShape(22.dp)
                )
                .clickable {
                    showSupporterSheet = true
                    onLoadSupporterDetails()
                }
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (hasBooth1) Icons.Default.Verified else Icons.Default.Favorite,
                    contentDescription = null,
                    tint = palette.spec.accent
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (hasBooth1) "Supporter đã xác thực (Mở khóa Photobooth đợt 1)"
                    else "Trạng thái Supporter & Gói Photobooth",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Đã ghi nhận từ máy chủ: ${vndFormat.format(supporterStatus.totalDonatedVnd)}đ • Chạm để xem QR & đồng bộ",
                fontSize = 12.5.sp,
                color = palette.mutedText
            )
        }

        // 3. 14 Locket Themes + Glass/Solid Appearance
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(palette.cardContainer)
                .border(1.dp, palette.cardBorder, RoundedCornerShape(22.dp))
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Hiệu ứng thẻ Kính mờ (Glass)", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text("Tắt để chuyển sang nền đặc (Solid)", fontSize = 12.sp, color = palette.mutedText)
                }
                Switch(checked = isGlassStyle, onCheckedChange = onToggleGlass)
            }

            Spacer(modifier = Modifier.height(14.dp))
            Text("Chủ đề màu sắc (14 bảng màu Locket)", fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Spacer(modifier = Modifier.height(10.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LocketThemes.ALL.forEach { theme ->
                    val selected = theme.id == activeThemeId
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(theme.surface)
                            .border(
                                width = if (selected) 2.dp else 1.dp,
                                color = if (selected) theme.accent else palette.cardBorder,
                                shape = RoundedCornerShape(50)
                            )
                            .clickable { onSelectTheme(theme.id) }
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(theme.accent)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(theme.name, fontSize = 12.5.sp, color = Color.White)
                        if (selected) {
                            Spacer(modifier = Modifier.width(4.dp))
                            Icon(
                                Icons.Default.Check,
                                contentDescription = null,
                                tint = theme.accent,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                }
            }
        }

        // 4. Logout Button
        OutlinedButton(
            onClick = onLogout,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
        ) {
            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, tint = Color(0xFFFF6B7A))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Đăng xuất khỏi tài khoản này", color = Color(0xFFFF6B7A), fontWeight = FontWeight.Bold)
        }

        // 5. Version & Commit Traceability Badge (for multi-version testing & rollback)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Locket Photobooth Native • v${BuildConfig.VERSION_NAME}",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = palette.mutedText
            )
            Text(
                text = "Build #${BuildConfig.VERSION_CODE} • Commit: ${BuildConfig.GIT_COMMIT_SHA}",
                fontSize = 11.sp,
                color = palette.mutedText.copy(alpha = 0.75f)
            )
        }
    }

    if (showSupporterSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSupporterSheet = false },
            containerColor = palette.spec.surface
        ) {
            var donorName by remember(supporterSheetState.donateMe?.donorDisplayName) {
                mutableStateOf(supporterSheetState.donateMe?.donorDisplayName.orEmpty())
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Thông tin Supporter chính thức", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(modifier = Modifier.height(8.dp))

                if (supporterSheetState.isLoading) {
                    CircularProgressIndicator(color = palette.spec.accent)
                } else {
                    val info = supporterSheetState.donateMe
                    if (info != null) {
                        Text(
                            text = "Tổng ủng hộ đã xác thực: ${vndFormat.format(info.donorTotal)}đ",
                            color = palette.spec.accent,
                            fontWeight = FontWeight.Bold
                        )
                        if (!info.qrUrl.isNullOrBlank()) {
                            Spacer(modifier = Modifier.height(10.dp))
                            AsyncImage(
                                model = info.qrUrl,
                                contentDescription = "VietQR Supporter",
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .size(210.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color.White)
                                    .padding(8.dp)
                            )
                        }
                        if (info.transferContent.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("Nội dung CK: ${info.transferContent}", fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp)
                        }
                        if (info.bankName.isNotBlank() || info.accountNumber.isNotBlank()) {
                            Text(
                                text = "${info.bankName} • ${info.accountNumber} (${info.accountName})",
                                fontSize = 12.5.sp,
                                color = palette.mutedText
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = donorName,
                                onValueChange = { donorName = it },
                                label = { Text("Tên hiển thị bảng vinh danh") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = { onSaveDonorDisplayName(donorName) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = palette.spec.accent,
                                    contentColor = palette.spec.onAccent
                                )
                            ) {
                                Text("Lưu")
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

