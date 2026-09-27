package com.dragosfotache.gymtracka.data.gym

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GymTypeConvertersTest {
    private val converters = GymTypeConverters()

    @Test
    fun instructions_roundTripThroughJson() {
        val instructions = listOf("Brace", "Press", "Return slowly")

        val encoded = converters.instructionsToJson(instructions)

        assertEquals(instructions, converters.jsonToInstructions(encoded))
    }

    @Test
    fun nullInstructions_roundTripAsNull() {
        assertNull(converters.instructionsToJson(null))
        assertNull(converters.jsonToInstructions(null))
    }
}
