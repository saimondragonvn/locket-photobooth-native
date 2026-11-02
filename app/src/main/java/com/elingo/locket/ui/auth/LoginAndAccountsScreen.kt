package com.elingo.locket.ui.auth

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.elingo.locket.R
import com.elingo.locket.core.model.SavedAccount
import com.elingo.locket.ui.legal.LegalDocKind
import com.elingo.locket.ui.legal.LegalDocumentsSheet
import com.elingo.locket.ui.theme.LocalLocketPalette

@Composable
fun LoginScreen(
    isLoggingIn: Boolean,
    savedAccounts: List<SavedAccount>,
    onLogin: (email: String, password: String) -> Unit,
    onQuickSwitchAccount: (uid: String) -> Unit,
    onRemoveSavedAccount: (uid: String) -> Unit
) {
    val palette = LocalLocketPalette.current
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var openLegalKind by remember { mutableStateOf<LegalDocKind?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.spec.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(20.dp))

        // Official App Brand Emblem (reconstructed from locket-icon.e692ef7377b8d16a87f7.png)
        Box(
            modifier = Modifier
                .size(88.dp)
                .clip(RoundedCornerShape(26.dp))
                .background(
                    Brush.linearGradient(
                        listOf(palette.spec.accent, palette.spec.accentSecondary)
                    )
                )
                .border(1.5.dp, palette.cardBorder, RoundedCornerShape(26.dp)),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.locket_brand_icon),
                contentDescription = "Locket Photobooth",
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        Text(
            text = "Locket Photobooth",
            fontSize = 28.sp,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Bản Native Android 60FPS • Tăng tốc phần cứng CameraX & Media3 GPU",
            fontSize = 13.sp,
            color = palette.mutedText
        )

        Spacer(modifier = Modifier.height(28.dp))

        // Saved Accounts Quick-Resume Card
        if (savedAccounts.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(palette.cardContainer)
                    .border(1.dp, palette.cardBorder, RoundedCornerShape(22.dp))
                    .padding(16.dp)
            ) {
                Text(
                    text = "Chạm để vào nhanh tài khoản đã lưu",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.mutedText
                )
                Spacer(modifier = Modifier.height(10.dp))
                savedAccounts.forEach { acc ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable { onQuickSwitchAccount(acc.uid) }
                            .padding(vertical = 8.dp, horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AccountAvatarCircle(avatarUrl = acc.avatar, label = acc.label, sizeDp = 40)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = acc.label,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (acc.email.isNotBlank()) {
                                Text(
                                    text = acc.email,
                                    fontSize = 12.sp,
                                    color = palette.mutedText,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        IconButton(onClick = { onRemoveSavedAccount(acc.uid) }) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "Xóa tài khoản đã lưu",
                                tint = palette.mutedText
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        // Credentials Form Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(palette.cardContainer)
                .border(1.dp, palette.cardBorder, RoundedCornerShape(24.dp))
                .padding(20.dp)
        ) {
            Text(
                text = "Đăng nhập tài khoản Locket",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email Locket") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next
                ),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = palette.spec.accent,
                    unfocusedBorderColor = palette.cardBorder
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Mật khẩu") },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (!isLoggingIn) onLogin(email, password)
                    }
                ),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(
                            imageVector = if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "Ẩn/Hiện mật khẩu"
                        )
                    }
                },
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = palette.spec.accent,
                    unfocusedBorderColor = palette.cardBorder
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(18.dp))

            Button(
                onClick = { onLogin(email, password) },
                enabled = !isLoggingIn,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.spec.accent,
                    contentColor = palette.spec.onAccent
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                if (isLoggingIn) {
                    CircularProgressIndicator(
                        color = palette.spec.onAccent,
                        strokeWidth = 2.5.dp,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Đang xác thực...", fontWeight = FontWeight.Bold)
                } else {
                    Text("Đăng nhập ngay", fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = palette.mutedText,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Mã hóa bất đối xứng RSA-OAEP-256 & Android Keystore",
                    fontSize = 11.5.sp,
                    color = palette.mutedText
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Điều khoản sử dụng",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.spec.accent,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable { openLegalKind = LegalDocKind.TERMS }
                )
                Text(
                    text = "  •  ",
                    fontSize = 12.sp,
                    color = palette.mutedText
                )
                Text(
                    text = "Chính sách quyền riêng tư",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = palette.spec.accent,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable { openLegalKind = LegalDocKind.PRIVACY }
                )
            }
        }
    }

    openLegalKind?.let { kind ->
        LegalDocumentsSheet(
            initialKind = kind,
            onDismiss = { openLegalKind = null }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSwitcherSheet(
    activeUid: String?,
    savedAccounts: List<SavedAccount>,
    onSwitchAccount: (uid: String) -> Unit,
    onAddAnotherAccount: () -> Unit,
    onRemoveAccount: (uid: String) -> Unit,
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
                .padding(horizontal = 20.dp, vertical = 10.dp)
        ) {
            Text(
                text = "Chuyển đổi tài khoản Locket",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))

            savedAccounts.forEach { acc ->
                val isCurrent = acc.uid == activeUid
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isCurrent) palette.spec.accent.copy(alpha = 0.14f) else Color.Transparent)
                        .clickable {
                            if (!isCurrent) onSwitchAccount(acc.uid)
                            onDismiss()
                        }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AccountAvatarCircle(avatarUrl = acc.avatar, label = acc.label, sizeDp = 44)
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = acc.label,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Text(
                            text = acc.username.takeIf { it.isNotBlank() }?.let { "@$it" } ?: acc.email,
                            fontSize = 12.sp,
                            color = palette.mutedText
                        )
                    }
                    if (isCurrent) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Đang dùng",
                            tint = palette.spec.accent
                        )
                    } else {
                        IconButton(onClick = { onRemoveAccount(acc.uid) }) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "Xóa",
                                tint = palette.mutedText
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
            }

            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                onClick = {
                    onDismiss()
                    onAddAnotherAccount()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = palette.spec.accent)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Đăng nhập thêm tài khoản khác", color = palette.spec.accent, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
fun AccountAvatarCircle(
    avatarUrl: String?,
    label: String,
    sizeDp: Int,
    modifier: Modifier = Modifier
) {
    val palette = LocalLocketPalette.current
    Box(
        modifier = modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .background(palette.spec.accent.copy(alpha = 0.22f))
            .border(1.dp, palette.cardBorder, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (!avatarUrl.isNullOrBlank()) {
            AsyncImage(
                model = avatarUrl,
                contentDescription = label,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            val initial = label.trim().firstOrNull()?.uppercase()
            if (initial != null) {
                Text(
                    text = initial,
                    fontWeight = FontWeight.Bold,
                    color = palette.spec.accent,
                    fontSize = (sizeDp * 0.42f).sp
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = palette.spec.accent
                )
            }
        }
    }
}

