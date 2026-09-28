package com.dragosfotache.gymtracka.data.sync

import com.dragosfotache.gymtracka.data.sync.codec.decodeWorkoutInput
import com.dragosfotache.gymtracka.data.sync.model.SyncModelException
import java.util.UUID
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Raw-body strictness: only strict JSON objects reach the field readers. */
class StrictJsonBodyTest {

    private fun minimalBody(): String {
        val root = UUID.randomUUID().toString()
        val entry = UUID.randomUUID().toString()
        val catalogue = UUID.randomUUID().toString()
        val set = UUID.randomUUID().toString()
        return """
        {
          "id": "$root",
          "name": "Active",
          "status": "active",
          "startedAt": "2026-09-24T14:00:00Z",
          "exercises": [
            {
              "id": "$entry",
              "exerciseId": "$catalogue",
              "position": 0,
              "notes": "",
              "restDurationSeconds": 30,
              "sets": [
                {"id": "$set", "position": 0, "repetitions": 5, "weightKg": 50.0,
                 "isCompleted": true, "restSeconds": null}
              ]
            }
          ]
        }
        """.trimIndent()
    }

    private fun assertBodyRejected(body: String) {
        try {
            decodeWorkoutInput(body)
        } catch (error: SyncModelException) {
            assertTrue(
                "Error must not echo the raw body: ${error.message}",
                error.message == null || !error.message!!.contains("SNEAKY_MARKER"),
            )
            return
        }
        fail("Expected strict body rejection")
    }

    @Test
    fun strictBodies_decodeWhileLenientSyntaxIsRejected() {
        // The valid body decodes normally.
        decodeWorkoutInput(minimalBody())

        val marker = "SNEAKY_MARKER"
        assertBodyRejected(minimalBody() + " trailing $marker")
        assertBodyRejected("{'id': '$marker'}")
        assertBodyRejected("{id: '$marker'}")
        assertBodyRejected("""{"id": "$marker",}""")
        assertBodyRejected("[]")
        assertBodyRejected("\"$marker\"")
        assertBodyRejected("42")
        assertBodyRejected("{")
        assertBodyRejected("")
        assertBodyRejected("   ")
    }
}
