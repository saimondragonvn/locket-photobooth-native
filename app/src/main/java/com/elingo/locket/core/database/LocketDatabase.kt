package com.elingo.locket.core.database

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "outbox_moments")
data class OutboxEntity(
    @PrimaryKey val id: String,
    val uid: String,
    val createdAt: Long,
    val mediaPath: String,
    val thumbPath: String? = null,
    val isVideo: Boolean,
    val isPrivate: Boolean,
    val filterId: String = "none",
    val caption: String = "",
    val overlayJson: String = "{}",
    val recipientsJson: String = "[]",
    val perksJson: String = "[]",
    val uploadId: String? = null,
    val jobId: String? = null,
    val prepared: Boolean = false,
    val status: String = STATUS_QUEUED, // queued, compressing, uploading, publishing, failed
    val progressPercent: Int = 0,
    val statusMessage: String = "Đang chờ...",
    val errorMessage: String? = null
) {
    companion object {
        const val STATUS_QUEUED = "queued"
        const val STATUS_COMPRESSING = "compressing"
        const val STATUS_UPLOADING = "uploading"
        const val STATUS_PUBLISHING = "publishing"
        const val STATUS_FAILED = "failed"
    }
}

@Dao
interface OutboxDao {
    @Query("SELECT * FROM outbox_moments WHERE uid = :uid ORDER BY createdAt DESC")
    fun observeForUser(uid: String): Flow<List<OutboxEntity>>

    @Query("SELECT * FROM outbox_moments WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): OutboxEntity?

    @Query("SELECT * FROM outbox_moments WHERE status != 'failed' ORDER BY createdAt ASC")
    suspend fun getPendingItems(): List<OutboxEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: OutboxEntity)

    @Query(
        """
        UPDATE outbox_moments 
        SET status = :status, progressPercent = :percent, statusMessage = :message, errorMessage = :error
        WHERE id = :id
        """
    )
    suspend fun updateProgress(
        id: String,
        status: String,
        percent: Int,
        message: String,
        error: String? = null
    )

    @Query("UPDATE outbox_moments SET mediaPath = :mediaPath, prepared = 1, filterId = 'none' WHERE id = :id")
    suspend fun markPrepared(id: String, mediaPath: String)

    @Query("UPDATE outbox_moments SET uploadId = :uploadId WHERE id = :id")
    suspend fun updateUploadId(id: String, uploadId: String)

    @Query("UPDATE outbox_moments SET jobId = :jobId WHERE id = :id")
    suspend fun updateJobId(id: String, jobId: String?)

    @Query("DELETE FROM outbox_moments WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM outbox_moments WHERE uid = :uid")
    suspend fun deleteAllForUser(uid: String)

    @Query("SELECT COUNT(*) FROM outbox_moments")
    suspend fun countAllItems(): Int

    @Query("DELETE FROM outbox_moments")
    suspend fun deleteAll()
}

@Database(
    entities = [OutboxEntity::class],
    version = 1,
    exportSchema = false
)
abstract class LocketDatabase : RoomDatabase() {
    abstract fun outboxDao(): OutboxDao

    companion object {
        @Volatile
        private var INSTANCE: LocketDatabase? = null

        fun getInstance(context: Context): LocketDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    LocketDatabase::class.java,
                    "locket_outbox.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}

