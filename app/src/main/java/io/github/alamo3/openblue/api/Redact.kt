package io.github.alamo3.openblue.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Strips what identifies a car or its owner from Bluelink data before it's shown for copying
 * (Settings > Raw vehicle data), so it can be shared to get help: IDs, the VIN's serial part,
 * names, contact details, addresses and where the car is. What the car can do and its status stay.
 */
object Redact {
    private const val HIDDEN = "(removed)"

    /** Keys (lower case) whose values are removed outright. */
    private val SECRET = setOf(
        "vehicleid", "regid", "registrationid", "profileid", "masterprofileid", "userid", "loginid", "username",
        "email", "emailaddress", "phone", "phonenumber", "mobile", "mobilenumber", "cellphone",
        "firstname", "lastname", "fullname", "nickname", "ownername",
        "address", "street", "streetaddress", "postalcode", "zip", "zipcode", "city", "province", "state",
        "lat", "lon", "lng", "latitude", "longitude", "alt", "altitude", "coord", "geocoord",
        "accesstoken", "refreshtoken", "token", "pauth", "pin", "transactionid", "deviceid",
        "photourl", "licenseplate", "plate", "dealercode", "preferreddealer", "imei", "iccid", "msisdn",
    )

    fun json(e: JsonElement): JsonElement {
        val vins = HashSet<String>()
        collectVins(e, vins)
        return walk(e, vins)
    }

    /** The VIN with its serial number (last six characters) hidden; the rest only says what model it is. */
    fun vin(v: String): String = if (v.length == 17) v.take(11) + "******" else HIDDEN

    private fun collectVins(e: JsonElement, out: MutableSet<String>) {
        when (e) {
            is JsonObject -> e.forEach { (k, v) ->
                if (k.equals("vin", true) && v is JsonPrimitive && v.isString) out += v.content
                collectVins(v, out)
            }
            is JsonArray -> e.forEach { collectVins(it, out) }
            else -> Unit
        }
    }

    private fun walk(e: JsonElement, vins: Set<String>): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.mapValues { (k, v) ->
            val key = k.lowercase()
            when {
                v is JsonNull -> v
                key == "vin" && v is JsonPrimitive -> JsonPrimitive(vin(v.content))
                key in SECRET && (v is JsonPrimitive || key in setOf("coord", "geocoord", "address")) -> JsonPrimitive(HIDDEN)
                else -> walk(v, vins)
            }
        })
        is JsonArray -> JsonArray(e.map { walk(it, vins) })
        is JsonPrimitive -> if (e.isString && vins.any { it.isNotEmpty() && e.content.contains(it) }) {
            JsonPrimitive(vins.fold(e.content) { s, v -> if (v.isEmpty()) s else s.replace(v, vin(v)) })
        } else e
    }
}
