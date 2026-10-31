package com.dragosfotache.gymtracka.data.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dragosfotache.gymtracka.data.sync.codec.decodeWorkoutInput
import com.dragosfotache.gymtracka.data.sync.codec.parseStrictJsonObject
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Android platform smoke test for raw JSON duplicate-key rejection.
 */
@RunWith(AndroidJUnit4::class)
class StrictDuplicateKeyAndroidTest {

    private val workoutId = "11111111-1111-1111-1111-111111111111"
    private val customId = "33333333-3333-3333-3333-333333333333"
    private val startedAt = "2026-10-03T12:00:00Z"

    private fun duplicateGuardReferenceText(): String {
        try {
            parseStrictJsonObject("{\"ref\":1,\"ref\":2}")
        } catch (e: SyncModelException) {
            assertNull(e.cause)
            val message = e.message ?: throw AssertionError("guard reference failure must carry fixed safe text")
            assertTrue(message.isNotBlank())
            return message
        }
        fail("reference duplicate must reject")
        throw IllegalStateException("unreachable")
    }

    private fun assertRejects(jsonText: String, decode: (String) -> Any?) {
        val reference = duplicateGuardReferenceText()
        try {
            decode(jsonText)
        } catch (e: SyncModelException) {
            assertNull("duplicate-key failure must not retain a raw parser cause", e.cause)
            assertEquals(
                "duplicate-key failure must carry the fixed guard text",
                reference,
                e.message,
            )
            return
        }
        fail("expected SyncModelException for duplicate keys in: $jsonText")
    }

    private fun assertAccepts(jsonText: String, decode: (String) -> Any?) {
        try {
            assertNotNull(decode(jsonText))
        } catch (e: SyncModelException) {
            fail("valid JSON must not be rejected: $jsonText (was: ${e.message})")
            throw e
        }
    }

    @Test
    fun exactRootDuplicateRejects() {
        assertRejects(
            "{\"id\":\"$workoutId\",\"name\":\"\",\"name\":\"x\"," +
                "\"status\":\"active\",\"startedAt\":\"$startedAt\",\"exercises\":[]}",
            ::decodeWorkoutInput,
        )
        assertRejects(
            "{\"id\":\"$workoutId\",\"name\":\"\",\"name\":\"\"," +
                "\"status\":\"active\",\"startedAt\":\"$startedAt\",\"exercises\":[]}",
            ::decodeWorkoutInput,
        )
    }

    @Test
    fun escapedEquivalentDuplicateRejects() {
        assertRejects(
            "{\"id\":\"$workoutId\",\"name\":\"a\",\"\\u006eame\":\"b\"," +
                "\"status\":\"active\",\"startedAt\":\"$startedAt\",\"exercises\":[]}",
            ::decodeWorkoutInput,
        )
        assertRejects("{\"a/b\":1,\"a\\/b\":2}", ::parseStrictJsonObject)
    }

    @Test
    fun nestedDuplicateRejects() {
        assertRejects(
            "{\"id\":\"$workoutId\",\"name\":\"\",\"status\":\"active\"," +
                "\"startedAt\":\"$startedAt\",\"exercises\":[{\"id\":\"$customId\"," +
                "\"exerciseId\":\"$customId\",\"position\":0,\"position\":1,\"notes\":\"\"," +
                "\"restDurationSeconds\":30,\"sets\":[]}]}",
            ::decodeWorkoutInput,
        )
        assertRejects("{\"l0\":{\"l1\":{\"x\":1,\"x\":2}}}", ::parseStrictJsonObject)
    }

    @Test
    fun distinctSiblingsAccept() {
        assertAccepts("{\"a\":{\"x\":1},\"b\":{\"x\":2}}", ::parseStrictJsonObject)
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
    }

    @Test
    fun escapedStringValuesAccept() {
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
    }

    @Test
    fun duplicateErrorTextStaysSafe() {
        val keyFrag = "sk9fLeakKey"
        val dup = "{\"id\":\"$workoutId\",\"name\":\"ok\",\"status\":\"active\"," +
            "\"startedAt\":\"$startedAt\",\"exercises\":[]," +
            "\"$keyFrag\":\"a\",\"$keyFrag\":\"b\"}"
        try {
            decodeWorkoutInput(dup)
            fail("expected SyncModelException for sentinel duplicate")
        } catch (e: SyncModelException) {
            assertNull(e.cause)
            val haystack = (e.message ?: "") + "\n" + e.toString()
            assertTrue(!haystack.contains(keyFrag))
            assertTrue(!haystack.contains(dup))
        }
    }
}
