package com.elingo.locket.core.network

import com.elingo.locket.core.auth.SessionManager
import com.elingo.locket.core.crypto.OpenSslAesCipher
import com.elingo.locket.core.crypto.RsaOaepLoginCipher
import com.elingo.locket.core.model.CaptionOverlayConfig
import com.elingo.locket.core.model.CaptionPreset
import com.elingo.locket.core.model.ChatMessage
import com.elingo.locket.core.model.Conversation
import com.elingo.locket.core.model.DonateMeResponse
import com.elingo.locket.core.model.DonorLeaderboardItem
import com.elingo.locket.core.model.FeedPage
import com.elingo.locket.core.model.FriendRequestsData
import com.elingo.locket.core.model.FriendUser
import com.elingo.locket.core.model.LocketSession
import com.elingo.locket.core.model.MessageReaction
import com.elingo.locket.core.model.Moment
import com.elingo.locket.core.model.MomentActivity
import com.elingo.locket.core.model.MomentActivityPerson
import com.elingo.locket.core.model.NearbyPlace
import com.elingo.locket.core.model.OverlayDisplay
import com.elingo.locket.core.model.PerkTier
import com.elingo.locket.core.model.StreakAnchor
import com.elingo.locket.core.model.WeatherInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class LocketApiException(
    override val message: String,
    val statusCode: Int = 0,
    val errorCode: String? = null,
    val isJobFailed: Boolean = false
) : IOException(message)

data class UploadSessionResult(
    val uploadUrl: String,
    val objectPath: String
)

data class PublishMomentResult(
    val moment: Moment?,
    val streak: StreakAnchor? = null,
    val jobId: String? = null
)

class LocketApiClient(
    private val sessionManager: SessionManager
) {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private val refreshMutex = Mutex()
    private val loginKeyMutex = Mutex()
    @Volatile
    private var cachedLoginSpki: String? = null
    private val mediaGoneCache = ConcurrentHashMap<String, Boolean>()

    private val apiHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // Dedicated clean client for Google Cloud Storage Signed URL PUT uploads
    private val gcsHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // =========================================================================
    // 1. AUTHENTICATION, RSA-OAEP-256 ENCRYPTION & 401 MUTEX REFRESH
    // =========================================================================

    /**
     * Fetches and caches the X.509 SPKI RSA-OAEP-256 public key from `GET /locket/login-key`
     * (exact port of `St` in `main.434d302f.js` Module 8406).
     */
    suspend fun getLoginPublicKeySpki(forceRefresh: Boolean = false): String = withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            cachedLoginSpki?.let { return@withContext it }
        }
        loginKeyMutex.withLock {
            if (!forceRefresh) {
                cachedLoginSpki?.let { return@withLock it }
            }
            val req = Request.Builder()
                .url("$BASE_URL/login-key")
                .get()
                .build()
            try {
                apiHttpClient.newCall(req).execute().use { resp ->
                    val raw = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        cachedLoginSpki = null
                        throw parseApiError(resp.code, raw, "Không lấy được khóa mã hóa đăng nhập")
                    }
                    val obj = json.parseToJsonElement(raw).jsonObject
                    val spki = obj["spki"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isNotBlank() }
                        ?: throw LocketApiException("Máy chủ không trả về khóa công khai SPKI", resp.code)
                    cachedLoginSpki = spki
                    spki
                }
            } catch (e: Throwable) {
                cachedLoginSpki = null
                throw e
            }
        }
    }

    /**
     * Asymmetric RSA-OAEP-256 login with automatic `LOGIN_KEY_STALE` retry
     * (exact port of `qt` & `_t` in `main.434d302f.js` Module 8406).
     */
    suspend fun login(email: String, password: String): LocketSession = withContext(Dispatchers.IO) {
        val cleanEmail = email.trim()
        var attempt = 0
        while (true) {
            val spki = getLoginPublicKeySpki(forceRefresh = attempt > 0)
            val creds = try {
                RsaOaepLoginCipher.encryptCredentials(spki, cleanEmail, password)
            } catch (e: IllegalArgumentException) {
                throw LocketApiException(e.message ?: "Email hoặc mật khẩu quá dài", 400)
            }

            try {
                val session = loginWithEncryptedCredentials(creds, cleanEmail)
                sessionManager.saveSession(
                    newSession = session,
                    encryptedCreds = creds,
                    rawEmailForKeystore = cleanEmail,
                    rawPasswordForKeystore = password
                )
                return@withContext session
            } catch (e: LocketApiException) {
                if (attempt == 0 && e.errorCode == "LOGIN_KEY_STALE") {
                    cachedLoginSpki = null
                    attempt++
                    continue
                }
                throw e
            }
        }
        @Suppress("UNREACHABLE_CODE")
        throw LocketApiException("Đăng nhập thất bại")
    }

    private fun loginWithEncryptedCredentials(
        creds: OpenSslAesCipher.EncryptedCredentials,
        fallbackEmail: String = ""
    ): LocketSession {
        val bodyObj = buildJsonObject {
            put("email", creds.encryptedEmail)
            put("password", creds.encryptedPassword)
        }
        val req = Request.Builder()
            .url("$BASE_URL/login")
            .post(bodyObj.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        apiHttpClient.newCall(req).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw parseApiError(resp.code, raw, "Đăng nhập thất bại")
            }
            val root = json.parseToJsonElement(raw).jsonObject
            val userObj = root["user"]?.jsonObject ?: root
            val idToken = userObj["idToken"]?.jsonPrimitive?.contentOrNull
                ?: throw LocketApiException("Máy chủ không trả về idToken", resp.code)
            val localId = userObj["localId"]?.jsonPrimitive?.contentOrNull
                ?: userObj["uid"]?.jsonPrimitive?.contentOrNull
                ?: throw LocketApiException("Máy chủ không trả về mã tài khoản", resp.code)
            val expiresIn = userObj["expiresIn"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 3600L

            return LocketSession(
                localId = localId,
                idToken = idToken,
                refreshToken = userObj["refreshToken"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                email = userObj["email"]?.jsonPrimitive?.contentOrNull ?: fallbackEmail,
                displayName = userObj["displayName"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                username = userObj["username"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                photoUrl = userObj["photoUrl"]?.jsonPrimitive?.contentOrNull
                    ?: userObj["profilePicture"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                expiresInSeconds = expiresIn,
                expiresAtMillis = System.currentTimeMillis() + expiresIn * 1000L
            )
        }
    }

    /**
     * Exact Native port of `uG` + `Qt` (`POST /locket/upgrade-login` in `main.434d302f.js` Modules 4891 & 8406):
     * Automatically migrates any legacy v1 OpenSSL AES (`U2FsdGVkX1...`) stored credentials to RSA-OAEP-256.
     */
    suspend fun upgradeLegacyStoredCredentials() = withContext(Dispatchers.IO) {
        val candidates = LinkedHashMap<Pair<String, String>, OpenSslAesCipher.EncryptedCredentials>()
        sessionManager.getStoredEncryptedCredentials()
            ?.takeIf { RsaOaepLoginCipher.hasLegacyCredentials(it) }
            ?.let { candidates[it.encryptedEmail to it.encryptedPassword] = it }

        sessionManager.savedAccounts.value.forEach { acc ->
            val eEmail = acc.encryptedEmail
            val ePass = acc.encryptedPassword
            if (!eEmail.isNullOrBlank() && !ePass.isNullOrBlank()) {
                val c = OpenSslAesCipher.EncryptedCredentials(eEmail, ePass)
                if (RsaOaepLoginCipher.hasLegacyCredentials(c)) {
                    candidates[eEmail to ePass] = c
                }
            }
        }

        if (candidates.isEmpty()) return@withContext
        val orderedList = candidates.values.toList()

        val data = postAuthenticatedJson("/upgrade-login", "Không nâng cấp được dữ liệu đăng nhập") { token ->
            buildJsonObject {
                put("idToken", token)
                put(
                    "logins",
                    buildJsonArray {
                        orderedList.forEach { item ->
                            add(
                                buildJsonObject {
                                    put("email", item.encryptedEmail)
                                    put("password", item.encryptedPassword)
                                }
                            )
                        }
                    }
                )
            }
        }

        val returnedArr = data["logins"]?.takeIf { it is JsonArray }?.jsonArray ?: return@withContext
        val replacementMap = mutableMapOf<Pair<String, String>, OpenSslAesCipher.EncryptedCredentials?>()

        orderedList.forEachIndexed { idx, oldCreds ->
            val resObj = returnedArr.getOrNull(idx)?.takeIf { it is JsonObject }?.jsonObject ?: return@forEachIndexed
            val newEmail = resObj["email"]?.jsonPrimitive?.contentOrNull
            val newPass = resObj["password"]?.jsonPrimitive?.contentOrNull
            val err = resObj["error"]?.jsonPrimitive?.contentOrNull
            val key = oldCreds.encryptedEmail to oldCreds.encryptedPassword
            if (!newEmail.isNullOrBlank() && !newPass.isNullOrBlank()) {
                replacementMap[key] = OpenSslAesCipher.EncryptedCredentials(newEmail, newPass)
            } else if (err == "LOGIN_DATA_INVALID") {
                replacementMap[key] = null
            }
        }

        sessionManager.replaceUpgradedCredentials(replacementMap)
    }

    suspend fun ensureValidToken(forceRefresh: Boolean = false): String = withContext(Dispatchers.IO) {
        val current = sessionManager.currentSession()
            ?: throw LocketApiException("Phiên đăng nhập đã hết hạn, vui lòng đăng nhập lại", 401)

        if (!forceRefresh && !current.isExpiringWithin(60_000L)) {
            return@withContext current.idToken
        }

        refreshMutex.withLock {
            val latest = sessionManager.currentSession()
                ?: throw LocketApiException("Phiên đăng nhập đã hết hạn", 401)
            if (!forceRefresh && !latest.isExpiringWithin(60_000L)) {
                return@withLock latest.idToken
            }

            val refreshed = tryRefreshToken(latest) ?: tryReLoginFromStoredCredentials(latest)
            if (refreshed != null) {
                sessionManager.saveSession(refreshed, null)
                refreshed.idToken
            } else if (!forceRefresh) {
                latest.idToken
            } else {
                sessionManager.logoutCurrentSession()
                throw LocketApiException("Phiên đăng nhập đã hết hạn, vui lòng đăng nhập lại", 401)
            }
        }
    }

    private fun tryRefreshToken(current: LocketSession): LocketSession? {
        if (current.refreshToken.isBlank()) return null
        return runCatching {
            val payload = buildJsonObject {
                put("refreshToken", current.refreshToken)
            }
            val req = Request.Builder()
                .url("$BASE_URL/refresh")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            apiHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val data = json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject
                val newIdToken = data["idToken"]?.jsonPrimitive?.contentOrNull ?: return null
                val expiresIn = data["expiresIn"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                    ?: current.expiresInSeconds
                current.copy(
                    idToken = newIdToken,
                    refreshToken = data["refreshToken"]?.jsonPrimitive?.contentOrNull ?: current.refreshToken,
                    localId = data["localId"]?.jsonPrimitive?.contentOrNull ?: current.localId,
                    expiresInSeconds = expiresIn,
                    expiresAtMillis = System.currentTimeMillis() + expiresIn * 1000L
                )
            }
        }.getOrNull()
    }

    private suspend fun tryReLoginFromStoredCredentials(current: LocketSession): LocketSession? {
        val creds = sessionManager.getStoredEncryptedCredentials()
        if (creds != null && !RsaOaepLoginCipher.hasLegacyCredentials(creds)) {
            val fromStored = runCatching {
                loginWithEncryptedCredentials(creds, current.email)
            }.getOrNull()
            if (fromStored != null) return fromStored
        }

        // Fallback: re-encrypt from Android Keystore using fresh `/locket/login-key` SPKI
        val rawPair = sessionManager.getKeystoreRawCredentials(current.localId) ?: return null
        return runCatching {
            val spki = getLoginPublicKeySpki(forceRefresh = true)
            val freshCreds = RsaOaepLoginCipher.encryptCredentials(spki, rawPair.first, rawPair.second)
            val freshSession = loginWithEncryptedCredentials(freshCreds, rawPair.first)
            sessionManager.saveSession(freshSession, freshCreds)
            freshSession
        }.getOrNull()
    }

    private suspend fun postAuthenticatedJson(
        endpointPath: String,
        fallbackError: String,
        buildFields: (idToken: String) -> JsonObject
    ): JsonObject = withContext(Dispatchers.IO) {
        var token = ensureValidToken(forceRefresh = false)
        var retried = false

        while (true) {
            val payload = buildFields(token)
            val req = Request.Builder()
                .url("$BASE_URL$endpointPath")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            apiHttpClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (resp.code == 401 && !retried) {
                    retried = true
                    token = ensureValidToken(forceRefresh = true)
                    return@use
                }
                if (!resp.isSuccessful) {
                    throw parseApiError(resp.code, raw, fallbackError)
                }
                return@withContext if (raw.isBlank()) {
                    buildJsonObject {}
                } else {
                    runCatching { json.parseToJsonElement(raw).jsonObject }.getOrElse { buildJsonObject {} }
                }
            }
        }
        @Suppress("UNREACHABLE_CODE")
        buildJsonObject {}
    }

    private suspend fun postAuthenticatedMultipart(
        endpointPath: String,
        fallbackError: String,
        buildMultipart: (idToken: String, userId: String) -> MultipartBody
    ): Pair<Int, JsonObject> = withContext(Dispatchers.IO) {
        var token = ensureValidToken(forceRefresh = false)
        var retried = false

        while (true) {
            val uid = sessionManager.currentSession()?.localId.orEmpty()
            val multipart = buildMultipart(token, uid)
            val req = Request.Builder()
                .url("$BASE_URL$endpointPath")
                .post(multipart)
                .build()

            apiHttpClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (resp.code == 401 && !retried) {
                    retried = true
                    token = ensureValidToken(forceRefresh = true)
                    return@use
                }
                if (!resp.isSuccessful) {
                    throw parseApiError(resp.code, raw, fallbackError)
                }
                val parsed = if (raw.isBlank()) {
                    buildJsonObject {}
                } else {
                    runCatching { json.parseToJsonElement(raw).jsonObject }.getOrElse { buildJsonObject {} }
                }
                return@withContext resp.code to parsed
            }
        }
        @Suppress("UNREACHABLE_CODE")
        200 to buildJsonObject {}
    }

    // =========================================================================
    // 2. 3-TIER MEDIA UPLOAD & JOB POLLING ENGINE
    // =========================================================================

    suspend fun createUploadSession(
        contentType: String,
        sizeBytes: Long,
        isVideo: Boolean,
        perks: List<String> = emptyList()
    ): UploadSessionResult {
        val data = postAuthenticatedJson("/upload-session", "Không khởi tạo được phiên tải lên") { token ->
            buildJsonObject {
                put("idToken", token)
                if (isVideo) put("kind", "video")
                put("contentType", contentType)
                put("size", sizeBytes)
                if (perks.isNotEmpty()) {
                    put("perks", JsonArray(perks.map { JsonPrimitive(it) }))
                }
            }
        }
        val uploadUrl = data["uploadUrl"]?.jsonPrimitive?.contentOrNull
            ?: throw LocketApiException("Thiếu uploadUrl từ máy chủ")
        val objectPath = data["objectPath"]?.jsonPrimitive?.contentOrNull
            ?: throw LocketApiException("Thiếu objectPath từ máy chủ")
        return UploadSessionResult(uploadUrl = uploadUrl, objectPath = objectPath)
    }

    suspend fun putBinaryToGcs(
        uploadUrl: String,
        file: File,
        onProgress: (Float) -> Unit
    ) = withContext(Dispatchers.IO) {
        val body = ProgressRequestBody(
            file = file,
            contentTypeString = "application/octet-stream",
            onProgress = { sent, total ->
                if (total > 0L) onProgress((sent.toFloat() / total.toFloat()).coerceIn(0f, 1f))
            }
        )
        val req = Request.Builder()
            .url(uploadUrl)
            .put(body)
            .header("Content-Type", "application/octet-stream")
            .header("x-goog-upload-command", "upload, finalize")
            .header("x-goog-upload-offset", "0")
            .build()

        gcsHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw LocketApiException("Tải tệp lên GCS thất bại (HTTP ${resp.code})", resp.code)
            }
        }
    }

    suspend fun publishImage(
        objectPath: String,
        caption: String,
        overlay: CaptionOverlayConfig,
        recipients: List<String>,
        streakDayYyyyMmDd: Int?,
        perks: List<String>
    ): PublishMomentResult {
        val data = postAuthenticatedJson("/publish-image", "Đăng ảnh thất bại") { token ->
            buildPublishJson(
                idToken = token,
                objectPath = objectPath,
                caption = caption,
                overlay = overlay,
                recipients = recipients,
                streakDayYyyyMmDd = streakDayYyyyMmDd,
                perks = perks
            )
        }
        return PublishMomentResult(
            moment = data["moment"]?.jsonObject?.let { parseMoment(it) },
            streak = parseStreakAnchor(data["streak"])
        )
    }

    suspend fun publishVideo(
        objectPath: String,
        caption: String,
        overlay: CaptionOverlayConfig,
        recipients: List<String>,
        streakDayYyyyMmDd: Int?,
        perks: List<String>
    ): PublishMomentResult {
        val data = postAuthenticatedJson("/publish-video", "Đăng video thất bại") { token ->
            buildPublishJson(
                idToken = token,
                objectPath = objectPath,
                caption = caption,
                overlay = overlay,
                recipients = recipients,
                streakDayYyyyMmDd = streakDayYyyyMmDd,
                perks = perks
            )
        }
        return PublishMomentResult(
            moment = data["moment"]?.jsonObject?.let { parseMoment(it) },
            streak = parseStreakAnchor(data["streak"]),
            jobId = data["jobId"]?.jsonPrimitive?.contentOrNull
        )
    }

    suspend fun getUploadPartStatus(uploadId: String): Long {
        val data = postAuthenticatedJson("/upload-part/status", "Không kiểm tra được tiến độ tệp") { token ->
            buildJsonObject {
                put("idToken", token)
                put("uploadId", uploadId)
            }
        }
        return data["received"]?.jsonPrimitive?.longOrNull ?: 0L
    }

    suspend fun uploadPartChunk(
        uploadId: String,
        file: File,
        offset: Long,
        chunkSize: Long,
        onChunkProgress: (sentInChunk: Long) -> Unit
    ): Long {
        val (_, data) = postAuthenticatedMultipart("/upload-part", "Tải mảnh dữ liệu thất bại") { token, _ ->
            val sliceBody = ProgressRequestBody(
                file = file,
                contentTypeString = "application/octet-stream",
                offset = offset,
                length = chunkSize,
                onProgress = { sent, _ -> onChunkProgress(sent) }
            )
            MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("idToken", token)
                .addFormDataPart("uploadId", uploadId)
                .addFormDataPart("offset", offset.toString())
                .addFormDataPart("total", file.length().toString())
                .addFormDataPart("part", "part", sliceBody)
                .build()
        }
        return data["received"]?.jsonPrimitive?.longOrNull ?: (offset + chunkSize)
    }

    suspend fun uploadMediaFinal(
        file: File,
        mimeType: String,
        isVideo: Boolean,
        uploadId: String?,
        caption: String,
        overlay: CaptionOverlayConfig,
        recipients: List<String>,
        filterId: String?,
        streakDayYyyyMmDd: Int?,
        perks: List<String>,
        onProgress: ((Float) -> Unit)? = null
    ): PublishMomentResult {
        val (code, data) = postAuthenticatedMultipart("/upload-media", "Gửi khoảnh khắc thất bại") { token, userId ->
            val builder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("caption", caption)
                .addFormDataPart("userId", userId)
                .addFormDataPart("idToken", token)

            if (!uploadId.isNullOrBlank()) {
                builder.addFormDataPart("uploadId", uploadId)
                builder.addFormDataPart("total", file.length().toString())
                builder.addFormDataPart("name", file.name)
                builder.addFormDataPart("type", mimeType)
            } else {
                val partName = if (isVideo) "videos" else "images"
                val fileBody = ProgressRequestBody(
                    file = file,
                    contentTypeString = mimeType,
                    onProgress = { sent, total ->
                        if (total > 0L) onProgress?.invoke(sent.toFloat() / total.toFloat())
                    }
                )
                builder.addFormDataPart(partName, file.name, fileBody)
            }

            appendOverlayFormParts(builder, overlay)
            if (recipients.isNotEmpty()) {
                builder.addFormDataPart("recipients", json.encodeToString(recipients))
            }
            if (isVideo && !filterId.isNullOrBlank() && filterId != "none") {
                builder.addFormDataPart("filter", filterId)
            }
            if (perks.isNotEmpty()) {
                builder.addFormDataPart("perks", json.encodeToString(perks))
            }
            if (streakDayYyyyMmDd != null) {
                builder.addFormDataPart("streakDay", streakDayYyyyMmDd.toString())
            }
            builder.build()
        }

        val jobId = if (code == 202) data["jobId"]?.jsonPrimitive?.contentOrNull else data["jobId"]?.jsonPrimitive?.contentOrNull
        return PublishMomentResult(
            moment = data["moment"]?.jsonObject?.let { parseMoment(it) },
            streak = parseStreakAnchor(data["streak"]),
            jobId = jobId
        )
    }

    suspend fun uploadPrivateMoment(
        file: File,
        mimeType: String,
        isVideo: Boolean,
        uploadId: String?,
        clientId: String,
        caption: String,
        overlay: CaptionOverlayConfig,
        filterId: String?,
        perks: List<String>,
        onProgress: ((Float) -> Unit)? = null
    ): PublishMomentResult {
        val (code, data) = postAuthenticatedMultipart("/private-moments", "Lưu riêng tư thất bại") { token, _ ->
            val builder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("caption", caption)
                .addFormDataPart("idToken", token)
                .addFormDataPart("clientId", clientId)

            if (!uploadId.isNullOrBlank()) {
                builder.addFormDataPart("uploadId", uploadId)
                builder.addFormDataPart("total", file.length().toString())
                builder.addFormDataPart("name", file.name)
                builder.addFormDataPart("type", mimeType)
            } else {
                val partName = if (isVideo) "videos" else "images"
                val fileBody = ProgressRequestBody(
                    file = file,
                    contentTypeString = mimeType,
                    onProgress = { sent, total ->
                        if (total > 0L) onProgress?.invoke(sent.toFloat() / total.toFloat())
                    }
                )
                builder.addFormDataPart(partName, file.name, fileBody)
            }

            appendOverlayFormParts(builder, overlay)
            if (isVideo && !filterId.isNullOrBlank() && filterId != "none") {
                builder.addFormDataPart("filter", filterId)
            }
            if (perks.isNotEmpty()) {
                builder.addFormDataPart("perks", json.encodeToString(perks))
            }
            builder.build()
        }

        val jobId = if (code == 202) data["jobId"]?.jsonPrimitive?.contentOrNull else data["jobId"]?.jsonPrimitive?.contentOrNull
        return PublishMomentResult(
            moment = data["moment"]?.jsonObject?.let { parseMoment(it, forcePrivate = true) },
            jobId = jobId
        )
    }

    suspend fun pollUploadJob(
        jobId: String,
        onProgress: (percent: Int, message: String) -> Unit
    ): PublishMomentResult = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + 20 * 60_000L
        while (System.currentTimeMillis() < deadline) {
            val req = Request.Builder()
                .url("$BASE_URL/upload-media/$jobId")
                .get()
                .build()

            apiHttpClient.newCall(req).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw parseApiError(resp.code, raw, "Không kiểm tra được trạng thái xử lý video")
                }
                val data = json.parseToJsonElement(raw).jsonObject
                val status = data["status"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val progObj = data["progress"]?.jsonObject
                val pct = progObj?.get("percent")?.jsonPrimitive?.intOrNull ?: 85
                val msg = progObj?.get("message")?.jsonPrimitive?.contentOrNull ?: "Máy chủ đang xử lý video..."
                onProgress(pct, msg)

                when (status) {
                    "completed" -> {
                        val resultObj = data["result"]?.jsonObject ?: data
                        return@withContext PublishMomentResult(
                            moment = resultObj["moment"]?.jsonObject?.let { parseMoment(it) },
                            streak = parseStreakAnchor(resultObj["streak"])
                        )
                    }
                    "failed" -> {
                        val errMsg = data["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                            ?: "Xử lý video trên máy chủ thất bại"
                        throw LocketApiException(errMsg, statusCode = 400, isJobFailed = true)
                    }
                }
            }
            delay(1500L)
        }
        throw LocketApiException("Quá thời gian chờ xử lý video, vui lòng thử lại")
    }

    // =========================================================================
    // 3. FEED, PRIVATE VAULT, MEMORIES, REACTIONS & MEDIA DOWNLOAD
    // =========================================================================

    suspend fun getFeed(limit: Int = 20, cursor: String? = null, userUid: String? = null): FeedPage {
        val data = postAuthenticatedJson("/feed", "Không tải được khoảnh khắc") { token ->
            buildJsonObject {
                put("idToken", token)
                put("limit", limit)
                if (!cursor.isNullOrBlank()) put("cursor", cursor)
                if (!userUid.isNullOrBlank()) put("user", userUid)
            }
        }
        val moments = data["moments"]?.jsonArray?.mapNotNull { el ->
            runCatching { parseMoment(el.jsonObject) }.getOrNull()
        }.orEmpty()
        val users = parseUsersMap(data["users"])
        val me = data["me"]?.takeIf { it !is JsonNull }?.jsonObject?.let { parseFriendUser(it) }
        val nextCursor = data["nextCursor"]?.jsonPrimitive?.contentOrNull
        return FeedPage(moments = moments, users = users, me = me, nextCursor = nextCursor)
    }

    suspend fun getPrivateMoments(limit: Int = 30, cursor: String? = null): FeedPage {
        val data = postAuthenticatedJson("/private-moments", "Không tải được kho riêng tư") { token ->
            buildJsonObject {
                put("idToken", token)
                put("limit", limit)
                if (!cursor.isNullOrBlank()) put("cursor", cursor)
            }
        }
        val moments = data["moments"]?.jsonArray?.mapNotNull { el ->
            runCatching { parseMoment(el.jsonObject, forcePrivate = true) }.getOrNull()
        }.orEmpty()
        return FeedPage(
            moments = moments,
            nextCursor = data["nextCursor"]?.jsonPrimitive?.contentOrNull
        )
    }

    suspend fun getMemoriesStats(): Pair<StreakAnchor?, Int?> {
        val data = postAuthenticatedJson("/memories-stats", "Không tải được thống kê kỷ niệm") { token ->
            buildJsonObject { put("idToken", token) }
        }
        val streak = parseStreakAnchor(data["streak"])
        val lockets = data["lockets"]?.jsonPrimitive?.intOrNull
        return streak to lockets
    }

    suspend fun reactToMoment(momentUid: String, emoji: String) {
        postAuthenticatedJson("/react-moment", "Thả cảm xúc thất bại") { token ->
            buildJsonObject {
                put("idToken", token)
                put("momentUid", momentUid)
                put("reaction", emoji)
            }
        }
    }

    suspend fun commentOnMoment(momentUid: String, receiverUid: String, text: String) {
        postAuthenticatedJson("/comment-moment", "Gửi phản hồi thất bại") { token ->
            buildJsonObject {
                put("idToken", token)
                put("momentUid", momentUid)
                put("receiverUid", receiverUid)
                put("text", text)
            }
        }
    }

    suspend fun markMomentViewed(momentUid: String, momentDate: String) {
        runCatching {
            postAuthenticatedJson("/mark-viewed", "Không đánh dấu đã xem") { token ->
                buildJsonObject {
                    put("idToken", token)
                    put("momentUid", momentUid)
                    put("momentDate", momentDate)
                }
            }
        }
    }

    suspend fun getMomentActivity(momentId: String): MomentActivity {
        val data = postAuthenticatedJson("/moment-activity", "Không tải được hoạt động") { token ->
            buildJsonObject {
                put("idToken", token)
                put("momentId", momentId)
            }
        }
        val people = data["people"]?.jsonArray?.mapNotNull { el ->
            val obj = el.jsonObject
            MomentActivityPerson(
                user = obj["user"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                lastAt = obj["last_at"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                reactions = obj["reactions"]?.jsonArray?.map { r ->
                    val ro = r.jsonObject
                    MessageReaction(
                        sender = ro["sender"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        emoji = ro["emoji"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                        createdAt = ro["created_at"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    )
                }.orEmpty()
            )
        }.orEmpty()
        return MomentActivity(
            views = data["views"]?.jsonPrimitive?.intOrNull ?: 0,
            reactions = data["reactions"]?.jsonPrimitive?.intOrNull ?: 0,
            people = people,
            users = parseUsersMap(data["users"])
        )
    }

    /**
     * Exact Native port of `kn` (`POST /locket/media-status` in `main.434d302f.js` Module 8406, line 2522):
     * Checks whether a dead Firebase Storage thumbnail/video URL has been deleted on Locket so the UI can auto-prune it.
     */
    suspend fun checkFirebaseMediaGone(url: String?): Boolean {
        if (url.isNullOrBlank() || !url.startsWith("https://firebasestorage.googleapis.com/")) {
            return false
        }
        mediaGoneCache[url]?.let { return it }
        return runCatching {
            val data = postAuthenticatedJson("/media-status", "Không kiểm tra được trạng thái tệp") { token ->
                buildJsonObject {
                    put("idToken", token)
                    put("urls", buildJsonArray { add(JsonPrimitive(url)) })
                }
            }
            val goneUrls = data["gone"]?.takeIf { it is JsonArray }?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                .orEmpty()
            val isGone = url in goneUrls
            mediaGoneCache[url] = isGone
            isGone
        }.getOrElse {
            mediaGoneCache.remove(url)
            false
        }
    }

    suspend fun deleteMoment(momentUid: String, ownerUid: String) {
        postAuthenticatedJson("/delete-moment", "Xóa khoảnh khắc thất bại") { token ->
            buildJsonObject {
                put("idToken", token)
                put("momentUid", momentUid)
                put("ownerUid", ownerUid)
            }
        }
    }

    suspend fun deletePrivateMoment(id: String) {
        postAuthenticatedJson("/private-moment/delete", "Xóa ảnh riêng tư thất bại") { token ->
            buildJsonObject {
                put("idToken", token)
                put("id", id)
            }
        }
    }

    suspend fun downloadMediaToFile(url: String, isPrivate: Boolean, destFile: File): File = withContext(Dispatchers.IO) {
        val req = if (isPrivate) {
            Request.Builder().url(url).get().build()
        } else {
            val token = ensureValidToken()
            val payload = buildJsonObject {
                put("idToken", token)
                put("url", url)
            }
            Request.Builder()
                .url("$BASE_URL/media")
                .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
        }

        apiHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw LocketApiException("Không tải được tệp (HTTP ${resp.code})", resp.code)
            }
            val body = resp.body ?: throw LocketApiException("Dữ liệu tệp rỗng")
            destFile.parentFile?.mkdirs()
            body.byteStream().use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        destFile
    }

    // =========================================================================
    // 4. CAPTION PRESETS, WEATHER & NEARBY PLACES
    // =========================================================================

    suspend fun getCaptionPresets(): List<CaptionPreset> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("$BASE_URL/caption-presets")
            .get()
            .build()
        apiHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext emptyList()
            val raw = resp.body?.string().orEmpty()
            val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return@withContext emptyList()
            val presetsElement = root["presets"] ?: return@withContext emptyList()
            val result = mutableListOf<CaptionPreset>()
            when (presetsElement) {
                is JsonArray -> presetsElement.forEachIndexed { idx, item ->
                    parsePresetObject("preset_$idx", item.jsonObject)?.let(result::add)
                }
                is JsonObject -> presetsElement.entries.forEach { (key, value) ->
                    if (value is JsonObject) {
                        parsePresetObject(key, value)?.let(result::add)
                    } else if (value is JsonArray) {
                        value.forEachIndexed { idx, item ->
                            if (item is JsonObject) parsePresetObject("${key}_$idx", item)?.let(result::add)
                        }
                    }
                }
                else -> Unit
            }
            result
        }
    }

    suspend fun getCaptionWeather(lat: Double, lng: Double): WeatherInfo {
        val data = postAuthenticatedJson("/caption-weather", "Không lấy được thời tiết") { token ->
            buildJsonObject {
                put("idToken", token)
                put("lat", lat)
                put("lng", lng)
            }
        }
        val w = data["weather"]?.jsonObject ?: data
        val temp = w["temp"]?.jsonPrimitive?.doubleOrNull
            ?: w["temperature"]?.jsonPrimitive?.doubleOrNull
        val desc = w["description"]?.jsonPrimitive?.contentOrNull
            ?: w["condition"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val icon = w["icon"]?.jsonPrimitive?.contentOrNull ?: "⛅"
        val label = w["text"]?.jsonPrimitive?.contentOrNull
            ?: listOfNotNull(temp?.let { "${it.toInt()}°C" }, desc.takeIf { it.isNotBlank() }).joinToString(" · ")
        return WeatherInfo(tempC = temp, description = desc, icon = icon, label = label.ifBlank { "Thời tiết" })
    }

    suspend fun getCaptionPlaces(lat: Double, lng: Double): List<NearbyPlace> {
        val data = postAuthenticatedJson("/caption-places", "Không tìm được địa điểm gần đây") { token ->
            buildJsonObject {
                put("idToken", token)
                put("lat", lat)
                put("lng", lng)
            }
        }
        return data["places"]?.jsonArray?.mapNotNull { el ->
            val obj = el.jsonObject
            val name = obj["name"]?.jsonPrimitive?.contentOrNull
                ?: obj["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            NearbyPlace(
                name = name,
                address = obj["address"]?.jsonPrimitive?.contentOrNull
                    ?: obj["subtitle"]?.jsonPrimitive?.contentOrNull.orEmpty()
            )
        }.orEmpty()
    }

    // =========================================================================
    // 5. ACCOUNT, FRIENDS & DIRECT MESSAGES (CHAT)
    // =========================================================================

    suspend fun getAccountInfo(): JsonObject {
        return postAuthenticatedJson("/get-account-info", "Không tải được thông tin hồ sơ") { token ->
            buildJsonObject { put("idToken", token) }
        }
    }

    suspend fun changeAccountName(firstName: String, lastName: String) {
        postAuthenticatedJson("/change-name-account", "Đổi tên thất bại") { token ->
            buildJsonObject {
                put("idToken", token)
                put("firstName", firstName.trim())
                put("lastName", lastName.trim())
            }
        }
        val fullName = "${firstName.trim()} ${lastName.trim()}".trim()
        sessionManager.updateProfileMetadata(displayName = fullName)
    }

    suspend fun changeAvatar(imageFile: File): String? {
        val (_, data) = postAuthenticatedMultipart("/change-avatar", "Đổi ảnh đại diện thất bại") { token, userId ->
            MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("images", imageFile.name, ProgressRequestBody(imageFile, "image/jpeg"))
                .addFormDataPart("userId", userId)
                .addFormDataPart("idToken", token)
                .build()
        }
        val newPhotoUrl = data["photoUrl"]?.jsonPrimitive?.contentOrNull
        val newIdToken = data["idToken"]?.jsonPrimitive?.contentOrNull
        val current = sessionManager.currentSession()
        if (current != null) {
            sessionManager.saveSession(
                current.copy(
                    idToken = newIdToken ?: current.idToken,
                    refreshToken = data["refreshToken"]?.jsonPrimitive?.contentOrNull ?: current.refreshToken,
                    photoUrl = newPhotoUrl ?: current.photoUrl
                )
            )
        }
        return newPhotoUrl
    }

    suspend fun getUserByUsername(username: String): FriendUser? {
        val clean = username.trim().removePrefix("@")
        val data = postAuthenticatedJson("/get-user-by-username", "Không tìm được người dùng") { token ->
            buildJsonObject {
                put("idToken", token)
                put("username", clean)
            }
        }
        val userObj = data["result"]?.jsonObject?.get("data")?.takeIf { it is JsonObject }?.jsonObject
            ?: data["user"]?.takeIf { it is JsonObject }?.jsonObject
            ?: return null
        return parseFriendUser(userObj)
    }

    suspend fun getFriends(refresh: Boolean = false): Pair<List<FriendUser>, FriendUser?> {
        val uid = sessionManager.currentSession()?.localId.orEmpty()
        val data = postAuthenticatedJson("/friends", "Không tải được danh sách bạn bè") { token ->
            buildJsonObject {
                put("idToken", token)
                put("userId", uid)
                put("refresh", refresh)
            }
        }
        val friends = data["friends"]?.jsonArray?.mapNotNull {
            runCatching { parseFriendUser(it.jsonObject) }.getOrNull()
        }.orEmpty()
        val me = data["me"]?.takeIf { it is JsonObject }?.jsonObject?.let { parseFriendUser(it) }
        if (me != null) {
            sessionManager.updateProfileMetadata(
                displayName = me.fullName.takeIf { it.isNotBlank() },
                username = me.username.takeIf { it.isNotBlank() },
                photoUrl = me.profilePictureUrl.takeIf { it.isNotBlank() }
            )
        }
        return friends to me
    }

    suspend fun getFriendRequests(refresh: Boolean = false): FriendRequestsData {
        val uid = sessionManager.currentSession()?.localId.orEmpty()
        val data = postAuthenticatedJson("/friend-requests", "Không tải được lời mời kết bạn") { token ->
            buildJsonObject {
                put("idToken", token)
                put("userId", uid)
                put("refresh", refresh)
            }
        }
        val incoming = data["incoming"]?.jsonArray?.mapNotNull {
            runCatching { parseFriendUser(it.jsonObject) }.getOrNull()
        }.orEmpty()
        val outgoing = data["outgoing"]?.jsonArray?.mapNotNull {
            runCatching { parseFriendUser(it.jsonObject) }.getOrNull()
        }.orEmpty()
        return FriendRequestsData(incoming = incoming, outgoing = outgoing)
    }

    suspend fun performFriendAction(targetUid: String, action: String) {
        postAuthenticatedJson("/friend-action", "Thao tác bạn bè thất bại") { token ->
            buildJsonObject {
                put("idToken", token)
                put("targetUid", targetUid)
                put("action", action) // accept, send, remove, block
            }
        }
    }

    suspend fun getConversations(): Pair<List<Conversation>, Map<String, FriendUser>> {
        val data = postAuthenticatedJson("/conversations", "Không tải được hộp thư") { token ->
            buildJsonObject { put("idToken", token) }
        }
        val list = data["conversations"]?.jsonArray?.mapNotNull { el ->
            val obj = el.jsonObject
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            Conversation(
                id = id,
                peer = obj["peer"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                updatedAt = obj["updated_at"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                unreadCount = obj["unread_count"]?.jsonPrimitive?.intOrNull ?: 0,
                isRead = obj["is_read"]?.jsonPrimitive?.booleanOrNull ?: true,
                otherLastReadAt = obj["other_last_read_at"]?.jsonPrimitive?.contentOrNull,
                latest = obj["latest"]?.takeIf { it is JsonObject }?.jsonObject?.let { parseChatMessage(it) }
            )
        }.orEmpty()
        return list to parseUsersMap(data["users"])
    }

    suspend fun getMessages(conversationId: String, cursor: String? = null, limit: Int = 30): Pair<List<ChatMessage>, String?> {
        val data = postAuthenticatedJson("/messages", "Không tải được tin nhắn") { token ->
            buildJsonObject {
                put("idToken", token)
                put("conversationId", conversationId)
                put("limit", limit)
                if (!cursor.isNullOrBlank()) put("cursor", cursor)
            }
        }
        val msgs = data["messages"]?.jsonArray?.mapNotNull {
            runCatching { parseChatMessage(it.jsonObject) }.getOrNull()
        }.orEmpty()
        return msgs to data["nextCursor"]?.jsonPrimitive?.contentOrNull
    }

    suspend fun sendMessage(receiverUid: String, text: String, momentUid: String? = null): String? {
        val data = postAuthenticatedJson("/send-message", "Gửi tin nhắn thất bại") { token ->
            buildJsonObject {
                put("idToken", token)
                put("receiverUid", receiverUid)
                put("text", text)
                if (!momentUid.isNullOrBlank()) put("momentUid", momentUid)
            }
        }
        return data["conversationId"]?.jsonPrimitive?.contentOrNull
    }

    suspend fun markConversationRead(conversationId: String) {
        runCatching {
            postAuthenticatedJson("/mark-read", "Không đánh dấu đã đọc") { token ->
                buildJsonObject {
                    put("idToken", token)
                    put("conversationId", conversationId)
                }
            }
        }
    }

    suspend fun deleteMessage(conversationId: String, messageId: String) {
        postAuthenticatedJson("/delete-message", "Gỡ tin nhắn thất bại") { token ->
            buildJsonObject {
                put("idToken", token)
                put("conversationId", conversationId)
                put("messageId", messageId)
            }
        }
    }

    suspend fun reactToMessage(conversationId: String, messageId: String, emoji: String, remove: Boolean) {
        postAuthenticatedJson("/react-message", "Thả cảm xúc tin nhắn thất bại") { token ->
            buildJsonObject {
                put("idToken", token)
                put("conversationId", conversationId)
                put("messageId", messageId)
                put("emoji", emoji)
                put("remove", remove)
            }
        }
    }

    suspend fun reportMessage(conversationId: String, messageId: String) {
        postAuthenticatedJson("/report-message", "Báo cáo tin nhắn thất bại") { token ->
            buildJsonObject {
                put("idToken", token)
                put("conversationId", conversationId)
                put("messageId", messageId)
            }
        }
    }

    // =========================================================================
    // 6. SUPPORTER PERKS STATUS (LEGITIMATE SERVER VERIFICATION)
    // =========================================================================

    suspend fun syncDonateStatus() {
        val uid = sessionManager.currentSession()?.localId ?: return
        runCatching {
            val data = postAuthenticatedJson("/donate/status", "Không tải được trạng thái Supporter") { token ->
                buildJsonObject { put("idToken", token) }
            }
            val total = data["total"]?.jsonPrimitive?.longOrNull ?: 0L
            val tiers = data["tiers"]?.jsonArray?.mapNotNull { el ->
                val obj = el.jsonObject
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                PerkTier(
                    id = id,
                    minTotal = obj["minTotal"]?.jsonPrimitive?.longOrNull ?: 10_000L,
                    name = obj["name"]?.jsonPrimitive?.contentOrNull ?: id
                )
            }
            sessionManager.updateSupporterStatus(uid, total, tiers)
        }
    }

    suspend fun getDonateMe(): DonateMeResponse {
        val data = postAuthenticatedJson("/donate/me", "Không tải được thông tin ủng hộ") { token ->
            buildJsonObject { put("idToken", token) }
        }
        val donor = data["donor"]?.takeIf { it is JsonObject }?.jsonObject
        val account = data["account"]?.takeIf { it is JsonObject }?.jsonObject
        val total = donor?.get("total")?.jsonPrimitive?.longOrNull ?: 0L
        sessionManager.currentSession()?.localId?.let { uid ->
            sessionManager.updateSupporterStatus(uid, total, null)
        }
        return DonateMeResponse(
            qrUrl = data["qrUrl"]?.jsonPrimitive?.contentOrNull,
            transferContent = data["transferContent"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            bankName = account?.get("bank")?.jsonPrimitive?.contentOrNull.orEmpty(),
            accountNumber = account?.get("accountNumber")?.jsonPrimitive?.contentOrNull.orEmpty(),
            accountName = account?.get("accountName")?.jsonPrimitive?.contentOrNull.orEmpty(),
            donorTotal = total,
            donorCount = donor?.get("count")?.jsonPrimitive?.intOrNull ?: 0,
            donorDisplayName = donor?.get("displayName")?.jsonPrimitive?.contentOrNull.orEmpty(),
            donorCode = donor?.get("code")?.jsonPrimitive?.contentOrNull.orEmpty()
        )
    }

    suspend fun setDonateDisplayName(displayName: String) {
        postAuthenticatedJson("/donate/name", "Không lưu được tên hiển thị") { token ->
            buildJsonObject {
                put("idToken", token)
                put("displayName", displayName.trim())
            }
        }
    }

    suspend fun getDonateList(): List<DonorLeaderboardItem> {
        val data = postAuthenticatedJson("/donate/list", "Không tải được danh sách ủng hộ") { token ->
            buildJsonObject { put("idToken", token) }
        }
        return data["donors"]?.jsonArray?.map { el ->
            val obj = el.jsonObject
            DonorLeaderboardItem(
                displayName = obj["displayName"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "Ẩn danh",
                isMe = obj["isMe"]?.jsonPrimitive?.booleanOrNull ?: false
            )
        }.orEmpty()
    }

    // =========================================================================
    // HELPERS & SERIALIZATION MAPPERS
    // =========================================================================

    private fun buildPublishJson(
        idToken: String,
        objectPath: String,
        caption: String,
        overlay: CaptionOverlayConfig,
        recipients: List<String>,
        streakDayYyyyMmDd: Int?,
        perks: List<String>
    ): JsonObject = buildJsonObject {
        put("idToken", idToken)
        put("objectPath", objectPath)
        put("caption", caption)
        if (overlay.noteType.isNotBlank() && overlay.noteType != "standard") {
            put("noteType", overlay.noteType)
        }
        if (!overlay.icon.isNullOrBlank()) {
            put("icon", overlay.icon)
        }
        if (!overlay.textColor.isNullOrBlank()) {
            put("textColor", overlay.textColor)
        }
        if (overlay.colors.isNotEmpty()) {
            put("colors", buildJsonArray { overlay.colors.forEach { add(JsonPrimitive(it)) } })
        }
        if (overlay.payload != null) {
            put("overlayPayload", overlay.payload)
        }
        if (recipients.isNotEmpty()) {
            put("recipients", buildJsonArray { recipients.forEach { add(JsonPrimitive(it)) } })
        }
        if (streakDayYyyyMmDd != null) {
            put("streakDay", streakDayYyyyMmDd)
        }
        if (perks.isNotEmpty()) {
            put("perks", buildJsonArray { perks.forEach { add(JsonPrimitive(it)) } })
        }
    }

    private fun appendOverlayFormParts(builder: MultipartBody.Builder, overlay: CaptionOverlayConfig) {
        if (overlay.noteType.isNotBlank() && overlay.noteType != "standard") {
            builder.addFormDataPart("noteType", overlay.noteType)
        }
        if (!overlay.icon.isNullOrBlank()) {
            builder.addFormDataPart("icon", overlay.icon)
        }
        if (!overlay.textColor.isNullOrBlank()) {
            builder.addFormDataPart("textColor", overlay.textColor)
        }
        if (overlay.colors.isNotEmpty()) {
            builder.addFormDataPart("colors", json.encodeToString(overlay.colors))
        }
        if (overlay.payload != null) {
            builder.addFormDataPart("overlayPayload", overlay.payload.toString())
        }
    }

    private fun parseMoment(obj: JsonObject, forcePrivate: Boolean = false): Moment {
        val id = obj["id"]?.jsonPrimitive?.contentOrNull
            ?: obj["canonical_uid"]?.jsonPrimitive?.contentOrNull
            ?: "m_${System.currentTimeMillis()}"
        val isPriv = forcePrivate || (obj["private"]?.jsonPrimitive?.booleanOrNull == true)
        val rawThumb = obj["thumbnail_url"]?.jsonPrimitive?.contentOrNull
        val rawVideo = obj["video_url"]?.jsonPrimitive?.contentOrNull

        fun resolveUrl(u: String?): String? {
            if (u.isNullOrBlank()) return null
            return if (isPriv && u.startsWith("/")) "$API_ORIGIN$u" else u
        }

        val caption = obj["caption"]?.jsonPrimitive?.contentOrNull.orEmpty()
        var overlayDisplay = OverlayDisplay(text = caption)

        val overlaysArr = obj["overlays"]?.takeIf { it is JsonArray }?.jsonArray
        val firstCaptionOverlay = overlaysArr?.firstOrNull { el ->
            el is JsonObject && el.jsonObject["overlay_type"]?.jsonPrimitive?.contentOrNull == "caption"
        }?.jsonObject

        if (firstCaptionOverlay != null) {
            val data = firstCaptionOverlay["data"]?.takeIf { it is JsonObject }?.jsonObject
            if (data != null) {
                val text = data["text"]?.jsonPrimitive?.contentOrNull ?: caption
                val textColor = data["text_color"]?.jsonPrimitive?.contentOrNull ?: "#FFFFFF"
                val bgColors = data["background"]?.takeIf { it is JsonObject }?.jsonObject
                    ?.get("colors")?.takeIf { it is JsonArray }?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()
                val icon = data["icon"]?.takeIf { it is JsonObject }?.jsonObject
                    ?.get("data")?.jsonPrimitive?.contentOrNull
                overlayDisplay = OverlayDisplay(
                    text = text,
                    icon = icon,
                    textColor = textColor,
                    backgroundColors = bgColors
                )
            }
        }

        return Moment(
            id = id,
            user = obj["user"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            date = obj["date"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            thumbnailUrl = resolveUrl(rawThumb),
            videoUrl = resolveUrl(rawVideo),
            caption = caption,
            overlay = overlayDisplay,
            isPrivate = isPriv,
            cursor = obj["cursor"]?.jsonPrimitive?.contentOrNull
        )
    }

    private fun parseFriendUser(obj: JsonObject): FriendUser {
        return FriendUser(
            uid = obj["uid"]?.jsonPrimitive?.contentOrNull
                ?: obj["localId"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            firstName = obj["first_name"]?.jsonPrimitive?.contentOrNull
                ?: obj["firstName"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            lastName = obj["last_name"]?.jsonPrimitive?.contentOrNull
                ?: obj["lastName"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            username = obj["username"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            profilePictureUrl = obj["profile_picture_url"]?.jsonPrimitive?.contentOrNull
                ?: obj["photoUrl"]?.jsonPrimitive?.contentOrNull.orEmpty()
        )
    }

    private fun parseUsersMap(element: JsonElement?): Map<String, FriendUser> {
        if (element !is JsonObject) return emptyMap()
        return buildMap {
            element.entries.forEach { (uid, value) ->
                if (value is JsonObject) {
                    val parsed = parseFriendUser(value)
                    put(uid, if (parsed.uid.isBlank()) parsed.copy(uid = uid) else parsed)
                }
            }
        }
    }

    private fun parseChatMessage(obj: JsonObject): ChatMessage {
        return ChatMessage(
            id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "msg_${System.currentTimeMillis()}",
            sender = obj["sender"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            body = obj["body"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            createdAt = obj["created_at"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            thumbnailUrl = obj["thumbnail_url"]?.jsonPrimitive?.contentOrNull,
            replyMoment = obj["reply_moment"]?.jsonPrimitive?.contentOrNull,
            clientToken = obj["client_token"]?.jsonPrimitive?.contentOrNull,
            reactions = obj["reactions"]?.takeIf { it is JsonArray }?.jsonArray?.mapNotNull { el ->
                val ro = el.jsonObject
                MessageReaction(
                    sender = ro["sender"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    emoji = ro["emoji"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    createdAt = ro["created_at"]?.jsonPrimitive?.contentOrNull.orEmpty()
                )
            }.orEmpty()
        )
    }

    private fun parseStreakAnchor(element: JsonElement?): StreakAnchor? {
        if (element !is JsonObject) return null
        val count = element["count"]?.jsonPrimitive?.intOrNull ?: return null
        val ymd = element["last_updated_yyyymmdd"]?.jsonPrimitive?.intOrNull ?: return null
        return if (count >= 0 && ymd > 20000101) StreakAnchor(count, ymd) else null
    }

    private fun parsePresetObject(fallbackId: String, obj: JsonObject): CaptionPreset? {
        val label = obj["text"]?.jsonPrimitive?.contentOrNull
            ?: obj["label"]?.jsonPrimitive?.contentOrNull
            ?: obj["name"]?.jsonPrimitive?.contentOrNull ?: return null
        return CaptionPreset(
            id = obj["id"]?.jsonPrimitive?.contentOrNull ?: fallbackId,
            label = label,
            icon = obj["icon"]?.jsonPrimitive?.contentOrNull,
            textColor = obj["textColor"]?.jsonPrimitive?.contentOrNull
                ?: obj["text_color"]?.jsonPrimitive?.contentOrNull ?: "#FFFFFF",
            colors = obj["colors"]?.takeIf { it is JsonArray }?.jsonArray
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
            payload = obj["payload"]
        )
    }

    private fun parseApiError(statusCode: Int, rawBody: String, fallback: String): LocketApiException {
        var serverMsg = ""
        var errCode: String? = null
        runCatching {
            val obj = json.parseToJsonElement(rawBody).jsonObject
            val errEl = obj["error"]
            if (errEl is JsonObject) {
                serverMsg = errEl["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
                errCode = errEl["code"]?.jsonPrimitive?.contentOrNull
            } else if (errEl is JsonPrimitive) {
                serverMsg = errEl.contentOrNull.orEmpty()
            }
            if (serverMsg.isBlank()) {
                serverMsg = obj["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
            }
        }

        val tokenKey = serverMsg.substringBefore(" ").removeSuffix(":")
        val friendly = when {
            errCode != null && errCode in DIRECT_PASS_THROUGH_ERROR_CODES && serverMsg.isNotBlank() -> {
                AUTH_ERROR_MAP[tokenKey] ?: serverMsg
            }
            AUTH_ERROR_MAP.containsKey(tokenKey) -> AUTH_ERROR_MAP.getValue(tokenKey)
            statusCode == 401 -> "Phiên đăng nhập đã hết hạn, vui lòng đăng nhập lại"
            statusCode == 413 -> serverMsg.ifBlank { "File quá lớn" }
            statusCode == 429 -> serverMsg.ifBlank { "Thao tác quá nhanh, thử lại sau ít phút" }
            serverMsg.isNotBlank() -> "$fallback: $serverMsg"
            else -> "$fallback (lỗi $statusCode)"
        }
        return LocketApiException(
            message = friendly,
            statusCode = statusCode,
            errorCode = errCode
        )
    }

    companion object {
        const val API_ORIGIN = "https://locket.elingo.app"
        const val BASE_URL = "$API_ORIGIN/locket"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private val AUTH_ERROR_MAP = mapOf(
            "INVALID_LOGIN_CREDENTIALS" to "Email hoặc mật khẩu không đúng",
            "INVALID_PASSWORD" to "Mật khẩu không đúng",
            "EMAIL_NOT_FOUND" to "Email này chưa có tài khoản Locket",
            "INVALID_EMAIL" to "Email không hợp lệ",
            "USER_DISABLED" to "Tài khoản đã bị khoá",
            "TOO_MANY_ATTEMPTS_TRY_LATER" to "Đăng nhập sai quá nhiều lần, thử lại sau"
        )

        private val DIRECT_PASS_THROUGH_ERROR_CODES = AUTH_ERROR_MAP.keys + setOf(
            "MISSING_PASSWORD",
            "LOGIN_LOCKED",
            "LOGIN_KEY_STALE",
            "LOGIN_DATA_INVALID",
            "MOMENT_GONE",
            "SUPPORTER_REQUIRED"
        )
    }
}

