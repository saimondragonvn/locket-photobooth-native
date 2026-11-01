package com.elingo.locket.media.uploader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.elingo.locket.LocketApplication
import com.elingo.locket.core.auth.SessionManager
import com.elingo.locket.core.database.OutboxDao
import com.elingo.locket.core.database.OutboxEntity
import com.elingo.locket.core.model.CaptionOverlayConfig
import com.elingo.locket.core.model.Moment
import com.elingo.locket.core.model.StreakAnchor
import com.elingo.locket.media.processor.Media3VideoProcessor
import com.elingo.locket.media.processor.PhotoProcessor
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

data class CompletedUploadEvent(
    val outboxId: String,
    val moment: Moment?,
    val streak: StreakAnchor?,
    val isPrivate: Boolean
)

/**
 * Coordinates instant non-blocking queueing into Room Outbox + WorkManager `dataSync` background execution.
 */
class OutboxManager(
    private val appContext: Context,
    private val outboxDao: OutboxDao,
    private val sessionManager: SessionManager
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _completedEvents = MutableSharedFlow<CompletedUploadEvent>(extraBufferCapacity = 16)
    val completedEvents: SharedFlow<CompletedUploadEvent> = _completedEvents.asSharedFlow()

    internal fun emitCompleted(event: CompletedUploadEvent) {
        _completedEvents.tryEmit(event)
    }

    suspend fun enqueueMoment(
        sourceFile: File,
        isVideo: Boolean,
        isPrivate: Boolean,
        filterId: String,
        caption: String,
        overlay: CaptionOverlayConfig,
        recipients: List<String>,
        perks: List<String>,
        alreadyPrepared: Boolean = false
    ): OutboxEntity {
        val uid = sessionManager.currentSession()?.localId
            ?: throw IllegalStateException("Chưa đăng nhập tài khoản Locket")

        // Copy capture/picked file into persistent app storage so cache cleanup never deletes queued uploads
        val outboxDir = File(appContext.filesDir, "outbox_raw").apply { mkdirs() }
        val ext = sourceFile.extension.ifBlank { if (isVideo) "mp4" else "jpg" }
        val id = "ob_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
        val persistentFile = if (sourceFile.parentFile?.absolutePath == outboxDir.absolutePath ||
            sourceFile.parentFile?.name == "outbox_media"
        ) {
            sourceFile
        } else {
            File(outboxDir, "$id.$ext").also { dest ->
                sourceFile.copyTo(dest, overwrite = true)
            }
        }

        val thumbFile = if (isVideo) {
            Media3VideoProcessor.extractVideoThumbnailFile(appContext, persistentFile)
        } else {
            persistentFile
        }

        val entity = OutboxEntity(
            id = id,
            uid = uid,
            createdAt = System.currentTimeMillis(),
            mediaPath = persistentFile.absolutePath,
            thumbPath = thumbFile?.absolutePath,
            isVideo = isVideo,
            isPrivate = isPrivate,
            filterId = if (alreadyPrepared) "none" else filterId,
            caption = caption,
            overlayJson = json.encodeToString(overlay),
            recipientsJson = json.encodeToString(recipients),
            perksJson = json.encodeToString(perks),
            prepared = alreadyPrepared,
            status = OutboxEntity.STATUS_QUEUED,
            progressPercent = 2,
            statusMessage = "Đang chuẩn bị xử lý ngầm..."
        )
        outboxDao.upsert(entity)
        triggerWorker()
        return entity
    }

    suspend fun retryFailedItem(id: String) {
        val item = outboxDao.getById(id) ?: return
        outboxDao.updateProgress(
            id = item.id,
            status = OutboxEntity.STATUS_QUEUED,
            percent = 5,
            message = "Đang thử gửi lại...",
            error = null
        )
        triggerWorker()
    }

    suspend fun cancelAndDeleteItem(id: String) {
        val item = outboxDao.getById(id)
        outboxDao.deleteById(id)
        item?.let { cleanupOutboxFiles(it) }
    }

    fun triggerWorker() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<OutboxUploadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(appContext).enqueueUniqueWork(
            UNIQUE_WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request
        )
    }

    internal fun cleanupOutboxFiles(entity: OutboxEntity) {
        runCatching {
            val media = File(entity.mediaPath)
            if (media.exists() && media.absolutePath.contains(appContext.filesDir.absolutePath)) {
                media.delete()
            }
            entity.thumbPath?.let { path ->
                val thumb = File(path)
                if (thumb.exists() && thumb.absolutePath.contains(appContext.filesDir.absolutePath)) {
                    thumb.delete()
                }
            }
        }
    }

    companion object {
        const val UNIQUE_WORK_NAME = "locket_outbox_sync_worker"
    }
}

/**
 * Background CoroutineWorker executing GPU Media3 Video Compression / Photo Compression + 3-Tier Upload
 * completely off the Main UI Thread, continuing reliably even if the user minimizes the app.
 */
class OutboxUploadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override suspend fun doWork(): Result {
        val app = applicationContext as? LocketApplication ?: return Result.failure()
        val container = app.container
        val outboxDao = container.database.outboxDao()
        val uploader = container.mediaUploader
        val outboxManager = container.outboxManager
        val sessionManager = container.sessionManager

        ensureNotificationChannel()

        val pendingItems = outboxDao.getPendingItems()
        if (pendingItems.isEmpty()) {
            return Result.success()
        }

        runCatching {
            setForeground(createForegroundInfo("Đang đồng bộ ${pendingItems.size} khoảnh khắc Locket...", 5))
        }

        for (rawItem in pendingItems) {
            var item = outboxDao.getById(rawItem.id) ?: continue
            val activeSession = sessionManager.currentSession()
            if (activeSession == null || activeSession.localId != item.uid) {
                // Skip items belonging to an inactive account until switched back
                continue
            }

            try {
                var mediaFile = File(item.mediaPath)
                if (!mediaFile.exists() && item.jobId.isNullOrBlank()) {
                    outboxDao.updateProgress(
                        id = item.id,
                        status = OutboxEntity.STATUS_FAILED,
                        percent = 0,
                        message = "Tệp gốc không còn tồn tại",
                        error = "Tệp gốc đã bị xóa trước khi tải lên"
                    )
                    continue
                }

                // Step A: Hardware / Off-Thread Media Preparation (1:1 Crop + Filter + Compression)
                if (!item.prepared && item.jobId.isNullOrBlank()) {
                    outboxDao.updateProgress(
                        id = item.id,
                        status = OutboxEntity.STATUS_COMPRESSING,
                        percent = 6,
                        message = if (item.isVideo) "Đang nén video bằng GPU (Media3)..." else "Đang tối ưu ảnh 1:1..."
                    )

                    val processedFile = if (item.isVideo) {
                        Media3VideoProcessor.processVideoForLocket(
                            context = applicationContext,
                            inputFile = mediaFile,
                            filterId = item.filterId,
                            forPrivate = item.isPrivate,
                            onProgress = { ratio ->
                                val pct = (6 + (ratio * 24f).roundToInt()).coerceIn(6, 30)
                                updateNotificationProgress("Đang xử lý video bằng GPU...", pct)
                            }
                        )
                    } else {
                        PhotoProcessor.processImageFile(
                            context = applicationContext,
                            sourceFile = mediaFile,
                            filterId = item.filterId,
                            forPrivate = item.isPrivate
                        )
                    }

                    if (processedFile.absolutePath != mediaFile.absolutePath &&
                        mediaFile.absolutePath.contains("outbox_raw")
                    ) {
                        runCatching { mediaFile.delete() }
                    }
                    mediaFile = processedFile
                    outboxDao.markPrepared(item.id, mediaFile.absolutePath)
                    item = outboxDao.getById(item.id) ?: item
                }

                // Step B: 3-Tier Network Upload & Server Job Polling
                val overlay = runCatching {
                    json.decodeFromString<CaptionOverlayConfig>(item.overlayJson)
                }.getOrDefault(CaptionOverlayConfig())
                val recipients = runCatching {
                    json.decodeFromString<List<String>>(item.recipientsJson)
                }.getOrDefault(emptyList())
                val perks = runCatching {
                    json.decodeFromString<List<String>>(item.perksJson)
                }.getOrDefault(emptyList())

                val todayYyyyMmDd = LocalDate.now()
                    .format(DateTimeFormatter.BASIC_ISO_DATE)
                    .toIntOrNull()

                val publishResult = uploader.uploadPreparedMedia(
                    clientId = item.id,
                    file = mediaFile,
                    isVideo = item.isVideo,
                    isPrivate = item.isPrivate,
                    filterId = item.filterId,
                    caption = item.caption,
                    overlay = overlay,
                    recipients = recipients,
                    streakDayYyyyMmDd = if (item.isPrivate) null else todayYyyyMmDd,
                    perks = perks,
                    existingUploadId = item.uploadId,
                    existingJobId = item.jobId,
                    onUploadIdAssigned = { upId -> outboxDao.updateUploadId(item.id, upId) },
                    onJobIdAssigned = { jId -> outboxDao.updateJobId(item.id, jId) },
                    onProgress = { pct, msg ->
                        val stage = if (pct >= 85) OutboxEntity.STATUS_PUBLISHING else OutboxEntity.STATUS_UPLOADING
                        outboxDao.updateProgress(
                            id = item.id,
                            status = stage,
                            percent = pct,
                            message = msg,
                            error = null
                        )
                        updateNotificationProgress(msg, pct)
                    }
                )

                // Step C: Update Streak Cache, Clean Up & Notify UI Feed
                if (!item.isPrivate && publishResult.streak != null) {
                    sessionManager.saveStreakAnchor(item.uid, publishResult.streak, null)
                }
                outboxDao.deleteById(item.id)
                outboxManager.cleanupOutboxFiles(item)
                outboxManager.emitCompleted(
                    CompletedUploadEvent(
                        outboxId = item.id,
                        moment = publishResult.moment,
                        streak = publishResult.streak,
                        isPrivate = item.isPrivate
                    )
                )
            } catch (e: Throwable) {
                val errText = e.localizedMessage ?: "Gửi khoảnh khắc thất bại"
                outboxDao.updateProgress(
                    id = item.id,
                    status = OutboxEntity.STATUS_FAILED,
                    percent = item.progressPercent,
                    message = "Gửi thất bại — Chạm để thử lại",
                    error = errText
                )
            }
        }

        return Result.success()
    }

    private fun createForegroundInfo(title: String, progress: Int): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Locket Photobooth")
            .setContentText(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, progress.coerceIn(0, 100), progress <= 0)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotificationProgress(message: String, percent: Int) {
        runCatching {
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("Locket Photobooth")
                .setContentText(message)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setProgress(100, percent.coerceIn(0, 100), false)
                .build()
            nm.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Tiến trình gửi ảnh & video Locket",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Hiển thị thanh tiến trình nén GPU và tải khoảnh khắc Locket dưới nền"
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "locket_upload_channel"
        private const val NOTIFICATION_ID = 4108
    }
}

