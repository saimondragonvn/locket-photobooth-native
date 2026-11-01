package com.elingo.locket.media.uploader

import com.elingo.locket.core.model.CaptionOverlayConfig
import com.elingo.locket.core.network.LocketApiClient
import com.elingo.locket.core.network.LocketApiException
import com.elingo.locket.core.network.PublishMomentResult
import kotlinx.coroutines.delay
import java.io.File
import java.util.UUID
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 3-Tier Smart Upload Engine (Exact Native Port of module 9580 in 941.e2a6ca53.chunk.js):
 * - Tier 1A (Direct GCS Signed URL PUT for Images <= 1 MB): Zero proxy bottleneck, fastest publish.
 * - Tier 1B (Direct GCS Signed URL PUT for MP4 Videos < 6 MB & no server filter): Direct PUT + `/publish-video` + `jobId` poll.
 * - Tier 2 (Resumable 1 MB Chunked Upload `/upload-part` + `/upload-part/status`): Survives flaky mobile networks
 *   and handles large videos/Private Vault files up to 200 MB with automatic offset resume & 5 retries per chunk.
 * - Tier 3 (Direct Multipart `/upload-media` or `/private-moments`): Used for small Private Vault images or fallback.
 */
class ThreeTierMediaUploader(
    private val apiClient: LocketApiClient
) {
    suspend fun uploadPreparedMedia(
        clientId: String,
        file: File,
        isVideo: Boolean,
        isPrivate: Boolean,
        filterId: String,
        caption: String,
        overlay: CaptionOverlayConfig,
        recipients: List<String>,
        streakDayYyyyMmDd: Int?,
        perks: List<String>,
        existingUploadId: String?,
        existingJobId: String?,
        onUploadIdAssigned: suspend (String) -> Unit,
        onJobIdAssigned: suspend (String?) -> Unit,
        onProgress: suspend (percent: Int, message: String) -> Unit
    ): PublishMomentResult {
        // 0. Resume directly into server video-job polling if the file was already uploaded before a crash/restart
        if (!existingJobId.isNullOrBlank()) {
            onProgress(85, "Đang đồng bộ tiến trình xử lý video...")
            return apiClient.pollUploadJob(existingJobId) { pct, msg ->
                // Safe non-suspending callback bridge handled inside poll loop
            }.also {
                onJobIdAssigned(null)
            }
        }

        if (!file.exists() || file.length() <= 0L) {
            throw LocketApiException("Không tìm thấy tệp phương tiện để tải lên")
        }

        val sizeBytes = file.length()
        val mimeType = resolveMimeType(file, isVideo)

        // =====================================================================
        // TIER 1A: Public/Friends JPEG Image <= 1,048,575 B -> Direct GCS PUT
        // =====================================================================
        if (!isPrivate && !isVideo && sizeBytes in 1L..TIER1_MAX_IMAGE_BYTES) {
            try {
                onProgress(15, "Khởi tạo phiên tải nhanh GCS...")
                val session = apiClient.createUploadSession(
                    contentType = mimeType,
                    sizeBytes = sizeBytes,
                    isVideo = false,
                    perks = perks
                )
                apiClient.putBinaryToGcs(session.uploadUrl, file) { ratio ->
                    // Progress callback is synchronous; lightweight status updates happen around milestones
                }
                onProgress(88, "Đang xuất bản ảnh lên Locket...")
                val published = apiClient.publishImage(
                    objectPath = session.objectPath,
                    caption = caption,
                    overlay = overlay,
                    recipients = recipients,
                    streakDayYyyyMmDd = streakDayYyyyMmDd,
                    perks = perks
                )
                onProgress(100, "Đã gửi khoảnh khắc!")
                return published
            } catch (e: LocketApiException) {
                if (isNonFallbackAccessError(e)) throw e
                // Fall through to Tier 2 / Tier 3 if GCS signed URL failed due to network/CORS/proxy
            }
        }

        // =====================================================================
        // TIER 1B: Public/Friends MP4 Video < 6 MB (6,291,456 B) & filter=="none" -> Direct GCS PUT
        // =====================================================================
        val hasServerVideoFilter = isVideo && filterId.isNotBlank() && filterId != "none"
        val isMp4 = file.extension.equals("mp4", ignoreCase = true) || mimeType == "video/mp4"
        if (!isPrivate && isVideo && isMp4 && !hasServerVideoFilter && sizeBytes in 1L until TIER1_MAX_VIDEO_BYTES) {
            try {
                onProgress(15, "Khởi tạo phiên tải video GCS...")
                val session = apiClient.createUploadSession(
                    contentType = "video/mp4",
                    sizeBytes = sizeBytes,
                    isVideo = true,
                    perks = perks
                )
                onProgress(25, "Đang đẩy video trực tiếp lên đám mây...")
                apiClient.putBinaryToGcs(session.uploadUrl, file) { _ -> }
                onProgress(80, "Đang xác nhận xuất bản video...")
                val published = apiClient.publishVideo(
                    objectPath = session.objectPath,
                    caption = caption,
                    overlay = overlay,
                    recipients = recipients,
                    streakDayYyyyMmDd = streakDayYyyyMmDd,
                    perks = perks
                )
                return awaitJobIfNeeded(published, onJobIdAssigned, onProgress)
            } catch (e: LocketApiException) {
                if (isNonFallbackAccessError(e)) throw e
                // Fall through to Tier 2 Resumable 1MB Chunked Upload
            }
        }

        // =====================================================================
        // TIER 2: Resumable 1 MB Chunked Upload (`sizeBytes > 1,048,576`)
        // =====================================================================
        if (sizeBytes > CHUNK_SIZE_BYTES) {
            val uploadId = existingUploadId?.takeIf { it.isNotBlank() } ?: generateUploadId().also {
                onUploadIdAssigned(it)
            }

            var offset = if (!existingUploadId.isNullOrBlank()) {
                runCatching { apiClient.getUploadPartStatus(uploadId) }.getOrDefault(0L)
            } else {
                0L
            }.coerceIn(0L, sizeBytes)

            val totalChunks = ((sizeBytes + CHUNK_SIZE_BYTES - 1L) / CHUNK_SIZE_BYTES).coerceAtLeast(1L)

            while (offset < sizeBytes) {
                val chunkLen = min(CHUNK_SIZE_BYTES, sizeBytes - offset)
                val chunkIdx = (offset / CHUNK_SIZE_BYTES) + 1L
                val pct = (15 + ((offset.toDouble() / sizeBytes.toDouble()) * 68.0).roundToInt()).coerceIn(15, 83)
                onProgress(pct, "Đang tải mảnh $chunkIdx/$totalChunks...")

                var attempt = 0
                var nextOffset = offset
                while (true) {
                    try {
                        val serverReceived = apiClient.uploadPartChunk(
                            uploadId = uploadId,
                            file = file,
                            offset = offset,
                            chunkSize = chunkLen,
                            onChunkProgress = { _ -> }
                        )
                        nextOffset = if (serverReceived > offset) serverReceived else (offset + chunkLen)
                        break
                    } catch (e: Throwable) {
                        if (e is LocketApiException && isNonFallbackAccessError(e)) throw e
                        attempt++
                        if (attempt >= MAX_CHUNK_RETRIES) {
                            throw if (e is LocketApiException) {
                                e
                            } else {
                                LocketApiException("Mạng không ổn định khi tải mảnh $chunkIdx: ${e.localizedMessage ?: "Lỗi kết nối"}")
                            }
                        }
                        delay(1500L * attempt)
                        // Re-sync exact byte offset from server before retrying so partial bytes are never corrupted
                        val synced = runCatching { apiClient.getUploadPartStatus(uploadId) }.getOrNull()
                        if (synced != null && synced > offset) {
                            nextOffset = synced
                            break
                        }
                    }
                }
                offset = nextOffset.coerceAtMost(sizeBytes)
            }

            onProgress(85, if (isPrivate) "Đang lưu vào kho riêng tư..." else "Đang hoàn tất khoảnh khắc...")
            val finalResult = if (isPrivate) {
                apiClient.uploadPrivateMoment(
                    file = file,
                    mimeType = mimeType,
                    isVideo = isVideo,
                    uploadId = uploadId,
                    clientId = clientId,
                    caption = caption,
                    overlay = overlay,
                    filterId = filterId,
                    perks = perks
                )
            } else {
                apiClient.uploadMediaFinal(
                    file = file,
                    mimeType = mimeType,
                    isVideo = isVideo,
                    uploadId = uploadId,
                    caption = caption,
                    overlay = overlay,
                    recipients = recipients,
                    filterId = filterId,
                    streakDayYyyyMmDd = streakDayYyyyMmDd,
                    perks = perks
                )
            }
            return awaitJobIfNeeded(finalResult, onJobIdAssigned, onProgress)
        }

        // =====================================================================
        // TIER 3: Direct Multipart FormData (Small Private Vault photos or <= 1 MB fallback)
        // =====================================================================
        onProgress(35, if (isPrivate) "Đang lưu ảnh riêng tư..." else "Đang tải tệp lên máy chủ...")
        val directResult = if (isPrivate) {
            apiClient.uploadPrivateMoment(
                file = file,
                mimeType = mimeType,
                isVideo = isVideo,
                uploadId = null,
                clientId = clientId,
                caption = caption,
                overlay = overlay,
                filterId = filterId,
                perks = perks
            )
        } else {
            apiClient.uploadMediaFinal(
                file = file,
                mimeType = mimeType,
                isVideo = isVideo,
                uploadId = null,
                caption = caption,
                overlay = overlay,
                recipients = recipients,
                filterId = filterId,
                streakDayYyyyMmDd = streakDayYyyyMmDd,
                perks = perks
            )
        }
        return awaitJobIfNeeded(directResult, onJobIdAssigned, onProgress)
    }

    private suspend fun awaitJobIfNeeded(
        initial: PublishMomentResult,
        onJobIdAssigned: suspend (String?) -> Unit,
        onProgress: suspend (percent: Int, message: String) -> Unit
    ): PublishMomentResult {
        val jobId = initial.jobId
        if (jobId.isNullOrBlank()) {
            onProgress(100, "Hoàn tất!")
            return initial
        }
        onJobIdAssigned(jobId)
        onProgress(86, "Máy chủ đang xử lý video...")
        val completed = apiClient.pollUploadJob(jobId) { _, _ -> }
        onJobIdAssigned(null)
        onProgress(100, "Đã đăng video!")
        return completed
    }

    private fun isNonFallbackAccessError(e: LocketApiException): Boolean {
        return e.statusCode == 401 ||
            e.statusCode == 403 ||
            e.errorCode == "SUPPORTER_REQUIRED" ||
            e.isJobFailed
    }

    private fun generateUploadId(): String {
        val base36Time = java.lang.Long.toString(System.currentTimeMillis(), 36)
        val hex16 = UUID.randomUUID().toString().replace("-", "").take(16)
        return "up$base36Time$hex16"
    }

    private fun resolveMimeType(file: File, isVideo: Boolean): String {
        val ext = file.extension.lowercase()
        return if (isVideo) {
            when (ext) {
                "mov" -> "video/quicktime"
                "webm" -> "video/webm"
                else -> "video/mp4"
            }
        } else {
            when (ext) {
                "png" -> "image/png"
                "webp" -> "image/webp"
                else -> "image/jpeg"
            }
        }
    }

    companion object {
        const val TIER1_MAX_IMAGE_BYTES = 1_048_575L // < 1 MB
        const val TIER1_MAX_VIDEO_BYTES = 6_291_456L // < 6 MB
        const val CHUNK_SIZE_BYTES = 1_048_576L      // 1 MB exact resumable slice
        private const val MAX_CHUNK_RETRIES = 5
    }
}

