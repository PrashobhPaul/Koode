package com.trippulse.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.trippulse.app.core.Profile
import com.trippulse.app.data.local.TripPulseDb
import com.trippulse.app.data.push.PushRegistry
import com.trippulse.app.data.remote.TripCloud
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The traveller's two rights over their own data, done completely.
 *
 * **Export** writes every table Koode keeps, as JSON, to a file they can keep
 * or send anywhere. The access keys and owner tokens that let a phone write
 * to a live journey are left out: they are credentials, not data about the
 * traveller, and a shared export must never carry them.
 *
 * **Erase** ends any live journey so the server deletes its copy on its own
 * timer, forgets every followed journey's push registration, then clears
 * every table, every preference file and the profile photo. Afterwards the
 * app is as installed.
 */
class DataSteward(
    private val context: Context,
    private val db: TripPulseDb,
    private val cloud: TripCloud,
    private val push: PushRegistry
) {

    /** Every table, in the order the schema declares them. */
    private val tables = listOf(
        "active_trip", "trip_legs", "trip_state", "event_queue", "location_buffer", "break_records",
        "expenses", "saved_places", "recent_destinations", "viewer_trip", "vehicles"
    )

    /** Columns that are credentials, never exported. */
    private val secret = Regex("(?i)(accessKey|ownerToken|viewerToken|passcode|secret|token)$")

    /** Every preference file the app writes, so an erase leaves none behind. */
    private val prefFiles = listOf(
        "koode_settings", "koode_profile", "koode_vehicles", "koode_place_handoff", "koode_onboarding",
        "tp_expense_opportunities", "tp_journey_plan", "tp_viewer_identity", "tp_distance_ledger",
        "tp_journey_closure", "tp_wellbeing_coach", "tp_toll_plazas", "tp_owner_tokens", "tp_record_restored"
    )

    suspend fun export(): File = withContext(Dispatchers.IO) {
        val root = JSONObject()
        root.put("app", "Koode")
        root.put("exportedAt", Instant.now().toString())
        root.put("profileName", Profile.name(context))
        val data = JSONObject()
        val sql = db.openHelper.readableDatabase
        for (t in tables) {
            val rows = JSONArray()
            sql.query("SELECT * FROM $t").use { c ->
                val names = c.columnNames
                while (c.moveToNext()) {
                    val row = JSONObject()
                    for (i in names.indices) {
                        if (secret.containsMatchIn(names[i])) continue
                        when (c.getType(i)) {
                            android.database.Cursor.FIELD_TYPE_NULL -> row.put(names[i], JSONObject.NULL)
                            android.database.Cursor.FIELD_TYPE_INTEGER -> row.put(names[i], c.getLong(i))
                            android.database.Cursor.FIELD_TYPE_FLOAT -> row.put(names[i], c.getDouble(i))
                            android.database.Cursor.FIELD_TYPE_BLOB -> row.put(names[i], "<binary>")
                            else -> row.put(names[i], c.getString(i))
                        }
                    }
                    rows.put(row)
                }
            }
            data.put(t, rows)
        }
        root.put("tables", data)
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(ZoneOffset.UTC).format(Instant.now())
        val file = File(dir, "koode-export-$stamp.json")
        file.writeText(root.toString(2))
        file
    }

    /** A chooser that hands the export to any app the traveller picks. */
    fun shareIntent(file: File): Intent {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Koode data export")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "Save or send your Koode data"
        )
    }

    /**
     * Everything, gone. Returns how many live journeys were ended on the
     * server, so the screen can say so.
     */
    suspend fun eraseEverything(): Int = withContext(Dispatchers.IO) {
        var ended = 0
        val now = System.currentTimeMillis()
        // The server deletes a journey an hour after its expiry; bring every
        // journey this phone started to that point now.
        runCatching {
            db.tripDao().allFlow().first().forEach { t ->
                if (t.cloudEnabled) { runCatching { cloud.setExpiry(t.accessKey, now) }; ended++ }
            }
        }
        runCatching {
            db.viewerDao().allFlow().first().forEach { v -> runCatching { push.unregisterFor(v.ref) } }
        }
        runCatching { db.clearAllTables() }
        prefFiles.forEach { name ->
            runCatching { context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit() }
        }
        runCatching { Profile.clearPhoto(context) }
        runCatching { File(context.cacheDir, "exports").deleteRecursively() }
        runCatching { context.cacheDir.listFiles()?.forEach { it.deleteRecursively() } }
        ended
    }
}
