package com.wansaw.opensms.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.sqlite.execSQL
import kotlinx.coroutines.flow.Flow

object JobState {
    const val ACCEPTED = "accepted"
    const val SUBMITTING = "submitting"
    const val SENT = "sent"
    const val DELIVERED = "delivered"
    const val FAILED = "failed"
    const val UNDELIVERED = "undelivered"
    const val UNKNOWN = "unknown"
    const val EXPIRED = "expired"

    fun isActive(s: String) = s == ACCEPTED || s == SUBMITTING
}

@Entity(tableName = "jobs")
data class JobEntity(
    @PrimaryKey val jobId: String,
    val attemptId: String,
    val recipient: String,
    /** Message text. Cleared as soon as the job reaches a final state. */
    val text: String,
    val simRequested: String,
    val expiresAt: Long,
    val segmentsEstimated: Int,
    val state: String,
    /** 1 or 2 once a SIM has been chosen. */
    val simUsed: Int? = null,
    /** Bit 1 = slot 1 tried, bit 2 = slot 2 tried. */
    val triedSims: Int = 0,
    /** 1 for the first attempt, 2 for the first retry, ... */
    val attemptNo: Int = 1,
    /** A retry is not started before this time (epoch ms). */
    val notBefore: Long = 0,
    val partsTotal: Int = 0,
    val partsOk: Int = 0,
    val partsFailed: Int = 0,
    val partsDelivered: Int = 0,
    val deliveryFailed: Boolean = false,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val createdAt: Long,
    val submittedAt: Long? = null,
    val finishedAt: Long? = null,
)

@Entity(tableName = "outbox")
data class EventEntity(
    @PrimaryKey val eventId: String,
    val jobId: String?,
    val json: String,
    val createdAt: Long,
)

@Dao
abstract class JobDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(job: JobEntity): Long

    @Update
    abstract suspend fun update(job: JobEntity)

    @Query("SELECT * FROM jobs WHERE jobId = :id")
    abstract suspend fun get(id: String): JobEntity?

    @Query("SELECT * FROM jobs WHERE state = 'accepted' AND notBefore <= :now ORDER BY createdAt ASC LIMIT 1")
    abstract suspend fun nextAccepted(now: Long): JobEntity?

    @Query("SELECT * FROM jobs WHERE state IN ('accepted','submitting')")
    abstract suspend fun active(): List<JobEntity>

    @Query("SELECT * FROM jobs ORDER BY createdAt DESC LIMIT 300")
    abstract fun history(): Flow<List<JobEntity>>

    @Query("SELECT COUNT(*) FROM jobs WHERE createdAt >= :since AND state IN ('sent','delivered')")
    abstract fun sentSince(since: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM jobs WHERE createdAt >= :since AND state IN ('failed','expired','undelivered','unknown')")
    abstract fun failedSince(since: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM jobs WHERE state IN ('accepted','submitting')")
    abstract fun pending(): Flow<Int>

    /** Messages used today (optionally for one SIM, 0 = all); failed/expired jobs do not count. One message = one job, however many SMS parts. */
    @Query(
        "SELECT COUNT(*) FROM jobs " +
            "WHERE createdAt >= :since AND state NOT IN ('failed','expired') AND (:slot = 0 OR simUsed = :slot)",
    )
    abstract suspend fun messagesSince(since: Long, slot: Int): Int

    @Query("DELETE FROM jobs WHERE createdAt < :before AND state NOT IN ('accepted','submitting')")
    abstract suspend fun prune(before: Long)

    @Query("DELETE FROM jobs")
    abstract suspend fun clear()

    /** Records the outcome of one multipart part of the *current* attempt. Returns the new job or null if stale. */
    @Transaction
    open suspend fun recordPart(jobId: String, attemptId: String, ok: Boolean, errorCode: String?, errorMessage: String?): JobEntity? {
        val j = get(jobId) ?: return null
        if (j.attemptId != attemptId || !JobState.isActive(j.state) && j.state != JobState.SENT) return null
        val n = j.copy(
            partsOk = j.partsOk + if (ok) 1 else 0,
            partsFailed = j.partsFailed + if (ok) 0 else 1,
            errorCode = if (ok) j.errorCode else (j.errorCode ?: errorCode),
            errorMessage = if (ok) j.errorMessage else (j.errorMessage ?: errorMessage),
        )
        update(n)
        return n
    }

    @Transaction
    open suspend fun recordDelivery(jobId: String, attemptId: String, ok: Boolean): JobEntity? {
        val j = get(jobId) ?: return null
        if (j.attemptId != attemptId) return null
        val n = j.copy(partsDelivered = j.partsDelivered + if (ok) 1 else 0, deliveryFailed = j.deliveryFailed || !ok)
        update(n)
        return n
    }
}

@Dao
abstract class OutboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(e: EventEntity)

    @Query("SELECT * FROM outbox ORDER BY createdAt ASC, eventId ASC LIMIT :limit")
    abstract suspend fun oldest(limit: Int): List<EventEntity>

    @Query("SELECT DISTINCT jobId FROM outbox WHERE jobId IS NOT NULL")
    abstract suspend fun pendingJobIds(): List<String>

    @Query("DELETE FROM outbox WHERE eventId IN (:ids)")
    abstract suspend fun delete(ids: List<String>)

    /** Reverse-SMS events have no job; unacknowledged ones older than [before] are dropped. */
    @Query("DELETE FROM outbox WHERE jobId IS NULL AND createdAt < :before")
    abstract suspend fun pruneInbound(before: Long)

    @Query("DELETE FROM outbox")
    abstract suspend fun clear()
}

@Database(entities = [JobEntity::class, EventEntity::class], version = 2, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun jobs(): JobDao
    abstract fun outbox(): OutboxDao
}

/** v1 -> v2: retry bookkeeping. */
val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
    override fun migrate(connection: androidx.sqlite.SQLiteConnection) {
        connection.execSQL("ALTER TABLE jobs ADD COLUMN attemptNo INTEGER NOT NULL DEFAULT 1")
        connection.execSQL("ALTER TABLE jobs ADD COLUMN notBefore INTEGER NOT NULL DEFAULT 0")
    }
}
