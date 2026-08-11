package com.trippulse.app.data.local

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Local-first persistence (docs/spec/06, 81). Room + SQLite is the durable
 * source of truth on the device: a crash, reboot or network transition must
 * never lose a pending event. The cloud is a transport, not the store.
 */

// ---------------------------------------------------------------------------
// Entities
// ---------------------------------------------------------------------------

@Entity(tableName = "active_trip")
data class ActiveTripEntity(
    @PrimaryKey val tripId: String,
    val secret: String,
    val accessKey: String,
    val originName: String,
    val originLat: Double,
    val originLng: Double,
    val destName: String,
    val destLat: Double,
    val destLng: Double,
    val emergencyName: String?,
    val emergencyPhone: String?,
    val createdAtMs: Long,
    val plannedDepartureMs: Long?,
    val startedAtMs: Long?,
    val completedAtMs: Long?,
    val expiresAtMs: Long?,
    val status: String,               // CREATED | ACTIVE | COMPLETED | EXPIRED
    val cloudEnabled: Boolean,
    val metaSynced: Boolean,
    val totalRouteDistanceM: Double,
    val ownerUid: String?
)

@Entity(tableName = "event_queue")
data class EventEntity(
    @PrimaryKey val eventId: String,
    val tripId: String,
    val type: String,
    val eventTimeMs: Long,
    val receivedAtMs: Long,
    val lat: Double?,
    val lng: Double?,
    val accuracyM: Double?,
    val source: String,
    val payloadJson: String,
    val schemaVersion: Int,
    val priority: Int,
    val syncStatus: String,
    val retryCount: Int,
    val lastAttemptAtMs: Long?,
    val sensitive: Boolean
)

@Entity(tableName = "location_buffer")
data class LocationSampleEntity(
    @PrimaryKey(autoGenerate = true) val autoId: Long = 0,
    val tripId: String,
    val tMs: Long,
    val lat: Double,
    val lng: Double,
    val accuracyM: Double,
    val speedMps: Double?,
    val bearing: Double?,
    val syncStatus: String
)

@Entity(tableName = "trip_state")
data class TripStateEntity(
    @PrimaryKey val tripId: String,
    val journey: String,
    val connectivity: String,
    val lat: Double?,
    val lng: Double?,
    val accuracyM: Double?,
    val speedKmh: Double?,
    val bearing: Double?,
    val lastLocationAtMs: Long?,
    val batteryPct: Int?,
    val distanceCoveredM: Double,
    val distanceRemainingM: Double,
    val progressPct: Double,
    val etaLowMs: Long?,
    val etaHighMs: Long?,
    val etaLikelyMs: Long?,
    val etaMode: String,
    val etaBreakdownJson: String?,
    val etaConfidence: String?,
    val drivingSinceMs: Long?,
    val stopStartedAtMs: Long?,
    val lastBreakEndAtMs: Long?,
    val waterAtMs: Long?,
    val foodAtMs: Long?,
    val toiletAtMs: Long?,
    val restAtMs: Long?,
    val fuelAtMs: Long?,
    val sosActive: Boolean,
    val sosAtMs: Long?,
    val overnightType: String?,
    val overnightSinceMs: Long?,
    val deviationActive: Boolean,
    // pending driver-interaction flags
    val checkpointDue: Boolean,
    val checkpointStopStartMs: Long?,
    val checkpointStopEndMs: Long?,
    val checkpointStopDurationS: Long?,
    val longStopPromptDue: Boolean,
    val possibleIncidentDue: Boolean,
    val updatedAtMs: Long
)

@Entity(tableName = "break_records")
data class BreakRecordEntity(
    @PrimaryKey val breakId: String,
    val tripId: String,
    val startMs: Long,
    val endMs: Long?,
    val durationS: Long?,
    val lat: Double?,
    val lng: Double?,
    val water: Boolean,
    val food: Boolean,
    val toilet: Boolean,
    val rest: Boolean,
    val fuel: Boolean,
    val charge: Boolean,
    val other: Boolean,
    val confirmationSource: String    // DRIVER_CONFIRMATION | INFERRED | MANUAL
)

@Entity(tableName = "viewer_trip")
data class ViewerTripEntity(
    @PrimaryKey val accessKey: String,
    val tripId: String,
    val label: String,
    val joinedAtMs: Long,
    val lastOpenedAtMs: Long,
    val expired: Boolean
)

// ---------------------------------------------------------------------------
// DAOs
// ---------------------------------------------------------------------------

@Dao
interface TripDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(trip: ActiveTripEntity)

    @Update
    suspend fun update(trip: ActiveTripEntity)

    @Query("SELECT * FROM active_trip WHERE tripId = :tripId")
    suspend fun byId(tripId: String): ActiveTripEntity?

    @Query("SELECT * FROM active_trip WHERE status IN ('CREATED','ACTIVE') ORDER BY createdAtMs DESC LIMIT 1")
    suspend fun activeTrip(): ActiveTripEntity?

    @Query("SELECT * FROM active_trip WHERE status IN ('CREATED','ACTIVE') ORDER BY createdAtMs DESC LIMIT 1")
    fun activeTripFlow(): Flow<ActiveTripEntity?>

    @Query("SELECT * FROM active_trip ORDER BY createdAtMs DESC")
    fun allFlow(): Flow<List<ActiveTripEntity>>

    @Query("UPDATE active_trip SET metaSynced = :synced WHERE tripId = :tripId")
    suspend fun setMetaSynced(tripId: String, synced: Boolean)
}

@Dao
interface EventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(e: EventEntity): Long

    @Query("SELECT * FROM event_queue WHERE tripId = :tripId ORDER BY eventTimeMs ASC")
    fun eventsFlow(tripId: String): Flow<List<EventEntity>>

    @Query("SELECT * FROM event_queue WHERE tripId = :tripId ORDER BY eventTimeMs ASC")
    suspend fun allForTrip(tripId: String): List<EventEntity>

    @Query(
        "SELECT * FROM event_queue WHERE syncStatus IN ('PENDING','FAILED_RETRYABLE') " +
            "ORDER BY priority ASC, eventTimeMs ASC LIMIT :limit"
    )
    suspend fun pendingByPriority(limit: Int): List<EventEntity>

    @Query("UPDATE event_queue SET syncStatus = :status, lastAttemptAtMs = :ts WHERE eventId = :id")
    suspend fun setStatus(id: String, status: String, ts: Long)

    @Query("UPDATE event_queue SET syncStatus = :status, retryCount = retryCount + 1, lastAttemptAtMs = :ts WHERE eventId = :id")
    suspend fun setStatusRetry(id: String, status: String, ts: Long)

    @Query("UPDATE event_queue SET syncStatus = 'PENDING' WHERE syncStatus = 'UPLOADING'")
    suspend fun resetUploading()

    @Query("SELECT COUNT(*) FROM event_queue WHERE syncStatus IN ('PENDING','FAILED_RETRYABLE','UPLOADING')")
    fun pendingCountFlow(): Flow<Int>
}

@Dao
interface LocationDao {
    @Insert
    suspend fun insert(s: LocationSampleEntity)

    @Query("SELECT * FROM location_buffer WHERE tripId = :tripId AND syncStatus = 'PENDING' ORDER BY tMs ASC LIMIT :limit")
    suspend fun pendingBatch(tripId: String, limit: Int): List<LocationSampleEntity>

    @Query("SELECT * FROM location_buffer WHERE tripId = :tripId ORDER BY tMs ASC")
    suspend fun allForTrip(tripId: String): List<LocationSampleEntity>

    @Query("UPDATE location_buffer SET syncStatus = 'ACKED' WHERE autoId IN (:ids)")
    suspend fun markSynced(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM location_buffer WHERE tripId = :tripId AND syncStatus = 'PENDING'")
    suspend fun pendingCount(tripId: String): Int

    // compact: drop the oldest already-acked samples beyond a cap to keep the
    // buffer bounded on very long trips (history detail is preserved in events).
    @Query("DELETE FROM location_buffer WHERE tripId = :tripId AND syncStatus = 'ACKED' AND autoId NOT IN (SELECT autoId FROM location_buffer WHERE tripId = :tripId ORDER BY tMs DESC LIMIT :keep)")
    suspend fun compactAcked(tripId: String, keep: Int)
}

@Dao
interface StateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(s: TripStateEntity)

    @Query("SELECT * FROM trip_state WHERE tripId = :tripId")
    suspend fun byId(tripId: String): TripStateEntity?

    @Query("SELECT * FROM trip_state WHERE tripId = :tripId")
    fun flow(tripId: String): Flow<TripStateEntity?>
}

@Dao
interface BreakDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(b: BreakRecordEntity)

    @Query("SELECT * FROM break_records WHERE tripId = :tripId AND endMs IS NULL ORDER BY startMs DESC LIMIT 1")
    suspend fun openBreak(tripId: String): BreakRecordEntity?

    @Query("SELECT * FROM break_records WHERE tripId = :tripId ORDER BY startMs ASC")
    suspend fun allForTrip(tripId: String): List<BreakRecordEntity>
}

@Dao
interface ViewerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(v: ViewerTripEntity)

    @Query("SELECT * FROM viewer_trip ORDER BY lastOpenedAtMs DESC")
    fun allFlow(): Flow<List<ViewerTripEntity>>

    @Query("SELECT * FROM viewer_trip WHERE accessKey = :key")
    suspend fun byKey(key: String): ViewerTripEntity?

    @Query("UPDATE viewer_trip SET expired = 1 WHERE accessKey = :key")
    suspend fun markExpired(key: String)

    @Query("UPDATE viewer_trip SET lastOpenedAtMs = :ts WHERE accessKey = :key")
    suspend fun touch(key: String, ts: Long)
}

// ---------------------------------------------------------------------------
// Database
// ---------------------------------------------------------------------------

@Database(
    entities = [
        ActiveTripEntity::class,
        EventEntity::class,
        LocationSampleEntity::class,
        TripStateEntity::class,
        BreakRecordEntity::class,
        ViewerTripEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class TripPulseDb : RoomDatabase() {
    abstract fun tripDao(): TripDao
    abstract fun eventDao(): EventDao
    abstract fun locationDao(): LocationDao
    abstract fun stateDao(): StateDao
    abstract fun breakDao(): BreakDao
    abstract fun viewerDao(): ViewerDao

    companion object {
        @Volatile private var INSTANCE: TripPulseDb? = null

        fun get(context: Context): TripPulseDb =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    TripPulseDb::class.java,
                    "trippulse.db"
                ).fallbackToDestructiveMigration().build().also { INSTANCE = it }
            }
    }
}
