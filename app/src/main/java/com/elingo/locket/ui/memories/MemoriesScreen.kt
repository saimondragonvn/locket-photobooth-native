package com.elingo.locket.ui.memories

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.elingo.locket.core.model.Moment
import com.elingo.locket.core.model.StreakState
import com.elingo.locket.ui.theme.LocalLocketPalette
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoriesScreen(
    moments: List<Moment>,
    streakState: StreakState
) {
    val palette = LocalLocketPalette.current
    var currentMonth by remember { mutableStateOf(YearMonth.now()) }
    var selectedDayMoments by remember { mutableStateOf<Pair<LocalDate, List<Moment>>?>(null) }

    val zone = ZoneId.systemDefault()
    val momentsByDay = remember(moments) {
        moments
            .filter { it.dateMillis > 0L }
            .groupBy { Instant.ofEpochMilli(it.dateMillis).atZone(zone).toLocalDate() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.spec.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        // 1. Streak & Total Lockets Hero Summary
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SummaryStatCard(
                icon = "🔥",
                value = "${streakState.count} ngày",
                label = if (streakState.postedToday) "Đã giữ chuỗi hôm nay" else "Đăng ảnh để nối chuỗi!",
                modifier = Modifier.weight(1f)
            )
            SummaryStatCard(
                icon = "📸",
                value = "${streakState.totalLockets ?: moments.size}",
                label = "Tổng khoảnh khắc Locket",
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(18.dp))

        // 2. Monthly Calendar Card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(palette.cardContainer)
                .border(1.dp, palette.cardBorder, RoundedCornerShape(24.dp))
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = { currentMonth = currentMonth.minusMonths(1) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Tháng trước")
                }
                Text(
                    text = "Tháng ${currentMonth.monthValue} / ${currentMonth.year}",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 17.sp
                )
                IconButton(onClick = { currentMonth = currentMonth.plusMonths(1) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Tháng sau")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Weekday Headers (Mon - Sun)
            val weekDays = listOf("T2", "T3", "T4", "T5", "T6", "T7", "CN")
            Row(modifier = Modifier.fillMaxWidth()) {
                weekDays.forEach { wd ->
                    Text(
                        text = wd,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = palette.mutedText,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            val firstOfMonth = currentMonth.atDay(1)
            val leadingEmpty = firstOfMonth.dayOfWeek.value - 1 // Monday = 0
            val daysInMonth = currentMonth.lengthOfMonth()
            val totalCells = ((leadingEmpty + daysInMonth + 6) / 7) * 7

            for (week in 0 until (totalCells / 7)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (dow in 0..6) {
                        val cellIndex = week * 7 + dow
                        val dayNumber = cellIndex - leadingEmpty + 1
                        if (dayNumber !in 1..daysInMonth) {
                            Spacer(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                            )
                        } else {
                            val date = currentMonth.atDay(dayNumber)
                            val dayList = momentsByDay[date].orEmpty()
                            val cover = dayList.firstOrNull()
                            val isToday = date == LocalDate.now(zone)

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(11.dp))
                                    .background(
                                        if (cover != null) palette.spec.accent.copy(alpha = 0.2f)
                                        else Color.White.copy(alpha = 0.04f)
                                    )
                                    .border(
                                        width = if (cover != null || isToday) 1.5.dp else 0.5.dp,
                                        color = when {
                                            cover != null -> palette.spec.accent
                                            isToday -> Color.White.copy(alpha = 0.5f)
                                            else -> palette.cardBorder
                                        },
                                        shape = RoundedCornerShape(11.dp)
                                    )
                                    .clickable(enabled = dayList.isNotEmpty()) {
                                        selectedDayMoments = date to dayList
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                if (cover != null) {
                                    AsyncImage(
                                        model = cover.thumbnailUrl ?: cover.videoUrl,
                                        contentDescription = "Ngày $dayNumber",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color.Black.copy(alpha = 0.28f))
                                    )
                                }
                                Text(
                                    text = dayNumber.toString(),
                                    fontSize = 12.5.sp,
                                    fontWeight = if (cover != null || isToday) FontWeight.ExtraBold else FontWeight.Normal,
                                    color = if (cover != null) Color.White else palette.mutedText
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    selectedDayMoments?.let { (date, list) ->
        ModalBottomSheet(
            onDismissRequest = { selectedDayMoments = null },
            containerColor = palette.spec.surface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp)
            ) {
                Text(
                    text = "Kỷ niệm ngày ${date.dayOfMonth}/${date.monthValue}/${date.year} (${list.size})",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(list, key = { it.id }) { m ->
                        Column(modifier = Modifier.width(220.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(220.dp)
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(Color.Black)
                            ) {
                                AsyncImage(
                                    model = m.thumbnailUrl ?: m.videoUrl,
                                    contentDescription = m.caption,
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            if (m.caption.isNotBlank()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(m.caption, fontSize = 13.sp, maxLines = 2)
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun SummaryStatCard(
    icon: String,
    value: String,
    label: String,
    modifier: Modifier = Modifier
) {
    val palette = LocalLocketPalette.current
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(22.dp))
            .background(palette.cardContainer)
            .border(1.dp, palette.cardBorder, RoundedCornerShape(22.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(icon, fontSize = 22.sp)
            Spacer(modifier = Modifier.width(8.dp))
            Text(value, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, color = palette.spec.accent)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(label, fontSize = 12.sp, color = palette.mutedText)
    }
}

