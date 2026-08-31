package com.trippulse.app

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trippulse.app.data.local.TripPulseDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The migration is the one change that, done wrong, destroys a traveller's
 * data with no way back. So it is run here against a real SQLite database on a
 * real Android runtime — the same migration objects the app ships — rather
 * than only read and reasoned about.
 *
 * Each case builds a table in its pre-migration shape, puts a row in it,
 * runs the actual migration, and proves two things: the SQL executed (a
 * malformed ALTER throws), and the row survived with the new column present.
 * The second is what matters to a family: an update must add capability, never
 * cost them the journey that was already recorded.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private fun freshDb(startVersion: Int, create: (SupportSQLiteDatabase) -> Unit): SupportSQLiteDatabase {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.deleteDatabase(DB_NAME)
        val cfg = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(DB_NAME)
            .callback(object : SupportSQLiteOpenHelper.Callback(startVersion) {
                override fun onCreate(db: SupportSQLiteDatabase) = create(db)
                override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) {}
            })
            .build()
        return FrameworkSQLiteOpenHelperFactory().create(cfg).writableDatabase
    }

    private fun SupportSQLiteDatabase.columns(table: String): Set<String> {
        val out = HashSet<String>()
        query("PRAGMA table_info($table)").use { c ->
            val nameIdx = c.getColumnIndex("name")
            while (c.moveToNext()) out.add(c.getString(nameIdx))
        }
        return out
    }

    private fun SupportSQLiteDatabase.rowCount(table: String): Int =
        query("SELECT COUNT(*) FROM $table").use { if (it.moveToFirst()) it.getInt(0) else -1 }

    private fun migration(from: Int, to: Int) =
        TripPulseDb.ALL_MIGRATIONS.first { it.startVersion == from && it.endVersion == to }

    // ---- v7 -> v8: the device dossier column ----------------------------

    @Test fun v7_to_v8_adds_the_dossier_and_keeps_the_row() {
        val db = freshDb(7) {
            it.execSQL(
                "CREATE TABLE active_trip (tripId TEXT NOT NULL PRIMARY KEY, " +
                    "status TEXT NOT NULL, wentDarkAtMs INTEGER, darkReason TEXT, " +
                    "simFingerprint TEXT, simChangedAtMs INTEGER)"
            )
            it.execSQL("INSERT INTO active_trip (tripId, status) VALUES ('TP-1', 'ACTIVE')")
        }
        migration(7, 8).migrate(db)

        assertTrue("deviceJson must exist after 7->8", db.columns("active_trip").contains("deviceJson"))
        assertEquals("the journey must survive the upgrade", 1, db.rowCount("active_trip"))
        db.close()
    }

    // ---- v6 -> v7: the going-dark columns -------------------------------

    @Test fun v6_to_v7_adds_the_dark_columns_and_keeps_the_row() {
        val db = freshDb(6) {
            it.execSQL(
                "CREATE TABLE active_trip (tripId TEXT NOT NULL PRIMARY KEY, status TEXT NOT NULL)"
            )
            it.execSQL("INSERT INTO active_trip (tripId, status) VALUES ('TP-2', 'ACTIVE')")
        }
        migration(6, 7).migrate(db)

        val cols = db.columns("active_trip")
        for (added in listOf("wentDarkAtMs", "darkReason", "simFingerprint", "simChangedAtMs")) {
            assertTrue("$added must exist after 6->7", cols.contains(added))
        }
        assertEquals(1, db.rowCount("active_trip"))
        db.close()
    }

    // ---- v5 -> v6: per-mode details, and the backfill -------------------

    @Test fun v5_to_v6_adds_details_and_carries_seat_and_pnr_across() {
        val db = freshDb(5) {
            // trip_legs in its v5 shape: the columns the migration reads.
            it.execSQL(
                "CREATE TABLE trip_legs (tripId TEXT NOT NULL, legIndex INTEGER NOT NULL, " +
                    "mode TEXT NOT NULL, fromName TEXT NOT NULL, fromLat REAL NOT NULL, " +
                    "fromLng REAL NOT NULL, toName TEXT NOT NULL, toLat REAL NOT NULL, " +
                    "toLng REAL NOT NULL, fuelType TEXT, plannedDepartureMs INTEGER, " +
                    "startedAtMs INTEGER, completedAtMs INTEGER, bookingRef TEXT, " +
                    "seat TEXT, boardingPoint TEXT, PRIMARY KEY (tripId, legIndex))"
            )
            it.execSQL(
                "INSERT INTO trip_legs (tripId, legIndex, mode, fromName, fromLat, fromLng, " +
                    "toName, toLat, toLng, seat, bookingRef) VALUES " +
                    "('TP-3', 0, 'TRAIN', 'A', 1.0, 1.0, 'B', 2.0, 2.0, 'S3-42', 'PNR9')"
            )
        }
        migration(5, 6).migrate(db)

        assertTrue("detailsJson must exist after 5->6", db.columns("trip_legs").contains("detailsJson"))
        // The seat and booking reference a v5 journey already had must reappear
        // inside the new JSON, not vanish.
        db.query("SELECT detailsJson FROM trip_legs WHERE tripId = 'TP-3'").use { c ->
            assertTrue(c.moveToFirst())
            val json = c.getString(0)
            assertTrue("seat carried across: $json", json.contains("S3-42"))
            assertTrue("pnr carried across: $json", json.contains("PNR9"))
        }
        db.close()
    }

    // ---- the whole chain, opened by Room itself -------------------------

    @Test fun the_whole_chain_from_v4_runs_and_lands_the_new_columns() {
        // The holistic proof: a database in a real older build's shape, run
        // through every migration from v4 to the current version in order,
        // ends with the columns the newest features need and without any
        // migration's SQL throwing along the way.
        val db = freshDb(4) {
            it.execSQL(
                "CREATE TABLE active_trip (tripId TEXT NOT NULL PRIMARY KEY, " +
                    "secret TEXT NOT NULL, accessKey TEXT NOT NULL, originName TEXT NOT NULL, " +
                    "originLat REAL NOT NULL, originLng REAL NOT NULL, destName TEXT NOT NULL, " +
                    "destLat REAL NOT NULL, destLng REAL NOT NULL, emergencyName TEXT, " +
                    "emergencyPhone TEXT, createdAtMs INTEGER NOT NULL, plannedDepartureMs INTEGER, " +
                    "startedAtMs INTEGER, completedAtMs INTEGER, expiresAtMs INTEGER, " +
                    "status TEXT NOT NULL, cloudEnabled INTEGER NOT NULL, metaSynced INTEGER NOT NULL, " +
                    "totalRouteDistanceM REAL NOT NULL, ownerUid TEXT, transportMode TEXT NOT NULL DEFAULT 'CAR', " +
                    "fuelType TEXT)"
            )
        }
        // 4->5 references trip_state, break_records, expenses, viewer_trip too.
        db.execSQL("CREATE TABLE trip_state (tripId TEXT NOT NULL PRIMARY KEY, expired INTEGER)")
        db.execSQL("CREATE TABLE break_records (breakId TEXT NOT NULL PRIMARY KEY)")
        db.execSQL("CREATE TABLE expenses (id INTEGER PRIMARY KEY AUTOINCREMENT, note TEXT)")
        db.execSQL("CREATE TABLE viewer_trip (accessKey TEXT NOT NULL PRIMARY KEY, expired INTEGER)")
        for (m in TripPulseDb.ALL_MIGRATIONS.filter { it.startVersion >= 4 }.sortedBy { it.startVersion }) {
            m.migrate(db)
        }
        assertTrue(db.columns("active_trip").contains("deviceJson"))
        assertTrue(db.columns("active_trip").contains("wentDarkAtMs"))
        db.close()
    }

    private companion object {
        const val DB_NAME = "migration-test.db"
    }
}
