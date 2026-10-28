package io.github.alamo3.openblue.api

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.math.roundToInt

/*
 * Hyundai's in-app charging ("iac/*" on mybluelink.ca), parsed loosely: field names follow the
 * official app's models, and anything missing just comes back null.
 */

private fun flag(v: String?) = v != null && (v.equals("Y", true) || v == "1" || v.equals("true", true))

/** What the account and car can use (iac/serviceInfo). */
data class ChargeService(
    val inAppCharging: Boolean,
    val remoteStart: Boolean,
    val stationSearch: Boolean,
    val plugAndCharge: Boolean,
    val routePlanning: Boolean,
    val cardRegistered: Boolean,
    val cards: List<PayCard>,
    val plans: List<ChargePlan>,
) {
    companion object {
        fun from(r: JsonObject): ChargeService {
            val ind = "iacServiceInd"
            return ChargeService(
                inAppCharging = flag(r.str("$ind.inAppChargingInd")),
                remoteStart = flag(r.str("$ind.chargingStartInd")),
                stationSearch = flag(r.str("$ind.chargingStationSearchInd")),
                plugAndCharge = flag(r.str("$ind.pncMenuInd")),
                routePlanning = flag(r.str("$ind.routePlanningInd")),
                cardRegistered = r.bool("icpServiceData.usrInfo.cardRegisterInfo") == true,
                cards = r.arr("payCardList").map { PayCard.from(it) },
                plans = r.arr("icpSubscriptInfo").map { ChargePlan.from(it) },
            )
        }
    }
}

data class PayCard(val name: String?, val issuer: String?, val number: String?) {
    /** "Visa ···· 1234" style, from whatever the server gives (it sends numbers masked). */
    val label: String
        get() {
            val last4 = number?.filter { it.isDigit() }?.takeLast(4)
            return listOfNotNull(issuer ?: name, last4?.takeIf { it.length == 4 }?.let { "···· $it" }).joinToString(" ").ifEmpty { "Card" }
        }

    companion object {
        fun from(e: JsonElement) = PayCard(e.str("cardName"), e.str("cardIssuerName"), e.str("cardNumber"))
    }
}

data class ChargePlan(val name: String, val status: String?, val start: String?, val end: String?) {
    companion object {
        fun from(e: JsonElement) = ChargePlan(
            name = e.str("subscriptionName") ?: "Charging plan",
            status = e.str("subscriptionStatCd"),
            start = e.str("subscriptionStartDt"),
            end = e.str("subscriptionEndDt"),
        )
    }
}

/** How fast a station can charge, from the server's speed names. */
enum class ChargeSpeed(val code: String, val label: String) {
    SLOW("slow", "Level 2"),
    FAST("fast", "DC fast"),
    RAPID("rapid", "DC fast"),
    ULTRA("ultra", "DC ultra-fast");

    companion object {
        fun of(s: String?) = entries.firstOrNull { it.code.equals(s, true) || it.name.equals(s, true) }
    }
}

data class Connector(val types: List<String>, val kw: Double?, val status: String?, val speed: ChargeSpeed?)

data class Charger(val id: String?, val reference: String?, val connectors: List<Connector>, val fee: String?)

/** A charging station from iac/csList or iac/csDetail. */
data class Station(
    val id: String,
    val name: String,
    val address: String?,
    val network: String?,
    val lat: Double,
    val lon: Double,
    val distanceKm: Double?,
    val maxKw: Double?,
    val speed: ChargeSpeed?,
    val available: Int?,
    val total: Int?,
    val outOfService: Int,
    val price: String?,
    val phone: String?,
    val chargers: List<Charger>,
) {
    /** Free chargers, all busy, or unknown/out of order (for the map pin colour). */
    val state: Int
        get() = when {
            available == null || total == null || total == 0 -> 2
            available > 0 -> 0
            outOfService >= total -> 2
            else -> 1
        }

    val availability: String?
        get() = if (available != null && total != null && total > 0) "$available of $total free" else null

    val power: String?
        get() = maxKw?.let { "${it.roundToInt()} kW" }

    companion object {
        fun from(e: JsonElement): Station? {
            val id = e.str("id") ?: e.str("poiId") ?: return null
            val lat = e.dbl("coordinates.center.lat") ?: e.dbl("coordinates.guidance.lat") ?: return null
            val lon = e.dbl("coordinates.center.lon") ?: e.dbl("coordinates.guidance.lon") ?: return null
            val lang = e.arr("languageResources").let { list ->
                list.firstOrNull { it.str("language")?.startsWith("en", true) == true } ?: list.firstOrNull()
            }
            val status = e.arr("status")
            fun sum(key: String) = status.mapNotNull { it.int(key) }.takeIf { it.isNotEmpty() }?.sum()
            val chargers = e.arr("chargers").map { c ->
                Charger(
                    id = c.str("id"),
                    reference = c.str("physicalReference"),
                    connectors = c.arr("connectors").map { k ->
                        Connector(
                            types = k.arr("connectorTypes").mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content },
                            kw = k.dbl("chargingPower"),
                            status = k.str("connectorStatus"),
                            speed = ChargeSpeed.of(k.str("chargingSpeed")),
                        )
                    },
                    fee = c.str("chargingFee"),
                )
            }
            val tariffText = e.arr("tariff").firstNotNullOfOrNull { t ->
                t.arr("tariffAltText").let { alt ->
                    (alt.firstOrNull { it.str("language")?.startsWith("en", true) == true } ?: alt.firstOrNull())?.str("text")
                }
            }
            val kw = e.dbl("chargingPower") ?: chargers.flatMap { it.connectors }.mapNotNull { it.kw }.maxOrNull()
            return Station(
                id = id,
                name = lang?.str("data.name") ?: "Charging station",
                address = lang?.str("data.address"),
                network = lang?.arr("data.providers.operation")?.firstOrNull()?.let { it.str("name") ?: it.str("brandName") },
                lat = lat,
                lon = lon,
                distanceKm = e.dbl("distance"),
                maxKw = kw?.takeIf { it > 0 },
                speed = ChargeSpeed.of(e.str("chargingSpeed")) ?: status.firstNotNullOfOrNull { ChargeSpeed.of(it.str("chargingSpeed")) },
                available = sum("numberOfAvailableConnectors"),
                total = sum("numberOfConnectors"),
                outOfService = (sum("numberOfErrorConnectors") ?: 0) + (sum("numberOfDisabledConnectors") ?: 0),
                price = tariffText ?: chargers.firstNotNullOfOrNull { it.fee?.takeIf { f -> f.isNotBlank() } },
                phone = e.str("phoneNumber"),
                chargers = chargers,
            )
        }
    }
}

/** A charging session paid through the app (iac/orderInfo). */
data class ChargeOrder(
    val id: String?,
    val started: String?,
    val stopped: String?,
    val place: String?,
    val address: String?,
    val kwh: Double?,
    val total: Double?,
    val currency: String?,
    val card: String?,
    val invoiceUrl: String?,
    val status: String?,
) {
    companion object {
        fun from(e: JsonElement) = ChargeOrder(
            id = e.str("ordNo") ?: e.str("trId"),
            started = e.str("startDateTime") ?: e.str("ordDate"),
            stopped = e.str("stopDateTime"),
            place = e.str("merchantNm") ?: e.str("brndNm") ?: e.str("ordNm"),
            address = listOfNotNull(e.str("addrStreet"), e.str("addrCity")).filter { it.isNotBlank() }.joinToString(", ").ifEmpty { null },
            kwh = e.dbl("totalEnergy"),
            total = e.dbl("totalPrice") ?: e.dbl("price"),
            currency = e.str("curr"),
            card = e.str("last4Creditcard")?.takeIf { it.isNotBlank() },
            invoiceUrl = e.str("invoiceUrl")?.takeIf { it.startsWith("http") },
            status = e.str("ordStatCd"),
        )
    }
}

data class ChargeHistory(val orders: List<ChargeOrder>, val kwh: Double?, val total: Double?) {
    companion object {
        fun from(r: JsonObject) = ChargeHistory(
            orders = r.arr("resultData").map { ChargeOrder.from(it) }.sortedByDescending { it.started.orEmpty() },
            kwh = r.dbl("resultEnergy"),
            total = r.dbl("resultPrice"),
        )
    }
}
