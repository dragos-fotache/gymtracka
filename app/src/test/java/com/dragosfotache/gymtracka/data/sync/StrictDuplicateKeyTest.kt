package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.sync.codec.decodeCustomExerciseInput
import com.dragosfotache.gymtracka.data.sync.codec.decodeCustomExerciseRecord
import com.dragosfotache.gymtracka.data.sync.codec.decodeDurableRequest
import com.dragosfotache.gymtracka.data.sync.codec.decodeMutationEnvelope
import com.dragosfotache.gymtracka.data.sync.codec.decodePullResult
import com.dragosfotache.gymtracka.data.sync.codec.decodePushResult
import com.dragosfotache.gymtracka.data.sync.codec.decodeRoutineInput
import com.dragosfotache.gymtracka.data.sync.codec.decodeRoutineRecord
import com.dragosfotache.gymtracka.data.sync.codec.decodeSnapshotResult
import com.dragosfotache.gymtracka.data.sync.codec.decodeWorkoutInput
import com.dragosfotache.gymtracka.data.sync.codec.decodeWorkoutRecord
import com.dragosfotache.gymtracka.data.sync.codec.encodePushMutationsBody
import com.dragosfotache.gymtracka.data.sync.codec.parseStrictJsonObject
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import com.dragosfotache.gymtracka.data.sync.model.UnsupportedDurableVersionException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * R02a: raw JSON duplicate object-member rejection at the shared JSON-object boundary.
 *
 * Independent behavioral expectations derived from docs/remaining-work/R02a-task.md and the
 * shared backend contract only. All JSON fixtures are hand-authored string literals; no
 * production encoder is used as an oracle.
 */
class StrictDuplicateKeyTest {

    // Distinct lowercase UUID fixtures; field names follow the shared OpenAPI.
    private val workoutId = "11111111-1111-1111-1111-111111111111"
    private val routineId = "22222222-2222-2222-2222-222222222222"
    private val customId = "33333333-3333-3333-3333-333333333333"
    private val mutationId = "44444444-4444-4444-4444-444444444444"
    private val workoutId2 = "55555555-5555-5555-5555-555555555555"
    private val mutationId2 = "66666666-6666-6666-6666-666666666666"
    private val mutationId3 = "77777777-7777-7777-7777-777777777777"
    private val startedAt = "2026-10-03T12:00:00Z"

    // ---- Hand-authored valid fixtures (independent minimal wire fixtures per spec). ----

    private fun validWorkoutInput(): String =
        "{\"id\":\"$workoutId\",\"name\":\"\",\"status\":\"active\"," +
            "\"startedAt\":\"$startedAt\",\"exercises\":[]}"

    private fun validWorkoutRecord(): String =
        "{\"id\":\"$workoutId\",\"name\":\"\",\"status\":\"active\"," +
            "\"startedAt\":\"$startedAt\",\"finishedAt\":null,\"revision\":1,\"exercises\":[]}"

    private fun validRoutineInput(): String =
        "{\"id\":\"$routineId\",\"name\":\"\",\"isDraft\":true,\"exercises\":[]}"

    private fun validRoutineRecord(): String =
        "{\"id\":\"$routineId\",\"name\":\"\",\"isDraft\":true,\"revision\":1,\"exercises\":[]}"

    private fun validCustomInput(): String = "{\"id\":\"$customId\",\"name\":\"Custom\"}"

    private fun validCustomRecord(): String =
        "{\"id\":\"$customId\",\"name\":\"Custom\",\"type\":\"reps\"," +
            "\"isUserCreated\":true,\"revision\":1,\"muscleGroups\":[],\"equipment\":[]}"

    private fun validPushResult(): String = "{\"results\":[]}"

    private fun validPullResult(): String =
        "{\"batches\":[],\"nextCursor\":\"opaque-tail\",\"hasMore\":false}"

    private fun validSnapshotResult(): String =
        "{\"records\":[],\"baselineCursor\":\"opaque-baseline\",\"hasMore\":false}"

    private fun validMutation(
        mutation: String = mutationId,
        root: String = workoutId,
    ): String =
        "{\"mutationId\":\"$mutation\",\"type\":\"workout.start\"," +
            "\"aggregateType\":\"workout\",\"aggregateId\":\"$root\",\"localGeneration\":1," +
            "\"payload\":{\"id\":\"$root\",\"name\":\"\",\"startedAt\":\"$startedAt\"}}"

    // ---- Small independent JSON string helpers (not production code). ----

    /** Minimal JSON-string escaping used only to hand-author durable wrappers. */
    private fun escapeForJsonString(raw: String): String =
        raw
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

    private fun durableWrapper(innerExact: String, version: Int = 1, root: String = workoutId): String {
        val escaped = escapeForJsonString(innerExact)
        return "{\"payloadVersion\":$version,\"requestJson\":\"$escaped\"," +
            "\"affectedRoots\":[{\"aggregateType\":\"workout\"," +
            "\"aggregateId\":\"$root\",\"localGeneration\":1}]}"
    }

    /** Workout input with a single fully populated set; numeric literals are raw source text. */
    private fun workoutInputWithSet(weightLiteral: String, repsLiteral: String = "8"): String =
        "{\"id\":\"$workoutId\",\"name\":\"\",\"status\":\"active\"," +
            "\"startedAt\":\"$startedAt\",\"exercises\":[{\"id\":\"aaaaaaaa-1111-1111-1111-000000000010\"," +
            "\"exerciseId\":\"$customId\",\"position\":0,\"notes\":\"\"," +
            "\"restDurationSeconds\":30,\"sets\":[{\"id\":\"bbbbbbbb-2222-2222-2222-000000000010\"," +
            "\"position\":0,\"repetitions\":$repsLiteral,\"weightKg\":$weightLiteral," +
            "\"isCompleted\":true,\"restSeconds\":60}]}]}"

    /** Inserts an additional root member before the final closing brace. */
    private fun withRootDuplicate(json: String, key: String, valueLiteral: String): String {
        val trimmed = json.trimEnd()
        check(trimmed.endsWith("}")) { "fixture must be a JSON object" }
        return trimmed.dropLast(1) + ",\"$key\":$valueLiteral}"
    }

    // ---- Assertion helpers for observable behavior. ----

    /**
     * Duplicate-key failures must be safe: fixed text, no raw body/member name/value,
     * and no retained raw parser cause. Never silently keep first/last member.
     */
    private val duplicateMessage: String by lazy {
        try {
            parseStrictJsonObject("""{"reference":1,"reference":2}""")
            throw AssertionError("raw duplicate must reject")
        } catch (error: SyncModelException) {
            assertNull(error.cause)
            error.message ?: throw AssertionError("duplicate failure must have safe text")
        }
    }

    private fun assertRejectsDuplicate(jsonText: String, decode: (String) -> Any?) {
        try {
            decode(jsonText)
        } catch (e: SyncModelException) {
            assertNull("duplicate-key failure must not retain a raw parser cause", e.cause)
            val message = e.message ?: ""
            assertTrue("duplicate-key failure must carry fixed safe text", message.isNotBlank())
            assertEquals("must fail at duplicate boundary, not incidental model validation", duplicateMessage, message)
            val haystack = message + "\n" + e.toString()
            assertTrue(
                "duplicate-key failure must not echo the raw body",
                !haystack.contains(jsonText),
            )
            return
        }
        fail("expected SyncModelException for duplicate keys in: $jsonText")
    }

    private fun assertAccepts(jsonText: String, decode: (String) -> Any?): Any? {
        try {
            val result = decode(jsonText)
            assertNotNull("valid JSON must decode to a model", result)
            return result
        } catch (e: SyncModelException) {
            fail("valid JSON must not be rejected: $jsonText (was: ${e.message})")
            throw e
        }
    }

    // ---- Acceptance: exact root duplicates, same and different values, every route. ----

    @Test
    fun exactRootDuplicateRejectsOnEveryRawRoute() {
        val routes: List<Triple<String, () -> String, (String) -> Any?>> = listOf(
            Triple("workoutInput", ::validWorkoutInput, ::decodeWorkoutInput),
            Triple("workoutRecord", ::validWorkoutRecord, ::decodeWorkoutRecord),
            Triple("routineInput", ::validRoutineInput, ::decodeRoutineInput),
            Triple("routineRecord", ::validRoutineRecord, ::decodeRoutineRecord),
            Triple("customInput", ::validCustomInput, ::decodeCustomExerciseInput),
            Triple("customRecord", ::validCustomRecord, ::decodeCustomExerciseRecord),
            Triple("mutation", { validMutation() }, ::decodeMutationEnvelope),
            Triple("pushResult", ::validPushResult, ::decodePushResult),
            Triple("pullResult", ::validPullResult, ::decodePullResult),
            Triple("snapshotResult", ::validSnapshotResult, ::decodeSnapshotResult),
        )
        for ((_, valid, decode) in routes) {
            assertAccepts(valid(), decode)
        }
        // Same-value and different-value duplicates per route (required-field duplicates).
        assertRejectsDuplicate(withRootDuplicate(validWorkoutInput(), "name", "\"\""), ::decodeWorkoutInput)
        assertRejectsDuplicate(
            withRootDuplicate(validWorkoutInput(), "name", "\"taken\""),
            ::decodeWorkoutInput,
        )
        assertRejectsDuplicate(
            withRootDuplicate(validWorkoutRecord(), "revision", "1"),
            ::decodeWorkoutRecord,
        )
        assertRejectsDuplicate(
            withRootDuplicate(validWorkoutRecord(), "revision", "2"),
            ::decodeWorkoutRecord,
        )
        assertRejectsDuplicate(
            withRootDuplicate(validRoutineInput(), "isDraft", "true"),
            ::decodeRoutineInput,
        )
        assertRejectsDuplicate(
            withRootDuplicate(validRoutineInput(), "isDraft", "false"),
            ::decodeRoutineInput,
        )
        assertRejectsDuplicate(
            withRootDuplicate(validRoutineRecord(), "name", "\"\""),
            ::decodeRoutineRecord,
        )
        assertRejectsDuplicate(
            withRootDuplicate(validRoutineRecord(), "name", "\"other\""),
            ::decodeRoutineRecord,
        )
        assertRejectsDuplicate(
            withRootDuplicate(validCustomInput(), "name", "\"Custom\""),
            ::decodeCustomExerciseInput,
        )
        assertRejectsDuplicate(
            withRootDuplicate(validCustomInput(), "name", "\"Other\""),
            ::decodeCustomExerciseInput,
        )
        assertRejectsDuplicate(
            withRootDuplicate(validCustomRecord(), "revision", "1"),
            ::decodeCustomExerciseRecord,
        )
        assertRejectsDuplicate(
            withRootDuplicate(validCustomRecord(), "revision", "7"),
            ::decodeCustomExerciseRecord,
        )
        assertRejectsDuplicate(
            withRootDuplicate(validMutation(), "type", "\"workout.start\""),
            ::decodeMutationEnvelope,
        )
        assertRejectsDuplicate(
            withRootDuplicate(validMutation(), "localGeneration", "1"),
            ::decodeMutationEnvelope,
        )
        assertRejectsDuplicate(
            withRootDuplicate(validPushResult(), "results", "[]"),
            ::decodePushResult,
        )
        assertRejectsDuplicate(
            withRootDuplicate(validPushResult(), "results", "null"),
            ::decodePushResult,
        )
        assertRejectsDuplicate(
            withRootDuplicate(validPullResult(), "hasMore", "false"),
            ::decodePullResult,
        )
        assertRejectsDuplicate(
            withRootDuplicate(validPullResult(), "hasMore", "true"),
            ::decodePullResult,
        )
        assertRejectsDuplicate(
            withRootDuplicate(validSnapshotResult(), "baselineCursor", "\"opaque-baseline\""),
            ::decodeSnapshotResult,
        )
        assertRejectsDuplicate(
            withRootDuplicate(validSnapshotResult(), "baselineCursor", "\"other\""),
            ::decodeSnapshotResult,
        )
        assertEquals("every raw route covered", 10, routes.size)
        assertTrue("route labels are distinct", routes.map { it.first }.toSet().size == 10)
    }

    // ---- Acceptance: escaped-equivalent names (Unicode, slash, quote, backslash). ----

    @Test
    fun escapedEquivalentNamesReject() {
        // "name" spelled with a \u006e escape aliases the plain spelling.
        assertRejectsDuplicate(
            "{\"id\":\"$workoutId\",\"name\":\"a\",\"\\u006eame\":\"b\"," +
                "\"status\":\"active\",\"startedAt\":\"$startedAt\",\"exercises\":[]}",
            ::decodeWorkoutInput,
        )
        // Nested payload alias.
        assertRejectsDuplicate(
            "{\"mutationId\":\"$mutationId\",\"type\":\"workout.start\"," +
                "\"aggregateType\":\"workout\",\"aggregateId\":\"$workoutId\"," +
                "\"localGeneration\":1,\"payload\":{\"id\":\"$workoutId\"," +
                "\"name\":\"\",\"\\u006eame\":\"x\",\"startedAt\":\"$startedAt\"}}",
            ::decodeMutationEnvelope,
        )
        // Escaped custom field alias.
        assertRejectsDuplicate(
            "{\"id\":\"$customId\",\"name\":\"Custom\",\"\\u006eame\":\"Other\"}",
            ::decodeCustomExerciseInput,
        )
        // Escaped slash spellings decode to the same name.
        assertRejectsDuplicate("{\"a/b\":1,\"a\\/b\":2}", ::parseStrictJsonObject)
        // Quote via \" versus \u0022 decodes to the same name.
        assertRejectsDuplicate("{\"a\\\"b\":1,\"a\\u0022b\":2}", ::parseStrictJsonObject)
        // Backslash via \\ versus \u005c decodes to the same name.
        assertRejectsDuplicate("{\"a\\\\b\":1,\"a\\u005cb\":2}", ::parseStrictJsonObject)
        // Raw café versus escaped caf\u00e9 decodes to the same name.
        assertRejectsDuplicate("""{"café":1,"caf\u00e9":2}""", ::parseStrictJsonObject)
        // Raw Kotlin strings retain the JSON escape spellings verbatim.
        for (body in listOf(
            """{"a\bb":1,"a\u0008b":2}""",
            """{"a\fb":1,"a\u000cb":2}""",
            """{"a\tb":1,"a\u0009b":2}""",
            """{"a\nb":1,"a\u000ab":2}""",
            """{"a\rb":1,"a\u000db":2}""",
            """{"𝄞":1,"\uD834\uDD1E":2}""",
        )) {
            assertRejectsDuplicate(body, ::parseStrictJsonObject)
        }
    }

    // ---- Acceptance: nested objects, arrays, multiple levels, ignored extension. ----

    @Test
    fun nestedAndArrayDuplicatesReject() {
        // Duplicate inside an object nested in an array element.
        assertRejectsDuplicate(
            "{\"id\":\"$workoutId\",\"name\":\"\",\"status\":\"active\"," +
                "\"startedAt\":\"$startedAt\",\"exercises\":[{\"id\":\"$customId\"," +
                "\"exerciseId\":\"$customId\",\"position\":0,\"position\":1,\"notes\":\"\"," +
                "\"restDurationSeconds\":30,\"sets\":[]}]}",
            ::decodeWorkoutInput,
        )
        // Duplicate several levels deep.
        assertRejectsDuplicate(
            "{\"l0\":{\"l1\":{\"l2\":{\"x\":1,\"x\":2}}}}",
            ::parseStrictJsonObject,
        )
        // Duplicate inside an ignored canonical extension field object.
        assertRejectsDuplicate(
            "{\"id\":\"$workoutId\",\"name\":\"\",\"status\":\"active\"," +
                "\"startedAt\":\"$startedAt\",\"finishedAt\":null,\"revision\":1," +
                "\"exercises\":[],\"extra\":{\"a\":1,\"a\":2}}",
            ::decodeWorkoutRecord,
        )
        // A single ignored convenience member is unique and must accept (see
        // validShapesTolerancesDecodeUnchanged); duplicating it rejects outright.
        assertRejectsDuplicate(
            "{\"id\":\"$workoutId\",\"name\":\"\",\"status\":\"active\"," +
                "\"startedAt\":\"$startedAt\",\"finishedAt\":null,\"revision\":1," +
                "\"exercises\":[],\"totalVolumeKg\":1,\"totalVolumeKg\":2}",
            ::decodeWorkoutRecord,
        )
        // Valid result envelopes with ignored, deeply nested additive fields.
        val extension = """"extra":{"array":[{"inner":{"key":1,"key":2}}]}"""
        assertRejectsDuplicate(
            withRootDuplicate(validPushResult(), "extension", "{$extension}"),
            ::decodePushResult,
        )
        assertRejectsDuplicate(
            withRootDuplicate(validPullResult(), "extension", "{$extension}"),
            ::decodePullResult,
        )
        assertRejectsDuplicate(
            withRootDuplicate(validSnapshotResult(), "extension", "{$extension}"),
            ::decodeSnapshotResult,
        )
    }

    // ---- Acceptance: same name in different objects is valid. ----

    @Test
    fun sameNameInDifferentObjectsAccepts() {
        assertAccepts("{\"a\":{\"x\":1},\"b\":{\"x\":2}}", ::parseStrictJsonObject)
        // Sibling array elements may reuse member names.
        assertAccepts(
            "{\"id\":\"$workoutId\",\"name\":\"\",\"status\":\"active\"," +
                "\"startedAt\":\"$startedAt\",\"exercises\":[" +
                "{\"id\":\"aaaaaaaa-1111-1111-1111-000000000001\"," +
                "\"exerciseId\":\"$customId\",\"position\":0,\"notes\":\"\"," +
                "\"restDurationSeconds\":30,\"sets\":[]}," +
                "{\"id\":\"aaaaaaaa-1111-1111-1111-000000000002\"," +
                "\"exerciseId\":\"$customId\",\"position\":1,\"notes\":\"\"," +
                "\"restDurationSeconds\":30,\"sets\":[]}]}",
            ::decodeWorkoutInput,
        )
        // Outer and nested objects may reuse member names.
        assertAccepts(
            """{"id":1,"child":{"id":2}}""",
            ::parseStrictJsonObject,
        )
        assertAccepts(durableWrapper(validMutation()), ::decodeDurableRequest)
    }

    // ---- Acceptance: JSON-looking text inside string values is not a duplicate. ----

    @Test
    fun jsonLookingStringValuesAccept() {
        assertAccepts(
            "{\"id\":\"$workoutId\",\"name\":\"}{ \\\"name\\\":\\\"x\\\", \\\"name\\\":\\\"y\\\"}\"," +
                "\"status\":\"active\",\"startedAt\":\"$startedAt\",\"exercises\":[]}",
            ::decodeWorkoutInput,
        )
        assertAccepts(
            "{\"id\":\"$workoutId\",\"name\":\"a\\\\b\\\"c\\/d\"," +
                "\"status\":\"active\",\"startedAt\":\"$startedAt\",\"exercises\":[]}",
            ::decodeWorkoutInput,
        )
        assertAccepts(
            "{\"id\":\"$customId\",\"name\":\"{\\\"a\\\":1,\\\"a\\\":2}\"}",
            ::decodeCustomExerciseInput,
        )
        // Repeated string values are not duplicate object members.
        assertAccepts("{\"a\":\"same\",\"b\":\"same\"}", ::parseStrictJsonObject)
    }

    // ---- Acceptance: JSON name equality without normalization. ----

    @Test
    fun nameEqualityHasNoNormalization() {
        // Case-distinct names are distinct.
        assertAccepts("{\"Name\":1,\"name\":2}", ::parseStrictJsonObject)
        // Composed vs decomposed Unicode remain distinct (no normalization).
        assertAccepts("{\"caf\\u00e9\":1,\"cafe\\u0301\":2}", ::parseStrictJsonObject)
        // Empty member name repeated rejects.
        assertRejectsDuplicate("{\"\":1,\"\":2}", ::parseStrictJsonObject)
        // Whitespace-different names are distinct; exact repeat rejects.
        assertAccepts("{\"a\":1,\"a \":2}", ::parseStrictJsonObject)
        assertRejectsDuplicate("{\"a\":1,\"a\":2}", ::parseStrictJsonObject)
    }

    // ---- Acceptance: valid minimal/canonical inputs decode unchanged. ----

    @Test
    fun validMinimalAndCanonicalDecodeUnchanged() {
        assertAccepts(validWorkoutInput(), ::decodeWorkoutInput)
        assertAccepts(validWorkoutRecord(), ::decodeWorkoutRecord)
        assertAccepts(validRoutineInput(), ::decodeRoutineInput)
        assertAccepts(validRoutineRecord(), ::decodeRoutineRecord)
        assertAccepts(validCustomInput(), ::decodeCustomExerciseInput)
        assertAccepts(validCustomRecord(), ::decodeCustomExerciseRecord)
        assertAccepts(validMutation(), ::decodeMutationEnvelope)
        assertAccepts(durableWrapper(validMutation()), ::decodeDurableRequest)
        assertAccepts(validPushResult(), ::decodePushResult)
        assertAccepts(validPullResult(), ::decodePullResult)
        assertAccepts(validSnapshotResult(), ::decodeSnapshotResult)
    }

    @Test
    fun validShapesTolerancesDecodeUnchanged() {
        // Explicit nulls, numbers, whitespace/escapes, array contents, millisecond
        // timestamps, and a tolerated canonical convenience field.
        assertAccepts(
            "{\n  \"id\" : \"$workoutId\" ,\n  \"name\" : \"A\\\"B\\\\C\\/D\\nE\\u00e9\" ,\n" +
                "  \"status\" : \"active\" ,\n  \"startedAt\" : \"2026-10-03T12:00:00.123Z\" ,\n" +
                "  \"exercises\" : [ {\n    \"id\" : \"aaaaaaaa-1111-1111-1111-000000000010\" ,\n" +
                "    \"exerciseId\" : \"$customId\" ,\n    \"position\" : 0 ,\n" +
                "    \"notes\" : \"hi\" ,\n    \"restDurationSeconds\" : 30 ,\n" +
                "    \"sets\" : [ { \"id\" : \"bbbbbbbb-2222-2222-2222-000000000010\" ,\n" +
                "      \"position\" : 0 , \"repetitions\" : 10 , \"weightKg\" : 12.5 ,\n" +
                "      \"isCompleted\" : true , \"restSeconds\" : 60 } ] } ] }",
            ::decodeWorkoutInput,
        )
        assertAccepts(
            "{\"id\":\"$workoutId\",\"name\":\"\",\"status\":\"active\"," +
                "\"startedAt\":\"2026-10-03T12:00:00.123Z\",\"finishedAt\":null," +
                "\"revision\":1,\"totalVolumeKg\":12.5,\"exercises\":[]}",
            ::decodeWorkoutRecord,
        )
        // Optional/default custom fields may be omitted.
        assertAccepts(validCustomInput(), ::decodeCustomExerciseInput)
        // Raw boundary decodes escapes with JSON string semantics.
        val obj = parseStrictJsonObject("{\"\\u006eame\":\"v\"}")
        assertEquals("v", obj.getString("name"))
    }

    // ---- Acceptance: mutation and durable outer wrapper duplicates. ----

    @Test
    fun mutationAndDurableOuterDuplicatesReject() {
        // Duplicate envelope member.
        assertRejectsDuplicate(
            withRootDuplicate(validMutation(), "payload", "{}"),
            ::decodeMutationEnvelope,
        )
        // Duplicate member inside the nested payload object.
        assertRejectsDuplicate(
            "{\"mutationId\":\"$mutationId\",\"type\":\"workout.start\"," +
                "\"aggregateType\":\"workout\",\"aggregateId\":\"$workoutId\"," +
                "\"localGeneration\":1,\"payload\":{\"id\":\"$workoutId\"," +
                "\"name\":\"\",\"name\":\"x\",\"startedAt\":\"$startedAt\"}}",
            ::decodeMutationEnvelope,
        )
        val wrapper = durableWrapper(validMutation())
        // Duplicate outer wrapper members.
        assertRejectsDuplicate(
            withRootDuplicate(wrapper, "payloadVersion", "1"),
            ::decodeDurableRequest,
        )
        assertRejectsDuplicate(
            withRootDuplicate(wrapper, "requestJson", "\"x\""),
            ::decodeDurableRequest,
        )
        assertRejectsDuplicate(
            withRootDuplicate(
                wrapper,
                "affectedRoots",
                "[{\"aggregateType\":\"workout\"," +
                    "\"aggregateId\":\"$workoutId\",\"localGeneration\":1}]",
            ),
            ::decodeDurableRequest,
        )
        // Duplicate inside an affectedRoots element.
        assertRejectsDuplicate(
            "{\"payloadVersion\":1,\"requestJson\":\"{}\"," +
                "\"affectedRoots\":[{\"aggregateType\":\"workout\"," +
                "\"aggregateType\":\"routine\",\"aggregateId\":\"$workoutId\"," +
                "\"localGeneration\":1}]}",
            ::decodeDurableRequest,
        )
    }

    // ---- Acceptance: embedded requestJson corruption rejects; outer scan stays shallow. ----

    @Test
    fun embeddedRequestJsonDuplicateRejects() {
        val corruptInner = "{\"mutationId\":\"$mutationId\",\"type\":\"workout.start\"," +
            "\"type\":\"routine.save\",\"aggregateType\":\"workout\"," +
            "\"aggregateId\":\"$workoutId\",\"localGeneration\":1," +
            "\"payload\":{\"id\":\"$workoutId\",\"name\":\"\",\"startedAt\":\"$startedAt\"}}"
        val wrapper = durableWrapper(corruptInner)
        // Outer wrapper keys are unique, so the shallow outer scan accepts the shell,
        // but the durable decoder parses the embedded mutation and must reject it.
        assertAccepts(wrapper, ::parseStrictJsonObject)
        assertRejectsDuplicate(wrapper, ::decodeDurableRequest)
    }

    // ---- Acceptance: byte-for-byte durable retention and push embedding. ----

    @Test
    fun validDurableRequestRetainsExactBytes() {
        // Unusual spelling: reordered keys, extra whitespace, escaped value spelling.
        val innerExact = "{ \"type\" : \"workout.start\" , \"payload\" : " +
            "{ \"startedAt\" : \"2026-10-03T12:00:00.123Z\" , \"name\" : \"A\\u0041\" , " +
            "\"id\" : \"$workoutId\" } , \"aggregateId\" : \"$workoutId\" , " +
            "\"aggregateType\" : \"workout\" , \"localGeneration\" : 1 , " +
            "\"mutationId\" : \"$mutationId\" }"
        val wrapper = "{\n  \"payloadVersion\" : 1 ,\n" +
            "  \"requestJson\" : \"${escapeForJsonString(innerExact)}\" ,\n" +
            "  \"affectedRoots\" : [ { \"aggregateType\" : \"workout\" , " +
            "\"aggregateId\" : \"$workoutId\" , \"localGeneration\" : 1 } ]\n}"
        val decoded = try {
            decodeDurableRequest(wrapper)
        } catch (e: SyncModelException) {
            fail("valid durable wrapper must decode: ${e.message}")
            throw e
        }
        assertEquals(innerExact, decoded.requestJson)
        assertEquals(1, decoded.payloadVersion)

        // Exact hand-authored expected push body: the shared mutations envelope with
        // the requestJson bytes embedded verbatim (no ID/wrapper regeneration, order kept).
        val body = encodePushMutationsBody(listOf(decoded))
        assertEquals("{\"mutations\":[" + innerExact + "]}", body)
        assertEquals("encoding must be deterministic", body, encodePushMutationsBody(listOf(decoded)))

        // Two requests keep order and exact bytes for both.
        val inner2 = validMutation(mutationId2, workoutId2)
        val decoded2 = decodeDurableRequest(durableWrapper(inner2, root = workoutId2))
        assertEquals("second requestJson retained byte-for-byte", inner2, decoded2.requestJson)
        val body2 = encodePushMutationsBody(listOf(decoded, decoded2))
        assertEquals("{\"mutations\":[" + innerExact + "," + inner2 + "]}", body2)

        // Numeric spelling is also part of frozen identity, not a normalized value.
        val numericPayload = workoutInputWithSet(weightLiteral = "2.000e+1")
        val numericInner = """{ "mutationId":"$mutationId3", "type":"workout.upsert", "aggregateType":"workout", "aggregateId":"$workoutId", "localGeneration":1, "payload":$numericPayload }"""
        val numeric = decodeDurableRequest(durableWrapper(numericInner))
        assertEquals(numericInner, numeric.requestJson)
        assertEquals("{\"mutations\":[" + numericInner + "]}", encodePushMutationsBody(listOf(numeric)))
    }

    // ---- Acceptance: unsupported version stays distinguishable. ----

    @Test
    fun unsupportedDurableVersionStaysDistinct() {
        val wrapper = durableWrapper(validMutation(), version = 999)
        try {
            decodeDurableRequest(wrapper)
            fail("expected UnsupportedDurableVersionException for unique valid v999 wrapper")
        } catch (e: UnsupportedDurableVersionException) {
            assertNotNull(e.message)
        }
    }

    // ---- Acceptance: strict syntax rejection is unchanged. ----

    @Test
    fun strictSyntaxStillRejected() {
        val badBodies = listOf(
            "[1,2]",
            "\"just a string\"",
            "123",
            "null",
            "{\"id\":\"x\"} trailing",
            "{\"a\":",
            "{\"a\":\"\\q\"}",
            "{\"a\":\"unterminated}",
            "{'a':1}",
            "{a:1}",
            "{\"a\":1,}",
            "/*comment*/{\"a\":1}",
            "{\"a\":1}//comment",
            "",
            "   ",
        )
        // NOTE (coordinator baseline fact): the raw boundary normalizes untyped tokens
        // such as NaN/Infinity/0x1/01 to strings (pre-R02a behavior); R02a must not add
        // numeric grammar or replace the parser, so no raw-boundary cases assert on them.
        // Typed model readers remain the rejection boundary (see
        // nonfiniteAndUntypedNumericsRejectAtTypedReaders).
        for (bad in badBodies) {
            try {
                parseStrictJsonObject(bad)
                fail("expected SyncModelException for malformed body: $bad")
            } catch (e: SyncModelException) {
                // Expected: strict syntax rejection unchanged.
            }
        }
        // The duplicate-key guard must not let malformed input through model routes.
        for (bad in listOf("[1,2]", "{\"id\":\"x\"} trailing")) {
            try {
                decodeWorkoutInput(bad)
                fail("expected SyncModelException for malformed workout input: $bad")
            } catch (e: SyncModelException) {
                // Expected.
            }
        }
    }

    @Test
    fun nonfiniteAndUntypedNumericsRejectAtTypedReaders() {
        // Nonfinite/untyped numeric input stays rejected at typed model readers.
        for (literal in listOf("NaN", "Infinity", "-Infinity", "0x1", "01")) {
            try {
                decodeWorkoutInput(workoutInputWithSet(weightLiteral = literal))
                fail("expected SyncModelException for weightKg $literal")
            } catch (e: SyncModelException) {
                // Expected: typed reader rejects nonfinite/untyped numeric input.
            }
        }
        // String-typed repetitions are untyped numeric input and must also reject.
        try {
            decodeWorkoutInput(workoutInputWithSet(weightLiteral = "20.0", repsLiteral = "\"8\""))
            fail("expected SyncModelException for string repetitions")
        } catch (e: SyncModelException) {
            // Expected.
        }
    }

    // ---- Acceptance: error text/cause leakage sentinels. ----

    @Test
    fun duplicateErrorTextLeaksNothing() {
        val keyFrag = "sk9fLeakKey"
        val valFrag = "sv9fLeakVal"
        val dupUnknown = "{\"id\":\"$workoutId\",\"name\":\"ok\",\"status\":\"active\"," +
            "\"startedAt\":\"$startedAt\",\"exercises\":[]," +
            "\"$keyFrag\":\"${valFrag}1\",\"$keyFrag\":\"${valFrag}2\"}"
        try {
            decodeWorkoutInput(dupUnknown)
            fail("expected SyncModelException for sentinel duplicate")
        } catch (e: SyncModelException) {
            assertNull("duplicate-key failure must not retain a raw parser cause", e.cause)
            val haystack = (e.message ?: "") + "\n" + e.toString()
            assertTrue("message must not contain the sentinel key", !haystack.contains(keyFrag))
            assertTrue("message must not contain the sentinel value", !haystack.contains(valFrag))
            assertTrue("message must not contain the raw body", !haystack.contains(dupUnknown))
        }
        val dupKnown = "{\"id\":\"$workoutId\",\"name\":\"${valFrag}A\"," +
            "\"name\":\"${valFrag}B\",\"status\":\"active\"," +
            "\"startedAt\":\"$startedAt\",\"exercises\":[]}"
        try {
            decodeWorkoutInput(dupKnown)
            fail("expected SyncModelException for sentinel value duplicate")
        } catch (e: SyncModelException) {
            assertNull(e.cause)
            val haystack = (e.message ?: "") + "\n" + e.toString()
            assertTrue(!haystack.contains(valFrag))
            assertTrue(!haystack.contains(dupKnown))
            assertTrue(!haystack.contains(workoutId))
        }
    }

    // ---- Acceptance: representative long/deep data without new caps or rescan blowup. ----

    @Test
    fun longAndDeepDataBehavesDeterministically() {
        // Long valid input: 200 well-formed exercises with distinct deterministic UUIDs.
        val exercises = StringBuilder()
        repeat(200) { index ->
            val suffix = index.toString().padStart(12, '0')
            val exId = "aaaaaaaa-1111-1111-1111-$suffix"
            if (index > 0) exercises.append(",")
            exercises.append("{\"id\":\"$exId\",\"exerciseId\":\"$customId\",")
            exercises.append("\"position\":$index,\"notes\":\"\",\"restDurationSeconds\":30,")
            if (index == 0) {
                exercises.append(
                    "\"sets\":[{\"id\":\"bbbbbbbb-2222-2222-2222-000000000000\"," +
                        "\"position\":0,\"repetitions\":8,\"weightKg\":20.0," +
                        "\"isCompleted\":true,\"restSeconds\":90}]}",
                )
            } else {
                exercises.append("\"sets\":[]}")
            }
        }
        val longValid = "{\"id\":\"$workoutId\",\"name\":\"long\",\"status\":\"active\"," +
            "\"startedAt\":\"$startedAt\",\"exercises\":[$exercises]}"
        assertAccepts(longValid, ::decodeWorkoutInput)
        // Deterministic: decoding twice succeeds identically (no stateful scan).
        assertAccepts(longValid, ::decodeWorkoutInput)

        // Same long shape with a duplicate in the final element rejects.
        val dupSuffix = 199.toString().padStart(12, '0')
        val dupTail = "{\"id\":\"aaaaaaaa-1111-1111-1111-$dupSuffix\"," +
            "\"exerciseId\":\"$customId\",\"position\":199,\"position\":200," +
            "\"notes\":\"\",\"restDurationSeconds\":30,\"sets\":[]}"
        val tailStart = longValid.lastIndexOf("{\"id\":\"aaaaaaaa-1111-1111-1111-$dupSuffix\"")
        check(tailStart >= 0) { "long fixture must contain the final element" }
        // Drop the final element plus the two closers of the exercises array and root object.
        val longDup = longValid.substring(0, tailStart) + dupTail + "]}"
        assertRejectsDuplicate(longDup, ::decodeWorkoutInput)

        // Deep valid nesting accepts at the raw boundary.
        var deepValid = "{\"k24\":1}"
        for (depth in 23 downTo 0) {
            deepValid = "{\"k$depth\":$deepValid}"
        }
        assertAccepts(deepValid, ::parseStrictJsonObject)
        // Deep duplicate at the innermost object rejects.
        var deepDup = "{\"dup\":1,\"dup\":2}"
        for (depth in 23 downTo 0) {
            deepDup = "{\"k$depth\":$deepDup}"
        }
        assertRejectsDuplicate(deepDup, ::parseStrictJsonObject)
    }

    // ---- Acceptance: raw boundary parses valid JSON exactly as before. ----

    @Test
    fun rawBoundaryParsesValidJsonUnchanged() {
        val obj = parseStrictJsonObject(validCustomRecord())
        assertEquals(customId, obj.getString("id"))
        assertEquals("Custom", obj.getString("name"))
        assertEquals(1, obj.getInt("revision"))
        assertTrue(obj.getBoolean("isUserCreated"))
    }
}
