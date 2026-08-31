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
    val ownerUid: String?,
    // Mode of transport drives the app's rule system: what to ask at breaks
    // (refuelling only for private vehicles), what counts as attention-worthy,
    // and whether fuel efficiency applies.
    val transportMode: String = "CAR",   // CAR | BIKE | CAB | BUS | TRAIN | FLIGHT
    val fuelType: String? = null,        // private vehicles: PETROL | DIESEL | ELECTRIC
    // Hybrid journeys: the leg the traveller is on right now. Single-mode
    // journeys always sit on leg 0, so there is one code path, not two.
    val activeLegIndex: Int = 0,
    // When arrival was DETECTED. The journey stays live until the traveller
    // ends it themselves — this timestamp only drives the "shall I close it?"
    // prompt and the post-completion expiry window.
    val arrivedAtMs: Long? = null,
    /** True only once the traveller explicitly ended the journey. */
    val endedByOwner: Boolean = false,
    /**
     * When the device stopped reporting in a way worth remembering.
     *
     * Set from the shutdown broadcast, or by the keeper when silence outlasts
     * every innocent explanation. Its presence is what exempts a journey from
     * the 72-hour sweep: once a journey has gone dark it is no longer just a
     * record, it is the thing a family would hand to the police.
     */
    val wentDarkAtMs: Long? = null,
    /** [com.trippulse.app.domain.DarkReason], as a name. */
    val darkReason: String? = null,
    /** Carrier fingerprint when the journey began — see core/DeviceIdentity. */
    val simFingerprint: String? = null,
    /** When a different SIM was first noticed, if one ever was. */
    val simChangedAtMs: Long? = null,
    /**
     * The forensic device dossier as JSON — see core/DeviceDossier.
     *
     * Captured at the journey's start and re-captured on boot, and pushed to
     * the cloud with the rest of the journey, so it is in the family's hands
     * even if the phone never comes back. It holds everything a report can
     * lawfully carry: make and model, Android version, the stable identifiers
     * Android still permits, the carrier, and the public IP at capture.
     */
    val deviceJson: String? = null
)

/**
 * One stage of a journey. See [com.trippulse.app.domain.JourneyLeg] for why
 * multi-leg is the base case rather than a special one.
 */
@Entity(tableName = "trip_legs", primaryKeys = ["tripId", "legIndex"])
data class TripLegEntity(
    val tripId: String,
    val legIndex: Int,
    val mode: String,
    val fromName: String,
    val fromLat: Double,
    val fromLng: Double,
    val toName: String,
    val toLat: Double,
    val toLng: Double,
    val fuelType: String?,
    val plannedDepartureMs: Long?,
    val startedAtMs: Long?,
    val completedAtMs: Long?,
    val bookingRef: String?,
    val seat: String?,
    val boardingPoint: String?,
    /**
     * Per-mode vehicle and booking details, as a JSON object keyed by
     * [com.trippulse.app.domain.DetailKeys].
     *
     * A map rather than columns because the questions differ per mode and will
     * keep growing -- coach numbers, operators, registrations, whatever the
     * next mode needs. Every one of those as its own column would be a
     * migration each time, and a table of mostly-null fields.
     */
    val detailsJson: String? = null
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
    val updatedAtMs: Long,
    /** Arrival was detected; the traveller is being asked to close the journey. */
    val arrivalPromptDue: Boolean = false,
    /** Leg currently being travelled (hybrid journeys). */
    val legIndex: Int = 0
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
    val confirmationSource: String,   // DRIVER_CONFIRMATION | INFERRED | MANUAL
    val tea: Boolean = false,
    val snack: Boolean = false,
    /** BREAKFAST | LUNCH | DINNER | SNACK when food was logged. */
    val mealKind: String? = null
)

/**
 * Owner-only journey expenses (fuel / food / accommodation / other). These are
 * private cost records for the trip owner: stored on-device only, never synced
 * to the cloud, and retained with the trip history until the owner deletes it.
 */
@Entity(tableName = "expenses")
data class ExpenseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tripId: String,
    val type: String,          // FUEL | FOOD | STAY | TICKET | OTHER
    val amount: Double,        // money spent (owner's currency); numbers only
    val quantity: Double?,     // fuel only: litres or kWh refilled
    val unit: String?,         // "L" (petrol/diesel) | "kWh" (electric)
    val note: String?,
    val tMs: Long,
    /** What was bought. Text only — never a number (see core/InputRules). */
    val item: String = ""
)

@Entity(tableName = "saved_places")
data class SavedPlaceEntity(
    @PrimaryKey val name: String,     // "Home", "Office", any custom label
    val lat: Double,
    val lng: Double,
    val createdAtMs: Long
)

/**
 * A journey this device follows.
 *
 * Product rule, absolute: a journey is only ever shown as ended once its
 * traveller ended it. A server we cannot reach, an expired capability or a
 * flat battery are all "we haven't heard for a while" — never "the journey is
 * over". [ended] is therefore set from an explicit completion signal alone,
 * while [unreachableSinceMs] carries the softer, honest state.
 */
@Entity(tableName = "viewer_trip")
data class ViewerTripEntity(
    @PrimaryKey val accessKey: String,
    val tripId: String,
    val label: String,
    val joinedAtMs: Long,
    val lastOpenedAtMs: Long,
    /** Kept for schema continuity; means "the traveller ended this journey". */
    val expired: Boolean,
    /** When the traveller ended it, if they have. */
    val endedAtMs: Long? = null,
    /** Last time this device successfully read anything for this journey. */
    val lastSeenAtMs: Long? = null,
    /** Set while reads are failing; cleared on the next successful read. */
    val unreachableSinceMs: Long? = null
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

    /** Recent journeys, newest first — the source of "somewhere you went lately". */
    @Query("SELECT * FROM active_trip ORDER BY createdAtMs DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<ActiveTripEntity>

    @Query("UPDATE active_trip SET metaSynced = :synced WHERE tripId = :tripId")
    suspend fun setMetaSynced(tripId: String, synced: Boolean)

    @Query("DELETE FROM active_trip WHERE tripId = :tripId")
    suspend fun delete(tripId: String)

    /**
     * Journeys still open long after they began.
     *
     * Nobody travels for three days without closing a journey on purpose; what
     * this actually finds is journeys whose traveller forgot, or whose phone
     * stopped reporting. Either way the row is no longer telling anyone the
     * truth, so it is cleared rather than kept.
     */
    @Query(
        "SELECT * FROM active_trip WHERE status IN ('CREATED','ACTIVE') " +
            "AND COALESCE(startedAtMs, createdAtMs) < :cutoffMs " +
            // A journey that went dark is never swept. The 72-hour rule exists
            // to stop forgotten journeys piling up; a journey whose device
            // stopped reporting is the opposite of forgotten, and its last
            // known position may be the only record of where somebody was.
            "AND wentDarkAtMs IS NULL"
    )
    suspend fun openSince(cutoffMs: Long): List<ActiveTripEntity>
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

    @Query("DELETE FROM event_queue WHERE tripId = :tripId")
    suspend fun deleteForTrip(tripId: String)
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

    @Query("DELETE FROM location_buffer WHERE tripId = :tripId")
    suspend fun deleteForTrip(tripId: String)
}

@Dao
interface StateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(s: TripStateEntity)

    @Query("SELECT * FROM trip_state WHERE tripId = :tripId")
    suspend fun byId(tripId: String): TripStateEntity?

    @Query("SELECT * FROM trip_state WHERE tripId = :tripId")
    fun flow(tripId: String): Flow<TripStateEntity?>

    @Query("DELETE FROM trip_state WHERE tripId = :tripId")
    suspend fun delete(tripId: String)
}

@Dao
interface BreakDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(b: BreakRecordEntity)

    @Query("SELECT * FROM break_records WHERE tripId = :tripId AND endMs IS NULL ORDER BY startMs DESC LIMIT 1")
    suspend fun openBreak(tripId: String): BreakRecordEntity?

    @Query("SELECT * FROM break_records WHERE tripId = :tripId ORDER BY startMs ASC")
    suspend fun allForTrip(tripId: String): List<BreakRecordEntity>

    @Query("DELETE FROM break_records WHERE tripId = :tripId")
    suspend fun deleteForTrip(tripId: String)
}

@Dao
interface ExpenseDao {
    @Insert
    suspend fun insert(e: ExpenseEntity)

    @Query("SELECT * FROM expenses WHERE tripId = :tripId ORDER BY tMs ASC")
    fun flowForTrip(tripId: String): Flow<List<ExpenseEntity>>

    @Query("SELECT * FROM expenses WHERE tripId = :tripId ORDER BY tMs ASC")
    suspend fun allForTrip(tripId: String): List<ExpenseEntity>

    @Query("DELETE FROM expenses WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM expenses WHERE tripId = :tripId")
    suspend fun deleteForTrip(tripId: String)
}

@Dao
interface SavedPlaceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(p: SavedPlaceEntity)

    @Query("DELETE FROM saved_places WHERE name = :name")
    suspend fun delete(name: String)

    @Query("SELECT * FROM saved_places ORDER BY createdAtMs ASC")
    fun allFlow(): Flow<List<SavedPlaceEntity>>

    @Query("SELECT * FROM saved_places ORDER BY createdAtMs ASC")
    suspend fun all(): List<SavedPlaceEntity>
}

@Dao
interface LegDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(leg: TripLegEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(legs: List<TripLegEntity>)

    @Query("SELECT * FROM trip_legs WHERE tripId = :tripId ORDER BY legIndex ASC")
    suspend fun forTrip(tripId: String): List<TripLegEntity>

    @Query("SELECT * FROM trip_legs WHERE tripId = :tripId ORDER BY legIndex ASC")
    fun flowForTrip(tripId: String): Flow<List<TripLegEntity>>

    @Query("SELECT * FROM trip_legs WHERE tripId = :tripId AND legIndex = :legIndex")
    suspend fun byIndex(tripId: String, legIndex: Int): TripLegEntity?

    @Query("UPDATE trip_legs SET startedAtMs = :ts WHERE tripId = :tripId AND legIndex = :legIndex")
    suspend fun markStarted(tripId: String, legIndex: Int, ts: Long)

    @Query("UPDATE trip_legs SET completedAtMs = :ts WHERE tripId = :tripId AND legIndex = :legIndex")
    suspend fun markCompleted(tripId: String, legIndex: Int, ts: Long)

    @Query("DELETE FROM trip_legs WHERE tripId = :tripId")
    suspend fun deleteForTrip(tripId: String)
}

@Dao
interface ViewerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(v: ViewerTripEntity)

    @Query("SELECT * FROM viewer_trip ORDER BY lastOpenedAtMs DESC")
    fun allFlow(): Flow<List<ViewerTripEntity>>

    @Query("SELECT * FROM viewer_trip WHERE expired = 0 ORDER BY lastOpenedAtMs DESC")
    suspend fun activeList(): List<ViewerTripEntity>

    @Query("SELECT * FROM viewer_trip WHERE accessKey = :key")
    suspend fun byKey(key: String): ViewerTripEntity?

    /**
     * The traveller ended this journey. This is the ONLY way a followed journey
     * is ever marked as over — see [ViewerTripEntity].
     */
    @Query("UPDATE viewer_trip SET expired = 1, endedAtMs = :ts WHERE accessKey = :key")
    suspend fun markEndedByOwner(key: String, ts: Long)

    /** A read succeeded: remember it and clear any "can't reach" marker. */
    @Query("UPDATE viewer_trip SET lastSeenAtMs = :ts, unreachableSinceMs = NULL WHERE accessKey = :key")
    suspend fun markSeen(key: String, ts: Long)

    /** A read failed: record since when, without claiming the journey ended. */
    @Query("UPDATE viewer_trip SET unreachableSinceMs = COALESCE(unreachableSinceMs, :ts) WHERE accessKey = :key")
    suspend fun markUnreachable(key: String, ts: Long)

    @Query("UPDATE viewer_trip SET lastOpenedAtMs = :ts WHERE accessKey = :key")
    suspend fun touch(key: String, ts: Long)

    @Query("DELETE FROM viewer_trip WHERE accessKey = :key")
    suspend fun deleteByKey(key: String)
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
        ViewerTripEntity::class,
        SavedPlaceEntity::class,
        ExpenseEntity::class,
        TripLegEntity::class
    ],
    version = 8,
    exportSchema = false
)
abstract class TripPulseDb : RoomDatabase() {
    abstract fun tripDao(): TripDao
    abstract fun eventDao(): EventDao
    abstract fun locationDao(): LocationDao
    abstract fun stateDao(): StateDao
    abstract fun breakDao(): BreakDao
    abstract fun viewerDao(): ViewerDao
    abstract fun savedPlaceDao(): SavedPlaceDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun legDao(): LegDao

    companion object {
        @Volatile private var INSTANCE: TripPulseDb? = null

        // v1 -> v2: saved places (Home / Office / custom) for quick trip setup.
        private val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS saved_places (" +
                        "name TEXT NOT NULL PRIMARY KEY, " +
                        "lat REAL NOT NULL, lng REAL NOT NULL, createdAtMs INTEGER NOT NULL)"
                )
            }
        }

        // v2 -> v3: owner-only expense records (fuel/food/stay).
        private val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS expenses (" +
                        "id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                        "tripId TEXT NOT NULL, type TEXT NOT NULL, amount REAL NOT NULL, " +
                        "quantity REAL, unit TEXT, note TEXT, tMs INTEGER NOT NULL)"
                )
            }
        }

        // v3 -> v4: transport mode + fuel type on the trip.
        private val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE active_trip ADD COLUMN transportMode TEXT NOT NULL DEFAULT 'CAR'")
                db.execSQL("ALTER TABLE active_trip ADD COLUMN fuelType TEXT")
            }
        }

        /**
         * v4 -> v5: hybrid journey legs, owner-only journey completion, richer
         * break records and an explicit expense item column.
         *
         * Every statement here is additive with a default, which is what makes
         * an app update safe to install *mid-journey*: an in-flight trip keeps
         * its row, its event queue and its location buffer, and simply gains
         * columns whose defaults describe exactly what was already true.
         */
        private val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE active_trip ADD COLUMN activeLegIndex INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE active_trip ADD COLUMN arrivedAtMs INTEGER")
                // Journeys already marked COMPLETED were completed by the old
                // build's rules; honour that rather than reopening them.
                db.execSQL("ALTER TABLE active_trip ADD COLUMN endedByOwner INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE active_trip SET endedByOwner = 1 WHERE status = 'COMPLETED'")

                db.execSQL("ALTER TABLE trip_state ADD COLUMN arrivalPromptDue INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE trip_state ADD COLUMN legIndex INTEGER NOT NULL DEFAULT 0")

                db.execSQL("ALTER TABLE break_records ADD COLUMN tea INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE break_records ADD COLUMN snack INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE break_records ADD COLUMN mealKind TEXT")

                db.execSQL("ALTER TABLE expenses ADD COLUMN item TEXT NOT NULL DEFAULT ''")
                // Older rows kept the description in `note`; promote it so the
                // money tracker and its PDF have an item column from day one.
                db.execSQL("UPDATE expenses SET item = COALESCE(note, '') WHERE item = ''")

                db.execSQL("ALTER TABLE viewer_trip ADD COLUMN endedAtMs INTEGER")
                db.execSQL("ALTER TABLE viewer_trip ADD COLUMN lastSeenAtMs INTEGER")
                db.execSQL("ALTER TABLE viewer_trip ADD COLUMN unreachableSinceMs INTEGER")
                // A previous build marked journeys "expired" simply because the
                // server could not be reached. That was never a statement about
                // the journey, so clear it and let the traveller decide.
                db.execSQL("UPDATE viewer_trip SET expired = 0 WHERE expired = 1")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS trip_legs (" +
                        "tripId TEXT NOT NULL, legIndex INTEGER NOT NULL, mode TEXT NOT NULL, " +
                        "fromName TEXT NOT NULL, fromLat REAL NOT NULL, fromLng REAL NOT NULL, " +
                        "toName TEXT NOT NULL, toLat REAL NOT NULL, toLng REAL NOT NULL, " +
                        "fuelType TEXT, plannedDepartureMs INTEGER, startedAtMs INTEGER, " +
                        "completedAtMs INTEGER, bookingRef TEXT, seat TEXT, boardingPoint TEXT, " +
                        "PRIMARY KEY (tripId, legIndex))"
                )
                // Give every existing journey the single leg it always had.
                db.execSQL(
                    "INSERT OR IGNORE INTO trip_legs (" +
                        "tripId, legIndex, mode, fromName, fromLat, fromLng, toName, toLat, toLng, " +
                        "fuelType, plannedDepartureMs, startedAtMs, completedAtMs, bookingRef, seat, boardingPoint) " +
                        "SELECT tripId, 0, transportMode, originName, originLat, originLng, " +
                        "destName, destLat, destLng, fuelType, plannedDepartureMs, startedAtMs, " +
                        "completedAtMs, NULL, NULL, NULL FROM active_trip"
                )
            }
        }

        /**
         * v5 -> v6: per-mode vehicle and booking details.
         *
         * Additive, like every migration before it, and it carries the two
         * fields that already existed into the new map so a journey created by
         * v5 keeps its seat and booking reference rather than appearing to
         * have lost them.
         */
        private val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE trip_legs ADD COLUMN detailsJson TEXT")
                // Built by concatenation rather than json_object(): the JSON1
                // extension is only guaranteed on Android 11 and later, and a
                // migration that throws on an older phone would be the exact
                // failure this codebase refuses to risk. replace() escapes any
                // quote that found its way into a seat or booking reference.
                db.execSQL(
                    "UPDATE trip_legs SET detailsJson = '{\"seat\":\"' || " +
                        "replace(COALESCE(seat, ''), '\"', '') || '\",\"pnr\":\"' || " +
                        "replace(COALESCE(bookingRef, ''), '\"', '') || '\"}' " +
                        "WHERE seat IS NOT NULL OR bookingRef IS NOT NULL"
                )
            }
        }

        /**
         * v6 -> v7: remembering that a device went dark.
         *
         * Additive, like every migration before it. Nothing is backfilled: a
         * journey that completed under v6 was never dark, and a journey still
         * running gets its fingerprint on the next tick rather than a guess
         * written in now.
         */
        private val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE active_trip ADD COLUMN wentDarkAtMs INTEGER")
                db.execSQL("ALTER TABLE active_trip ADD COLUMN darkReason TEXT")
                db.execSQL("ALTER TABLE active_trip ADD COLUMN simFingerprint TEXT")
                db.execSQL("ALTER TABLE active_trip ADD COLUMN simChangedAtMs INTEGER")
            }
        }

        /**
         * v7 -> v8: the forensic device dossier.
         *
         * Additive, no backfill: a journey created before v8 was never carrying
         * one, and it gets a dossier on its next tick if it is still running.
         */
        private val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE active_trip ADD COLUMN deviceJson TEXT")
            }
        }

        /**
         * No destructive fallback. A journey in progress is irreplaceable data;
         * losing it because a migration was missing would be the worst possible
         * failure mode, so a missing migration must fail loudly in testing
         * instead of silently wiping a traveller's history in the field.
         */
        /**
         * Every migration, in order. Exposed (not private) so an instrumented
         * test can run the exact same objects against a real SQLite database
         * that the app runs in the field — the migration is the one change
         * that, if wrong, destroys a traveller's data unrecoverably, so it is
         * proven on an Android runtime rather than only read.
         */
        val ALL_MIGRATIONS: Array<androidx.room.migration.Migration> = arrayOf(
            MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
            MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8
        )

        fun get(context: Context): TripPulseDb =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    TripPulseDb::class.java,
                    "trippulse.db"
                ).addMigrations(*ALL_MIGRATIONS)
                    .build().also { INSTANCE = it }
            }
    }
}
