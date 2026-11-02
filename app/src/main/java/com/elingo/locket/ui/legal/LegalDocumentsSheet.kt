package com.elingo.locket.ui.legal

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.elingo.locket.ui.theme.LocalLocketPalette

enum class LegalDocKind {
    TERMS,
    PRIVACY
}

private data class LegalSection(
    val heading: String,
    val items: List<String>
)

private const val LEGAL_UPDATED_DATE = "02/11/2026"

private val TERMS_SECTIONS = listOf(
    LegalSection(
        heading = "Về Locket Photobooth",
        items = listOf(
            "Locket Photobooth là công cụ không chính thức giúp bạn đăng ảnh, video và trò chuyện trên Locket.",
            "Công cụ không liên kết, không được Locket Labs bảo trợ hay xác nhận. “Locket” là thương hiệu của chủ sở hữu tương ứng."
        )
    ),
    LegalSection(
        heading = "Tài khoản",
        items = listOf(
            "Chỉ dùng tài khoản Locket của chính bạn, hoặc tài khoản bạn được chủ sở hữu cho phép sử dụng.",
            "Bạn chịu trách nhiệm về mọi hoạt động thực hiện trên tài khoản của mình qua công cụ này."
        )
    ),
    LegalSection(
        heading = "Nội dung",
        items = listOf(
            "Bạn chịu trách nhiệm với ảnh, video, chú thích và tin nhắn mình gửi.",
            "Không đăng nội dung vi phạm pháp luật, xâm phạm quyền riêng tư hoặc bản quyền của người khác, và tuân thủ điều khoản của Locket."
        )
    ),
    LegalSection(
        heading = "Sử dụng hợp lý",
        items = listOf(
            "Không dùng công cụ để gửi tin rác, quấy rối người khác, tấn công hay làm quá tải hệ thống."
        )
    ),
    LegalSection(
        heading = "Rủi ro và giới hạn trách nhiệm",
        items = listOf(
            "Locket có thể thay đổi, giới hạn hoặc khoá các tài khoản dùng công cụ bên thứ ba.",
            "Dịch vụ được cung cấp “nguyên trạng”, có thể gián đoạn, thay đổi hoặc ngừng mà không báo trước.",
            "Locket Photobooth không chịu trách nhiệm cho việc mất dữ liệu hoặc tài khoản bị hạn chế khi sử dụng công cụ."
        )
    ),
    LegalSection(
        heading = "Ủng hộ",
        items = listOf(
            "Ủng hộ là tự nguyện, không phải phí dịch vụ, không kèm quyền lợi cam kết cố định và không được hoàn lại."
        )
    ),
    LegalSection(
        heading = "Thay đổi điều khoản",
        items = listOf(
            "Điều khoản có thể được cập nhật. Tiếp tục sử dụng sau khi cập nhật nghĩa là bạn đồng ý với bản mới."
        )
    )
)

private val PRIVACY_SECTIONS = listOf(
    LegalSection(
        heading = "Thông tin đăng nhập",
        items = listOf(
            "Bạn đăng nhập bằng tài khoản Locket. Email và mật khẩu được mã hoá bất đối xứng RSA-OAEP-256 ngay trên thiết bị của bạn trước khi gửi tới máy chủ của Locket Photobooth, rồi máy chủ dùng chúng để đăng nhập vào Locket.",
            "Máy chủ không lưu email hay mật khẩu Locket của bạn: chúng chỉ đi qua máy chủ để đăng nhập vào Locket.",
            "Sau khi đăng nhập, máy chủ dùng mã phiên (token) do Locket cấp để thực hiện các thao tác bạn yêu cầu.",
            "Trên thiết bị Android của bạn, ứng dụng lưu phiên đăng nhập và thông tin đăng nhập đã mã hoá trong Android Keystore (EncryptedSharedPreferences) để giữ đăng nhập và chuyển nhanh giữa các tài khoản đã lưu. Đăng xuất hoặc xoá tài khoản khỏi danh sách sẽ xoá phần này trên thiết bị."
        )
    ),
    LegalSection(
        heading = "Ảnh, video và tin nhắn",
        items = listOf(
            "Ảnh, video, chú thích và tin nhắn bạn gửi được chuyển tới Locket. Ảnh và video có thể được xử lý tạm trên máy chủ (nén, đổi định dạng) trước khi gửi.",
            "Máy chủ lưu danh sách người nhận của những khoảnh khắc bạn gửi riêng, để hiển thị lại cho bạn.",
            "Khoảnh khắc bạn chọn \"Riêng tư\" không được gửi lên Locket: ảnh hoặc video đó, chú thích và thời gian được lưu trên máy chủ của Locket Photobooth để chỉ mình bạn xem lại. Xoá khoảnh khắc sẽ xoá tệp khỏi máy chủ.",
            "Nhãn Thời tiết và Vị trí chỉ hỏi vị trí của bạn khi bạn chọn chúng. Máy chủ gửi toạ độ đã làm tròn tới dịch vụ thời tiết và bản đồ và không lưu lại."
        )
    ),
    LegalSection(
        heading = "Ủng hộ",
        items = listOf(
            "Khi bạn ủng hộ, máy chủ lưu mã chuyển khoản riêng của bạn, tên hiển thị bạn chọn, số tiền và thời gian giao dịch (do SePay gửi tới).",
            "Danh sách người ủng hộ chỉ hiện tên hiển thị, không hiện số tiền."
        )
    ),
    LegalSection(
        heading = "Bên thứ ba",
        items = listOf(
            "Locket và Google Firebase: nơi lưu tài khoản, ảnh, video và tin nhắn của bạn, theo chính sách riêng của Locket.",
            "SePay: thông báo giao dịch chuyển khoản ủng hộ.",
            "MET Norway (thời tiết) và Photon của komoot (tên địa điểm, dữ liệu © OpenStreetMap): chỉ khi bạn chọn nhãn Thời tiết hoặc Vị trí."
        )
    ),
    LegalSection(
        heading = "Quyền của bạn",
        items = listOf(
            "Bạn có thể đăng xuất, xoá tài khoản khỏi danh sách trên thiết bị, xoá toàn bộ dữ liệu trên thiết bị và xoá khoảnh khắc đã đăng bất cứ lúc nào.",
            "Đổi mật khẩu Locket sẽ làm thông tin đăng nhập đã lưu không còn dùng được."
        )
    )
)

/**
 * Exact Native Compose port of `561.656a873d.chunk.js` (`Terms` & `Privacy`, updated `02/11/2026`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegalDocumentsSheet(
    initialKind: LegalDocKind,
    onDismiss: () -> Unit
) {
    val palette = LocalLocketPalette.current
    var activeKind by remember(initialKind) { mutableStateOf(initialKind) }

    val title = if (activeKind == LegalDocKind.TERMS) "Điều khoản sử dụng" else "Chính sách quyền riêng tư"
    val intro = if (activeKind == LegalDocKind.TERMS) {
        "Khi dùng Locket Photobooth, bạn đồng ý với các điều khoản dưới đây."
    } else {
        "Trang này cho biết Locket Photobooth xử lý những dữ liệu nào của bạn và bằng cách nào."
    }
    val sections = if (activeKind == LegalDocKind.TERMS) TERMS_SECTIONS else PRIVACY_SECTIONS

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = palette.spec.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = activeKind == LegalDocKind.TERMS,
                    onClick = { activeKind = LegalDocKind.TERMS },
                    label = { Text("Điều khoản sử dụng") }
                )
                FilterChip(
                    selected = activeKind == LegalDocKind.PRIVACY,
                    onClick = { activeKind = LegalDocKind.PRIVACY },
                    label = { Text("Quyền riêng tư") }
                )
            }

            Text(title, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                text = "Cập nhật lần cuối: $LEGAL_UPDATED_DATE",
                fontSize = 12.sp,
                color = palette.spec.accent,
                fontWeight = FontWeight.SemiBold
            )
            Text(text = intro, fontSize = 13.5.sp, color = palette.mutedText)

            sections.forEach { sec ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(palette.cardContainer)
                        .border(1.dp, palette.cardBorder, RoundedCornerShape(16.dp))
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(sec.heading, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    sec.items.forEach { bullet ->
                        Text(
                            text = "• $bullet",
                            fontSize = 13.sp,
                            color = palette.mutedText,
                            lineHeight = 19.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.spec.accent,
                    contentColor = palette.spec.onAccent
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text("Đã hiểu", fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(10.dp))
        }
    }
}

/**
 * Exact Native Compose port of `317.db45aa20.chunk.js` (`OnlineCards` modal sheet).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun OnlineCardsSheet(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val palette = LocalLocketPalette.current
    val kinds = listOf("Thiệp cưới", "Sinh nhật", "Kỷ yếu", "Thiệp Tết", "Tỏ tình", "Valentine", "Giáng sinh", "20/10")
    val points = listOf(
        "Làm nhanh ngay trên điện thoại hoặc máy tính",
        "Tự thêm thông tin sự kiện, ảnh và lời chúc",
        "Gửi cho mọi người bằng một đường link",
        "Khách xác nhận tham dự online"
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = palette.spec.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(palette.spec.accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Favorite,
                    contentDescription = null,
                    tint = palette.spec.accent,
                    modifier = Modifier.size(28.dp)
                )
            }

            Text("Thiệp Online", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            Text(
                text = "Tạo thiệp mời online miễn phí, gửi bằng một đường link",
                fontSize = 13.5.sp,
                color = palette.mutedText
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                kinds.forEach { k ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(palette.cardContainer)
                            .border(1.dp, palette.cardBorder, RoundedCornerShape(50))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(k, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(palette.cardContainer)
                    .border(1.dp, palette.cardBorder, RoundedCornerShape(18.dp))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                points.forEach { pt ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = palette.spec.accent,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(pt, fontSize = 13.sp)
                    }
                }
            }

            Button(
                onClick = {
                    onDismiss()
                    runCatching {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://thieponline.id.vn")))
                    }
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = palette.spec.accent,
                    contentColor = palette.spec.onAccent
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Text("Làm thiệp miễn phí", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp)
            }

            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Đóng")
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

