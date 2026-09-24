package com.dragosfotache.gymtracka.data.gym

import androidx.room.TypeConverter
import org.json.JSONArray

class GymTypeConverters {
    @TypeConverter
    fun instructionsToJson(instructions: List<String>?): String? =
        instructions?.let(::JSONArray)?.toString()

    @TypeConverter
    fun jsonToInstructions(json: String?): List<String>? = json?.let { value ->
        val array = JSONArray(value)
        List(array.length()) { index -> array.getString(index) }
    }
}
