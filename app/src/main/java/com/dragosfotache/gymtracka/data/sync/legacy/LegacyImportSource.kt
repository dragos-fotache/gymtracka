package com.dragosfotache.gymtracka.data.sync.legacy

import android.database.Cursor
import android.util.Base64
import com.dragosfotache.gymtracka.data.gym.GymDatabase
import com.dragosfotache.gymtracka.data.sync.http.ServiceOrigin
import com.dragosfotache.gymtracka.data.sync.model.AggregateType
import com.dragosfotache.gymtracka.data.sync.model.CustomExerciseInputModel
import com.dragosfotache.gymtracka.data.sync.model.ImportRecord
import com.dragosfotache.gymtracka.data.sync.model.RoutineExerciseInputModel
import com.dragosfotache.gymtracka.data.sync.model.RoutineInputModel
import com.dragosfotache.gymtracka.data.sync.model.RoutineSetModel
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import com.dragosfotache.gymtracka.data.sync.model.WorkoutExerciseInputModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutImportModel
import com.dragosfotache.gymtracka.data.sync.model.WorkoutSetModel
import com.dragosfotache.gymtracka.data.sync.model.requireWorkoutUuid
import com.dragosfotache.gymtracka.data.sync.model.validateCustomExerciseInputModel
import com.dragosfotache.gymtracka.data.sync.model.validateRoutineInputModel
import com.dragosfotache.gymtracka.data.sync.model.validateWorkoutImportModel
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Raw source-freeze and typed validation helpers for local capture.
 *
 * Source JSON structure (versioned local evidence, NOT a wire/request format):
 * ```
 * {"v":1,
 *  "workouts":[[cell,...]], "workout_exercises":[[cell,...]],
 *  "workout_sets":[[cell,...]], "routines":[[cell,...]],
 *  "routine_exercises":[[cell,...]], "routine_sets":[[cell,...]],
 *  "exercises":[[cell,...]], "exercise_muscle_groups":[[cell,...]],
 *  "exercise_equipment":[[cell,...]]}
 * ```
 * Each cell is `{"t":"null"}` | `{"t":"int","v":"<decimal>"}` |
 * `{"t":"float","v":"<raw double bits decimal>"}` |
 * `{"t":"text","v":"<exact>"}` | `{"t":"blob","v":"<base64>"}`.
 * Column order per table is fixed in [FROZEN_TABLES]; row order is stable
 * ORDER BY id/position as frozen. Version is validated on recovery; tests use
 * independent raw SQL preservation and raw-source substring/type checks.
 */
internal object LegacyImportSource {
    const val EVIDENCE_VERSION: Long = 1L
    const val PHASE_CAPTURED = "captured"
    const val PHASE_BLOCKED = "blocked"
    const val BLOCKED_ERROR_CODE = "invalid_source"

    fun canonicalOriginText(origin: ServiceOrigin): String {
        val host = if (origin.host.contains(':')) "[${origin.host}]" else origin.host
        return "https://${host}:${origin.port}"
    }

    fun parseCanonicalOrigin(text: String): ServiceOrigin {
        val parsed = try {
            ServiceOrigin.parse(text)
        } catch (_: Exception) {
            throw SyncModelException("Invalid service origin")
        }
        if (canonicalOriginText(parsed) != text) throw SyncModelException("Non-canonical origin")
        return parsed
    }

    private data class FrozenTable(val key: String, val sql: String)

    private val FROZEN_TABLES = listOf(
        FrozenTable(
            "workouts",
            """SELECT id, name, started_at_millis, finished_at_millis, status,
                account_id, server_revision, local_generation, sync_state,
                deleted_at_millis FROM workouts
                WHERE account_id IS NULL ORDER BY id""",
        ),
        FrozenTable(
            "workout_exercises",
            """SELECT we.id, we.workout_id, we.exercise_id, we.position,
                we.notes, we.rest_duration_seconds
                FROM workout_exercises we JOIN workouts w ON w.id = we.workout_id
                WHERE w.account_id IS NULL
                ORDER BY we.workout_id, we.position, we.id""",
        ),
        FrozenTable(
            "workout_sets",
            """SELECT ws.id, ws.workout_exercise_id, ws.position, ws.repetitions,
                ws.weight_kg, ws.is_completed, ws.rest_seconds
                FROM workout_sets ws
                JOIN workout_exercises we ON we.id = ws.workout_exercise_id
                JOIN workouts w ON w.id = we.workout_id
                WHERE w.account_id IS NULL
                ORDER BY ws.workout_exercise_id, ws.position, ws.id""",
        ),
        FrozenTable(
            "routines",
            """SELECT id, name, is_draft, account_id, server_revision,
                local_generation, sync_state, deleted_at_millis FROM routines
                WHERE account_id IS NULL ORDER BY id""",
        ),
        FrozenTable(
            "routine_exercises",
            """SELECT re.id, re.routine_id, re.exercise_id, re.position,
                re.rest_duration_seconds
                FROM routine_exercises re JOIN routines r ON r.id = re.routine_id
                WHERE r.account_id IS NULL
                ORDER BY re.routine_id, re.position, re.id""",
        ),
        FrozenTable(
            "routine_sets",
            """SELECT rs.id, rs.routine_exercise_id, rs.position,
                rs.repetitions, rs.weight_kg
                FROM routine_sets rs
                JOIN routine_exercises re ON re.id = rs.routine_exercise_id
                JOIN routines r ON r.id = re.routine_id
                WHERE r.account_id IS NULL
                ORDER BY rs.routine_exercise_id, rs.position, rs.id""",
        ),
        FrozenTable(
            "exercises",
            """SELECT id, name, type, difficulty_level, force_type, mechanics,
                category, instructions, is_user_created, account_id,
                server_revision, local_generation, sync_state, deleted_at_millis
                FROM exercises
                WHERE account_id IS NULL
                    AND (is_user_created != 0 OR typeof(is_user_created) != 'integer')
                ORDER BY id""",
        ),
        FrozenTable(
            "exercise_muscle_groups",
            """SELECT emg.exercise_id, emg.muscle_group_id, emg.type
                FROM exercise_muscle_groups emg JOIN exercises e ON e.id = emg.exercise_id
                WHERE e.account_id IS NULL
                    AND (e.is_user_created != 0 OR typeof(e.is_user_created) != 'integer')
                ORDER BY emg.exercise_id, emg.type, emg.muscle_group_id""",
        ),
        FrozenTable(
            "exercise_equipment",
            """SELECT ee.exercise_id, ee.equipment_id, ee.num_items
                FROM exercise_equipment ee JOIN exercises e ON e.id = ee.exercise_id
                WHERE e.account_id IS NULL
                    AND (e.is_user_created != 0 OR typeof(e.is_user_created) != 'integer')
                ORDER BY ee.exercise_id, ee.equipment_id""",
        ),
    )

    private fun cellJson(cursor: Cursor, index: Int): JSONObject {
        return when (cursor.getType(index)) {
            Cursor.FIELD_TYPE_NULL -> JSONObject().put("t", "null")
            Cursor.FIELD_TYPE_INTEGER -> JSONObject()
                .put("t", "int")
                .put("v", cursor.getLong(index).toString())
            Cursor.FIELD_TYPE_FLOAT -> JSONObject()
                .put("t", "float")
                .put("v", java.lang.Double.doubleToRawLongBits(cursor.getDouble(index)).toString())
            Cursor.FIELD_TYPE_STRING -> JSONObject()
                .put("t", "text")
                .put("v", cursor.getString(index))
            Cursor.FIELD_TYPE_BLOB -> JSONObject()
                .put("t", "blob")
                .put("v", Base64.encodeToString(cursor.getBlob(index), Base64.NO_WRAP))
            else -> throw SyncModelException("Unsupported SQLite storage type")
        }
    }

    fun freeze(database: GymDatabase): String {
        val root = JSONObject().put("v", 1)
        val db = database.openHelper.writableDatabase
        for (table in FROZEN_TABLES) {
            val rows = JSONArray()
            db.query(table.sql).use { cursor ->
                val width = cursor.columnCount
                while (cursor.moveToNext()) {
                    val row = JSONArray()
                    for (i in 0 until width) row.put(cellJson(cursor, i))
                    rows.put(row)
                }
            }
            root.put(table.key, rows)
        }
        return root.toString()
    }

    fun validateSourceJson(sourceJson: String): JSONObject {
        val root = try {
            JSONObject(sourceJson)
        } catch (_: JSONException) {
            throw SyncModelException("Malformed source JSON")
        }
        val expectedKeys = FROZEN_TABLES.map { it.key }.toSet() + "v"
        val actualKeys = root.keys().asSequence().toSet()
        if (actualKeys != expectedKeys) throw SyncModelException("Malformed source evidence")
        val versionRaw = try {
            root.get("v")
        } catch (_: JSONException) {
            throw SyncModelException("Malformed source evidence")
        }
        if ((versionRaw !is Int && versionRaw !is Long) || (versionRaw as Number).toLong() != 1L) {
            throw SyncModelException("Unsupported source evidence version")
        }
        val widths = mapOf(
            "workouts" to 10, "workout_exercises" to 6, "workout_sets" to 7,
            "routines" to 8, "routine_exercises" to 5, "routine_sets" to 5,
            "exercises" to 14, "exercise_muscle_groups" to 3, "exercise_equipment" to 3,
        )
        for (table in FROZEN_TABLES) {
            val rawTable = try {
                root.get(table.key)
            } catch (_: JSONException) {
                throw SyncModelException("Malformed source evidence")
            }
            if (rawTable !is JSONArray) throw SyncModelException("Malformed source evidence")
            val width = widths[table.key] ?: throw SyncModelException("Malformed source evidence")
            for (i in 0 until rawTable.length()) {
                val rowRaw = try {
                    rawTable.get(i)
                } catch (_: JSONException) {
                    throw SyncModelException("Malformed source evidence")
                }
                if (rowRaw !is JSONArray || rowRaw.length() != width) {
                    throw SyncModelException("Malformed source evidence")
                }
                for (c in 0 until rowRaw.length()) {
                    val cellRaw = try {
                        rowRaw.get(c)
                    } catch (_: JSONException) {
                        throw SyncModelException("Malformed source evidence")
                    }
                    if (cellRaw !is JSONObject) throw SyncModelException("Malformed source evidence")
                    val tRaw = try {
                        cellRaw.get("t")
                    } catch (_: JSONException) {
                        throw SyncModelException("Malformed source evidence")
                    }
                    if (tRaw !is String) throw SyncModelException("Malformed source evidence")
                    val wantKeys = if (tRaw == "null") 1 else 2
                    if (cellRaw.length() != wantKeys) throw SyncModelException("Malformed source evidence")
                    if (!cellRaw.has("t")) throw SyncModelException("Malformed source evidence")
                    when (tRaw) {
                        "null" -> if (cellRaw.has("v")) throw SyncModelException("Malformed source evidence")
                        "int", "float", "text", "blob" -> {
                            val vRaw = try {
                                cellRaw.get("v")
                            } catch (_: JSONException) {
                                throw SyncModelException("Malformed source evidence")
                            }
                            if (vRaw !is String) throw SyncModelException("Malformed source evidence")
                            when (tRaw) {
                                "int", "float" -> if (!isCanonicalLong(vRaw)) {
                                    throw SyncModelException("Malformed source evidence")
                                }
                                "blob" -> try {
                                    val decoded = Base64.decode(vRaw, Base64.NO_WRAP)
                                    if (Base64.encodeToString(decoded, Base64.NO_WRAP) != vRaw) {
                                        throw SyncModelException("Malformed source evidence")
                                    }
                                } catch (_: Exception) {
                                    throw SyncModelException("Malformed source evidence")
                                }
                                else -> Unit
                            }
                        }
                        else -> throw SyncModelException("Malformed source evidence")
                    }
                }
            }
        }
        return root
    }

    private fun isCanonicalLong(s: String): Boolean {
        if (s.isEmpty()) return false
        if (s == "0") return true
        val body = if (s.startsWith("-")) s.substring(1) else s
        if (body.isEmpty() || body.length > 19) return false
        if (body[0] == '0') return false
        if (body.any { it !in '0'..'9' }) return false
        return try {
            s.toLong()
            true
        } catch (_: NumberFormatException) {
            false
        }
    }

    /** Validated local graph in deterministic dependency order. */
    data class ValidGraph(
        val customs: List<CustomExerciseInputModel>,
        val routines: List<RoutineInputModel>,
        val finishedWorkouts: List<WorkoutImportModel>,
        val activeWorkout: WorkoutImportModel?,
    ) {
        val importCount: Int get() = customs.size + routines.size + finishedWorkouts.size +
            (if (activeWorkout != null) 1 else 0)

        fun orderedRecords(): List<Pair<AggregateType, ImportRecord>> {
            val out = ArrayList<Pair<AggregateType, ImportRecord>>()
            customs.sortedBy { it.id }.forEach {
                out.add(AggregateType.CUSTOM_EXERCISE to ImportRecord.CustomExercise(it))
            }
            routines.sortedBy { it.id }.forEach {
                out.add(AggregateType.ROUTINE to ImportRecord.Routine(it))
            }
            finishedWorkouts.sortedBy { it.id }.forEach {
                out.add(AggregateType.WORKOUT to ImportRecord.Workout(it))
            }
            activeWorkout?.let { out.add(AggregateType.WORKOUT to ImportRecord.Workout(it)) }
            return out
        }
    }

    private data class RawWorkout(
        val id: String, val name: String, val startedAt: Long, val finishedAt: Long?,
        val status: String, val generation: Long,
    )
    private data class RawWorkoutExercise(
        val id: String, val workoutId: String, val exerciseId: String,
        val position: Int, val notes: String, val rest: Int,
    )
    private data class RawWorkoutSet(
        val id: String, val parentId: String, val position: Int,
        val reps: Int?, val weight: Double?, val completed: Boolean, val rest: Int?,
    )
    private data class RawRoutine(
        val id: String, val name: String, val draft: Boolean, val generation: Long,
    )
    private data class RawRoutineExercise(
        val id: String, val routineId: String, val exerciseId: String,
        val position: Int, val rest: Int,
    )
    private data class RawRoutineSet(
        val id: String, val parentId: String, val position: Int,
        val reps: Int?, val weight: Double?,
    )
    private data class RawCustom(
        val id: String, val name: String, val type: String,
        val difficulty: String?, val force: String?, val mechanics: String?,
        val category: String?, val instructions: List<String>?,
        val instructionsWasNull: Boolean, val generation: Long,
    )

    private fun Cursor.requireIntText(index: Int, field: String): Long {
        if (getType(index) != Cursor.FIELD_TYPE_INTEGER) throw SyncModelException("$field requires INTEGER")
        return getLong(index)
    }

    private fun Cursor.optionalInt(index: Int, field: String): Long? {
        return when (getType(index)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_INTEGER -> getLong(index)
            else -> throw SyncModelException("$field requires INTEGER or NULL")
        }
    }

    private fun Cursor.requireLabel(index: Int, field: String): String {
        if (getType(index) != Cursor.FIELD_TYPE_STRING) throw SyncModelException("$field requires TEXT")
        return getString(index)
    }

    private fun Cursor.optionalLabel(index: Int, field: String): String? {
        return when (getType(index)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_STRING -> getString(index)
            else -> throw SyncModelException("$field requires TEXT or NULL")
        }
    }

    private fun Cursor.requireNull(index: Int, field: String) {
        if (getType(index) != Cursor.FIELD_TYPE_NULL) throw SyncModelException("$field must be NULL")
    }

    private fun Cursor.requireFlag(index: Int, field: String): Boolean {
        if (getType(index) != Cursor.FIELD_TYPE_INTEGER) throw SyncModelException("$field requires INTEGER")
        return when (val v = getLong(index)) {
            0L -> false
            1L -> true
            else -> throw SyncModelException("$field requires 0/1")
        }
    }

    private fun Cursor.optionalFlag(index: Int, field: String): Int? {
        return when (getType(index)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_INTEGER -> when (val v = getLong(index)) {
                0L, 1L -> v.toInt()
                else -> throw SyncModelException("$field requires 0/1 or NULL")
            }
            else -> throw SyncModelException("$field requires INTEGER or NULL")
        }
    }

    private fun Cursor.optionalNonNegativeInt(index: Int, field: String): Int? {
        return when (getType(index)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_INTEGER -> {
                val v = getLong(index)
                if (v < 0L || v > Int.MAX_VALUE) throw SyncModelException("$field out of range")
                v.toInt()
            }
            else -> throw SyncModelException("$field requires INTEGER or NULL")
        }
    }

    private fun Cursor.requireNonNegativeInt(index: Int, field: String): Int {
        if (getType(index) != Cursor.FIELD_TYPE_INTEGER) throw SyncModelException("$field requires INTEGER")
        val v = getLong(index)
        if (v < 0L || v > Int.MAX_VALUE) throw SyncModelException("$field out of range")
        return v.toInt()
    }

    private fun Cursor.optionalWeight(index: Int, field: String): Double? {
        return when (getType(index)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_FLOAT -> getDouble(index)
            Cursor.FIELD_TYPE_INTEGER -> getLong(index).toDouble()
            else -> throw SyncModelException("$field requires REAL/INTEGER or NULL")
        }
    }

    private fun parseInstructions(raw: String?): Pair<List<String>?, Boolean> {
        if (raw == null) return null to true
        val array = try {
            JSONArray(raw)
        } catch (_: JSONException) {
            throw SyncModelException("Invalid stored instructions")
        }
        val out = ArrayList<String>(array.length())
        for (i in 0 until array.length()) {
            val element = try {
                array.get(i)
            } catch (_: JSONException) {
                throw SyncModelException("Invalid stored instructions")
            }
            if (element !is String) throw SyncModelException("Invalid stored instructions")
            out.add(element)
        }
        return out to false
    }

    private fun exists(database: GymDatabase, sql: String, arg: String): Boolean {
        database.openHelper.writableDatabase.query(sql, arrayOf<Any>(arg)).use { c ->
            if (!c.moveToFirst()) return false
            return c.getLong(0) == 1L
        }
    }

    fun buildValidatedGraph(database: GymDatabase): ValidGraph {
        val db = database.openHelper.writableDatabase
        val workouts = ArrayList<RawWorkout>()
        db.query(
            """SELECT id, name, started_at_millis, finished_at_millis, status,
                account_id, server_revision, local_generation, sync_state,
                deleted_at_millis FROM workouts
                WHERE account_id IS NULL ORDER BY id""",
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.requireLabel(0, "workout id").also { requireWorkoutUuid(it, "id") }
                val name = c.requireLabel(1, "workout name")
                val started = c.requireIntText(2, "startedAt")
                val finished = when (c.getType(3)) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_INTEGER -> c.getLong(3)
                    else -> throw SyncModelException("finishedAt requires INTEGER or NULL")
                }
                val status = c.requireLabel(4, "status")
                if (status != "active" && status != "finished") throw SyncModelException("Invalid workout status")
                c.requireNull(5, "workout account")
                c.requireNull(6, "workout server revision")
                val gen = c.requireIntText(7, "workout generation")
                if (gen < 0L) throw SyncModelException("Negative generation")
                if (c.requireLabel(8, "sync state") != "local_only") throw SyncModelException("Nonlocal metadata")
                if (c.getType(9) != Cursor.FIELD_TYPE_NULL) throw SyncModelException("Tombstoned root")
                workouts.add(RawWorkout(id, name, started, finished, status, gen))
            }
        }
        val workoutExercises = ArrayList<RawWorkoutExercise>()
        db.query(
            """SELECT we.id, we.workout_id, we.exercise_id, we.position,
                we.notes, we.rest_duration_seconds
                FROM workout_exercises we JOIN workouts w ON w.id = we.workout_id
                WHERE w.account_id IS NULL
                ORDER BY we.workout_id, we.position, we.id""",
        ).use { c ->
            while (c.moveToNext()) {
                workoutExercises.add(
                    RawWorkoutExercise(
                        id = c.requireLabel(0, "workout exercise id").also { requireWorkoutUuid(it, "id") },
                        workoutId = c.requireLabel(1, "workout id").also { requireWorkoutUuid(it, "id") },
                        exerciseId = c.requireLabel(2, "exerciseId").also { requireWorkoutUuid(it, "id") },
                        position = c.requireNonNegativeInt(3, "position"),
                        notes = c.requireLabel(4, "notes"),
                        rest = c.requireNonNegativeInt(5, "rest"),
                    ),
                )
            }
        }
        val workoutSets = ArrayList<RawWorkoutSet>()
        db.query(
            """SELECT ws.id, ws.workout_exercise_id, ws.position, ws.repetitions,
                ws.weight_kg, ws.is_completed, ws.rest_seconds
                FROM workout_sets ws
                JOIN workout_exercises we ON we.id = ws.workout_exercise_id
                JOIN workouts w ON w.id = we.workout_id
                WHERE w.account_id IS NULL
                ORDER BY ws.workout_exercise_id, ws.position, ws.id""",
        ).use { c ->
            while (c.moveToNext()) {
                workoutSets.add(
                    RawWorkoutSet(
                        id = c.requireLabel(0, "set id").also { requireWorkoutUuid(it, "id") },
                        parentId = c.requireLabel(1, "parent").also { requireWorkoutUuid(it, "id") },
                        position = c.requireNonNegativeInt(2, "position"),
                        reps = c.optionalNonNegativeInt(3, "repetitions"),
                        weight = c.optionalWeight(4, "weight"),
                        completed = c.requireFlag(5, "is_completed"),
                        rest = c.optionalNonNegativeInt(6, "rest_seconds"),
                    ),
                )
            }
        }
        val routines = ArrayList<RawRoutine>()
        db.query(
            """SELECT id, name, is_draft, account_id, server_revision,
                local_generation, sync_state, deleted_at_millis FROM routines
                WHERE account_id IS NULL ORDER BY id""",
        ).use { c ->
            while (c.moveToNext()) {
                routines.add(
                    RawRoutine(
                        id = c.requireLabel(0, "routine id").also { requireWorkoutUuid(it, "id") },
                        name = c.requireLabel(1, "routine name"),
                        draft = c.requireFlag(2, "is_draft"),
                        generation = run {
                            c.requireNull(3, "routine account")
                            c.requireNull(4, "routine server revision")
                            val g = c.requireIntText(5, "routine generation")
                            if (g < 0L) throw SyncModelException("Negative generation")
                            if (c.requireLabel(6, "sync state") != "local_only") {
                                throw SyncModelException("Nonlocal metadata")
                            }
                            if (c.getType(7) != Cursor.FIELD_TYPE_NULL) throw SyncModelException("Tombstoned root")
                            g
                        },
                    ),
                )
            }
        }
        val routineExercises = ArrayList<RawRoutineExercise>()
        db.query(
            """SELECT re.id, re.routine_id, re.exercise_id, re.position,
                re.rest_duration_seconds
                FROM routine_exercises re JOIN routines r ON r.id = re.routine_id
                WHERE r.account_id IS NULL
                ORDER BY re.routine_id, re.position, re.id""",
        ).use { c ->
            while (c.moveToNext()) {
                routineExercises.add(
                    RawRoutineExercise(
                        id = c.requireLabel(0, "routine exercise id").also { requireWorkoutUuid(it, "id") },
                        routineId = c.requireLabel(1, "routine id").also { requireWorkoutUuid(it, "id") },
                        exerciseId = c.requireLabel(2, "exerciseId").also { requireWorkoutUuid(it, "id") },
                        position = c.requireNonNegativeInt(3, "position"),
                        rest = c.requireNonNegativeInt(4, "rest"),
                    ),
                )
            }
        }
        val routineSets = ArrayList<RawRoutineSet>()
        db.query(
            """SELECT rs.id, rs.routine_exercise_id, rs.position,
                rs.repetitions, rs.weight_kg
                FROM routine_sets rs
                JOIN routine_exercises re ON re.id = rs.routine_exercise_id
                JOIN routines r ON r.id = re.routine_id
                WHERE r.account_id IS NULL
                ORDER BY rs.routine_exercise_id, rs.position, rs.id""",
        ).use { c ->
            while (c.moveToNext()) {
                routineSets.add(
                    RawRoutineSet(
                        id = c.requireLabel(0, "routine set id").also { requireWorkoutUuid(it, "id") },
                        parentId = c.requireLabel(1, "parent").also { requireWorkoutUuid(it, "id") },
                        position = c.requireNonNegativeInt(2, "position"),
                        reps = c.optionalNonNegativeInt(3, "repetitions"),
                        weight = c.optionalWeight(4, "weight"),
                    ),
                )
            }
        }
        val customs = ArrayList<RawCustom>()
        val muscleRows = HashMap<String, MutableList<Triple<String, String, String>>>()
        val equipmentRows = HashMap<String, MutableList<Pair<String, Int?>>>()
        db.query(
            """SELECT id, name, type, difficulty_level, force_type, mechanics,
                category, instructions, is_user_created, account_id,
                server_revision, local_generation, sync_state, deleted_at_millis
                FROM exercises
                WHERE account_id IS NULL
                    AND (is_user_created != 0 OR typeof(is_user_created) != 'integer')
                ORDER BY id""",
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.requireLabel(0, "custom id").also { requireWorkoutUuid(it, "id") }
                val name = c.requireLabel(1, "custom name")
                val type = c.requireLabel(2, "custom type")
                if (type != "reps") throw SyncModelException("Custom type must be reps")
                val difficulty = c.optionalLabel(3, "difficulty")
                val force = c.optionalLabel(4, "force")
                val mechanics = c.optionalLabel(5, "mechanics")
                val category = c.optionalLabel(6, "category")
                val instructionsRaw = when (c.getType(7)) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_STRING -> c.getString(7)
                    else -> throw SyncModelException("Instructions require TEXT or NULL")
                }
                val (instructions, wasNull) = parseInstructions(instructionsRaw)
                if (!c.requireFlag(8, "is_user_created")) throw SyncModelException("Bundled row")
                c.requireNull(9, "custom account")
                c.requireNull(10, "custom server revision")
                val gen = c.requireIntText(11, "custom generation")
                if (gen < 0L) throw SyncModelException("Negative generation")
                if (c.requireLabel(12, "sync state") != "local_only") throw SyncModelException("Nonlocal metadata")
                if (c.getType(13) != Cursor.FIELD_TYPE_NULL) throw SyncModelException("Tombstoned root")
                customs.add(
                    RawCustom(id, name, type, difficulty, force, mechanics, category, instructions, wasNull, gen),
                )
            }
        }
        db.query(
            """SELECT emg.exercise_id, emg.muscle_group_id, emg.type
                FROM exercise_muscle_groups emg JOIN exercises e ON e.id = emg.exercise_id
                WHERE e.account_id IS NULL
                    AND (e.is_user_created != 0 OR typeof(e.is_user_created) != 'integer')
                ORDER BY emg.exercise_id, emg.type, emg.muscle_group_id""",
        ).use { c ->
            while (c.moveToNext()) {
                val ex = c.requireLabel(0, "muscle exercise").also { requireWorkoutUuid(it, "id") }
                val muscle = c.requireLabel(1, "muscle").also { requireWorkoutUuid(it, "id") }
                val role = c.requireLabel(2, "role")
                if (role != "primary" && role != "secondary") throw SyncModelException("Invalid muscle role")
                muscleRows.getOrPut(ex) { ArrayList() }.add(Triple(ex, muscle, role))
            }
        }
        db.query(
            """SELECT ee.exercise_id, ee.equipment_id, ee.num_items
                FROM exercise_equipment ee JOIN exercises e ON e.id = ee.exercise_id
                WHERE e.account_id IS NULL
                    AND (e.is_user_created != 0 OR typeof(e.is_user_created) != 'integer')
                ORDER BY ee.exercise_id, ee.equipment_id""",
        ).use { c ->
            while (c.moveToNext()) {
                val ex = c.requireLabel(0, "equipment exercise").also { requireWorkoutUuid(it, "id") }
                val eq = c.requireLabel(1, "equipment").also { requireWorkoutUuid(it, "id") }
                val qty = when (c.getType(2)) {
                    Cursor.FIELD_TYPE_NULL -> null
                    Cursor.FIELD_TYPE_INTEGER -> {
                        val v = c.getLong(2)
                        if (v < 0L || v > Int.MAX_VALUE) throw SyncModelException("Equipment quantity out of range")
                        v.toInt()
                    }
                    else -> throw SyncModelException("Equipment quantity requires INTEGER or NULL")
                }
                val list = equipmentRows.getOrPut(ex) { ArrayList() }
                if (list.isNotEmpty()) throw SyncModelException("Custom has more than one equipment")
                list.add(eq to qty)
            }
        }

        if (workouts.isEmpty() && routines.isEmpty() && customs.isEmpty()) {
            throw EmptyLocalData()
        }

        val activeWorkouts = workouts.filter { it.status == "active" }
        if (activeWorkouts.size > 1) throw SyncModelException("More than one active workout")

        val workoutIds = workouts.map { it.id }
        if (workoutIds.toSet().size != workoutIds.size) throw SyncModelException("Duplicate workout IDs")
        val routineIds = routines.map { it.id }
        if (routineIds.toSet().size != routineIds.size) throw SyncModelException("Duplicate routine IDs")
        val customIds = customs.map { it.id }
        if (customIds.toSet().size != customIds.size) throw SyncModelException("Duplicate custom IDs")

        val workoutExerciseIds = workoutExercises.map { it.id }
        val workoutSetIds = workoutSets.map { it.id }
        val allWorkoutFamily = workoutIds + workoutExerciseIds + workoutSetIds
        if (allWorkoutFamily.toSet().size != allWorkoutFamily.size) {
            throw SyncModelException("Workout family IDs overlap")
        }
        val routineExerciseIds = routineExercises.map { it.id }
        val routineSetIds = routineSets.map { it.id }
        val allRoutineFamily = routineIds + routineExerciseIds + routineSetIds
        if (allRoutineFamily.toSet().size != allRoutineFamily.size) {
            throw SyncModelException("Routine family IDs overlap")
        }

        val exercisesByWorkout = workoutExercises.groupBy { it.workoutId }
        val setsByWorkoutExercise = workoutSets.groupBy { it.parentId }
        val exercisesByRoutine = routineExercises.groupBy { it.routineId }
        val setsByRoutineExercise = routineSets.groupBy { it.parentId }

        for (w in workouts) {
            val entries = exercisesByWorkout[w.id].orEmpty().sortedBy { it.position }
            entries.forEachIndexed { index, e ->
                if (e.position != index) throw SyncModelException("Workout position gap")
                if (e.workoutId != w.id) throw SyncModelException("Workout child mismatch")
                if (!exists(database, "SELECT EXISTS(SELECT 1 FROM exercises WHERE id = ?)", e.exerciseId)) {
                    throw SyncModelException("Unknown exercise reference")
                }
                if (exists(
                        database,
                        "SELECT EXISTS(SELECT 1 FROM exercises WHERE id = ? AND account_id IS NOT NULL)",
                        e.exerciseId,
                    )
                ) {
                    throw SyncModelException("Foreign custom reference")
                }
            }
            val entryIds = entries.map { it.id }
            if (entryIds.toSet().size != entryIds.size) throw SyncModelException("Duplicate workout exercise IDs")
            for (e in entries) {
                val sets = setsByWorkoutExercise[e.id].orEmpty().sortedBy { it.position }
                sets.forEachIndexed { index, s ->
                    if (s.position != index) throw SyncModelException("Workout set position gap")
                }
                val ids = sets.map { it.id }
                if (ids.toSet().size != ids.size) throw SyncModelException("Duplicate workout set IDs")
            }
        }
        for (r in routines) {
            val entries = exercisesByRoutine[r.id].orEmpty().sortedBy { it.position }
            entries.forEachIndexed { index, e ->
                if (e.position != index) throw SyncModelException("Routine position gap")
                if (e.routineId != r.id) throw SyncModelException("Routine child mismatch")
                if (!exists(database, "SELECT EXISTS(SELECT 1 FROM exercises WHERE id = ?)", e.exerciseId)) {
                    throw SyncModelException("Unknown exercise reference")
                }
                if (exists(
                        database,
                        "SELECT EXISTS(SELECT 1 FROM exercises WHERE id = ? AND account_id IS NOT NULL)",
                        e.exerciseId,
                    )
                ) {
                    throw SyncModelException("Foreign custom reference")
                }
            }
            for (e in entries) {
                val sets = setsByRoutineExercise[e.id].orEmpty().sortedBy { it.position }
                sets.forEachIndexed { index, s ->
                    if (s.position != index) throw SyncModelException("Routine set position gap")
                }
            }
        }
        for (raw in customs) {
            for ((_, muscle, _) in muscleRows[raw.id].orEmpty()) {
                if (!exists(database, "SELECT EXISTS(SELECT 1 FROM muscle_groups WHERE id = ?)", muscle)) {
                    throw SyncModelException("Unknown muscle reference")
                }
            }
            for ((eq, _) in equipmentRows[raw.id].orEmpty()) {
                if (!exists(database, "SELECT EXISTS(SELECT 1 FROM equipment WHERE id = ?)", eq)) {
                    throw SyncModelException("Unknown equipment reference")
                }
            }
        }

        val customInputs = customs.map { raw ->
            val rels = muscleRows[raw.id].orEmpty()
            val primaries = rels.filter { it.third == "primary" }
            if (primaries.size > 1) throw SyncModelException("Custom has more than one primary")
            val primary = primaries.singleOrNull()?.second
            val secondaries = rels.filter { it.third == "secondary" }.map { it.second }
            val eq = equipmentRows[raw.id].orEmpty().singleOrNull()
            validateCustomExerciseInputModel(
                CustomExerciseInputModel(
                    id = raw.id,
                    name = raw.name,
                    primaryMuscleGroupId = primary,
                    secondaryMuscleGroupIds = secondaries,
                    equipmentId = eq?.first,
                    equipmentNumItems = eq?.second,
                    category = raw.category,
                    difficultyLevel = raw.difficulty,
                    forceType = raw.force,
                    mechanics = raw.mechanics,
                    instructions = raw.instructions,
                ),
            )
        }
        val routineInputs = routines.map { r ->
            val entries = exercisesByRoutine[r.id].orEmpty().sortedBy { it.position }.map { e ->
                val sets = setsByRoutineExercise[e.id].orEmpty().sortedBy { it.position }.map { s ->
                    RoutineSetModel(id = s.id, position = s.position, repetitions = s.reps, weightKg = s.weight)
                }
                RoutineExerciseInputModel(
                    id = e.id, exerciseId = e.exerciseId, position = e.position,
                    restDurationSeconds = e.rest, sets = sets,
                )
            }
            validateRoutineInputModel(
                RoutineInputModel(id = r.id, name = r.name, isDraft = r.draft, exercises = entries),
            )
        }
        val workoutImports = workouts.map { w ->
            val entries = exercisesByWorkout[w.id].orEmpty().sortedBy { it.position }.map { e ->
                val sets = setsByWorkoutExercise[e.id].orEmpty().sortedBy { it.position }.map { s ->
                    WorkoutSetModel(
                        id = s.id, position = s.position, repetitions = s.reps,
                        weightKg = s.weight, isCompleted = s.completed, restSeconds = s.rest,
                    )
                }
                WorkoutExerciseInputModel(
                    id = e.id, exerciseId = e.exerciseId, position = e.position,
                    notes = e.notes, restDurationSeconds = e.rest, sets = sets,
                )
            }
            validateWorkoutImportModel(
                WorkoutImportModel(
                    id = w.id, name = w.name, status = w.status,
                    startedAtMillis = w.startedAt, finishedAtMillis = w.finishedAt,
                    exercises = entries,
                ),
            )
        }
        return ValidGraph(
            customs = customInputs,
            routines = routineInputs,
            finishedWorkouts = workoutImports.filter { it.status == "finished" },
            activeWorkout = workoutImports.singleOrNull { it.status == "active" },
        )
    }

    class EmptyLocalData : Exception()

    /**
     * Target-scope raw freeze using the same versioned cell encoding and stable
     * ordering as [freeze], but for rows already bound to [accountId]. Child and
     * relationship rows join through the bound roots; the global catalogue is
     * never frozen.
     */
    fun freezeTargetScope(database: GymDatabase, accountId: String): String {
        val root = JSONObject().put("v", 1)
        val arg = arrayOf<Any>(accountId)
        val tables = listOf(
            "workouts" to """SELECT id, name, started_at_millis, finished_at_millis, status,
                account_id, server_revision, local_generation, sync_state,
                deleted_at_millis FROM workouts
                WHERE account_id = ? ORDER BY id""",
            "workout_exercises" to """SELECT we.id, we.workout_id, we.exercise_id, we.position,
                we.notes, we.rest_duration_seconds
                FROM workout_exercises we JOIN workouts w ON w.id = we.workout_id
                WHERE w.account_id = ?
                ORDER BY we.workout_id, we.position, we.id""",
            "workout_sets" to """SELECT ws.id, ws.workout_exercise_id, ws.position, ws.repetitions,
                ws.weight_kg, ws.is_completed, ws.rest_seconds
                FROM workout_sets ws
                JOIN workout_exercises we ON we.id = ws.workout_exercise_id
                JOIN workouts w ON w.id = we.workout_id
                WHERE w.account_id = ?
                ORDER BY ws.workout_exercise_id, ws.position, ws.id""",
            "routines" to """SELECT id, name, is_draft, account_id, server_revision,
                local_generation, sync_state, deleted_at_millis FROM routines
                WHERE account_id = ? ORDER BY id""",
            "routine_exercises" to """SELECT re.id, re.routine_id, re.exercise_id, re.position,
                re.rest_duration_seconds
                FROM routine_exercises re JOIN routines r ON r.id = re.routine_id
                WHERE r.account_id = ?
                ORDER BY re.routine_id, re.position, re.id""",
            "routine_sets" to """SELECT rs.id, rs.routine_exercise_id, rs.position,
                rs.repetitions, rs.weight_kg
                FROM routine_sets rs
                JOIN routine_exercises re ON re.id = rs.routine_exercise_id
                JOIN routines r ON r.id = re.routine_id
                WHERE r.account_id = ?
                ORDER BY rs.routine_exercise_id, rs.position, rs.id""",
            "exercises" to """SELECT id, name, type, difficulty_level, force_type, mechanics,
                category, instructions, is_user_created, account_id,
                server_revision, local_generation, sync_state, deleted_at_millis
                FROM exercises WHERE account_id = ? AND is_user_created = 1
                ORDER BY id""",
            "exercise_muscle_groups" to """SELECT emg.exercise_id, emg.muscle_group_id, emg.type
                FROM exercise_muscle_groups emg JOIN exercises e ON e.id = emg.exercise_id
                WHERE e.account_id = ? AND e.is_user_created = 1
                ORDER BY emg.exercise_id, emg.type, emg.muscle_group_id""",
            "exercise_equipment" to """SELECT ee.exercise_id, ee.equipment_id, ee.num_items
                FROM exercise_equipment ee JOIN exercises e ON e.id = ee.exercise_id
                WHERE e.account_id = ? AND e.is_user_created = 1
                ORDER BY ee.exercise_id, ee.equipment_id""",
        )
        val db = database.openHelper.writableDatabase
        for ((key, sql) in tables) {
            val rows = JSONArray()
            db.query(sql, arg).use { cursor ->
                val width = cursor.columnCount
                while (cursor.moveToNext()) {
                    val row = JSONArray()
                    for (i in 0 until width) row.put(cellJson(cursor, i))
                    rows.put(row)
                }
            }
            root.put(key, rows)
        }
        return root.toString()
    }

    /**
     * Strict raw business preservation check between the frozen local source
     * and the frozen bound target. Allows ONLY account_id (null to target),
     * local_generation (original to original+1) and sync_state (local_only to
     * pending) transitions on root rows; every other cell, including types,
     * must match exactly. Throws [SyncModelException] (blocked/corrupt mapping
     * by the caller) on any other difference.
     */
    fun verifyBusinessPreserved(originalSourceJson: String, targetJson: String, accountId: String) {
        val original = validateSourceJson(originalSourceJson)
        val target = validateSourceJson(targetJson)
        val allowed = mapOf(
            "workouts" to mapOf(5 to "account", 7 to "generation", 8 to "state"),
            "routines" to mapOf(3 to "account", 5 to "generation", 6 to "state"),
            "exercises" to mapOf(9 to "account", 11 to "generation", 12 to "state"),
        )
        for (table in FROZEN_TABLES) {
            val oRows = original.getJSONArray(table.key)
            val tRows = target.getJSONArray(table.key)
            if (oRows.length() != tRows.length()) throw SyncModelException("Business changed")
            for (i in 0 until oRows.length()) {
                val oRow = oRows.getJSONArray(i)
                val tRow = tRows.getJSONArray(i)
                for (c in 0 until oRow.length()) {
                    val kind = allowed[table.key]?.get(c)
                    if (kind == null) {
                        if (!cellsEqual(oRow.getJSONObject(c), tRow.getJSONObject(c))) {
                            throw SyncModelException("Business changed")
                        }
                    } else {
                        verifyAllowedTransition(
                            kind, oRow.getJSONObject(c), tRow.getJSONObject(c), accountId,
                        )
                    }
                }
            }
        }
    }

    private fun cellsEqual(a: JSONObject, b: JSONObject): Boolean {
        if (a.getString("t") != b.getString("t")) return false
        if (a.getString("t") == "null") return true
        return a.getString("v") == b.getString("v")
    }

    private fun verifyAllowedTransition(kind: String, o: JSONObject, t: JSONObject, accountId: String) {
        when (kind) {
            "account" -> {
                if (o.getString("t") != "null") throw SyncModelException("Business changed")
                if (t.getString("t") != "text" || t.getString("v") != accountId) {
                    throw SyncModelException("Business changed")
                }
            }
            "generation" -> {
                if (o.getString("t") != "int" || t.getString("t") != "int") {
                    throw SyncModelException("Business changed")
                }
                val before = o.getString("v").toLongOrNull()
                    ?: throw SyncModelException("Business changed")
                val after = t.getString("v").toLongOrNull()
                    ?: throw SyncModelException("Business changed")
                if (before == Long.MAX_VALUE || after != before + 1L) {
                    throw SyncModelException("Business changed")
                }
            }
            "state" -> {
                if (o.getString("t") != "text" || o.getString("v") != "local_only") {
                    throw SyncModelException("Business changed")
                }
                if (t.getString("t") != "text" || t.getString("v") != "pending") {
                    throw SyncModelException("Business changed")
                }
            }
            else -> throw SyncModelException("Business changed")
        }
    }

    /** Requires the local source scope to be empty (no late-added local roots). */
    fun verifyLocalScopeEmpty(database: GymDatabase) {
        val frozen = validateSourceJson(freeze(database))
        for (table in FROZEN_TABLES) {
            if (frozen.getJSONArray(table.key).length() != 0) {
                throw SyncModelException("Late local root")
            }
        }
    }

    private fun sourceCellText(row: JSONArray, index: Int): String {
        val cell = row.getJSONObject(index)
        if (cell.getString("t") != "text") throw SyncModelException("Malformed source evidence")
        return cell.getString("v")
    }

    private fun sourceCellLong(row: JSONArray, index: Int): Long {
        val cell = row.getJSONObject(index)
        if (cell.getString("t") != "int") throw SyncModelException("Malformed source evidence")
        return cell.getString("v").toLongOrNull()
            ?: throw SyncModelException("Malformed source evidence")
    }

    /**
     * Expected capture entries derived from validated source JSON in
     * deterministic dependency order (customs, routines, finished workouts by
     * UUID, active workout last) with original local generations. Lets
     * recovery link source_json to journal entries: count, order, identity and
     * original g+1 linkage. At most one active workout is accepted; anything
     * else fails closed.
     */
    fun expectedEntriesFromSource(source: JSONObject): List<Triple<AggregateType, String, Long>> {
        val customs = ArrayList<Pair<String, Long>>()
        val exercises = source.getJSONArray("exercises")
        for (i in 0 until exercises.length()) {
            val row = exercises.getJSONArray(i)
            customs.add(sourceCellText(row, 0) to sourceCellLong(row, 11))
        }
        val routines = ArrayList<Pair<String, Long>>()
        val routineRows = source.getJSONArray("routines")
        for (i in 0 until routineRows.length()) {
            val row = routineRows.getJSONArray(i)
            routines.add(sourceCellText(row, 0) to sourceCellLong(row, 5))
        }
        val finished = ArrayList<Pair<String, Long>>()
        val actives = ArrayList<Pair<String, Long>>()
        val workoutRows = source.getJSONArray("workouts")
        for (i in 0 until workoutRows.length()) {
            val row = workoutRows.getJSONArray(i)
            val id = sourceCellText(row, 0)
            val status = sourceCellText(row, 4)
            val gen = sourceCellLong(row, 7)
            when (status) {
                "finished" -> finished.add(id to gen)
                "active" -> actives.add(id to gen)
                else -> throw SyncModelException("Malformed source evidence")
            }
        }
        if (actives.size > 1) throw SyncModelException("Malformed source evidence")
        val out = ArrayList<Triple<AggregateType, String, Long>>()
        customs.sortedBy { it.first }.forEach { (id, gen) ->
            out.add(Triple(AggregateType.CUSTOM_EXERCISE, id, gen))
        }
        routines.sortedBy { it.first }.forEach { (id, gen) ->
            out.add(Triple(AggregateType.ROUTINE, id, gen))
        }
        finished.sortedBy { it.first }.forEach { (id, gen) ->
            out.add(Triple(AggregateType.WORKOUT, id, gen))
        }
        actives.singleOrNull()?.let { (id, gen) ->
            out.add(Triple(AggregateType.WORKOUT, id, gen))
        }
        return out
    }
}
