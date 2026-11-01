package com.elingo.locket

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.elingo.locket.core.auth.SessionManager
import com.elingo.locket.core.database.LocketDatabase
import com.elingo.locket.core.network.LocketApiClient
import com.elingo.locket.media.uploader.OutboxManager
import com.elingo.locket.media.uploader.ThreeTierMediaUploader

class AppContainer(app: Application) {
    val sessionManager: SessionManager = SessionManager(app)
    val database: LocketDatabase = LocketDatabase.getInstance(app)
    val apiClient: LocketApiClient = LocketApiClient(sessionManager)
    val mediaUploader: ThreeTierMediaUploader = ThreeTierMediaUploader(apiClient)
    val outboxManager: OutboxManager = OutboxManager(
        appContext = app,
        outboxDao = database.outboxDao(),
        sessionManager = sessionManager
    )
}

class LocketApplication : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Resume any pending Outbox uploads immediately on app launch
        container.outboxManager.triggerWorker()
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(VideoFrameDecoder.Factory())
            }
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("coil_media_cache"))
                    .maxSizeBytes(150L * 1024L * 1024L) // 150 MB media thumbnail cache
                    .build()
            }
            .crossfade(true)
            .build()
    }
}

