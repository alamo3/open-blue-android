package io.github.alamo3.openblue.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** Walks a dotted path ("result.vehicles.0.vin") through objects and arrays. */
fun JsonElement?.at(path: String): JsonElement? {
    var cur: JsonElement? = this
    for (part in path.split('.')) {
        val node = cur
        cur = when (node) {
            is JsonObject -> node[part]
            is JsonArray -> part.toIntOrNull()?.let { node.getOrNull(it) }
            else -> null
        }
        if (cur == null || cur is JsonNull) return null
    }
    return cur
}

fun JsonElement?.str(path: String): String? = (at(path) as? JsonPrimitive)?.contentOrNull

fun JsonElement?.int(path: String): Int? = (at(path) as? JsonPrimitive)?.let { p ->
    p.intOrNull ?: p.doubleOrNull?.toInt() ?: p.contentOrNull?.toIntOrNull()
}

fun JsonElement?.dbl(path: String): Double? = (at(path) as? JsonPrimitive)?.let { p ->
    p.doubleOrNull ?: p.contentOrNull?.toDoubleOrNull()
}

/** Bluelink mixes true/false with 0/1 for flags; accept both. */
fun JsonElement?.bool(path: String): Boolean? = (at(path) as? JsonPrimitive)?.let { p ->
    p.booleanOrNull ?: p.intOrNull?.let { it != 0 }
}

fun JsonElement?.arr(path: String): JsonArray = (at(path) as? JsonArray) ?: JsonArray(emptyList())
