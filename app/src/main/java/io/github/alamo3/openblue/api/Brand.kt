package io.github.alamo3.openblue.api

/**
 * The Hyundai Motor Group brands that share the Canadian Bluelink API ("tods"): the same paths,
 * and payloads on different hosts. Kia uses the web-portal request profile documented by
 * hyundai_kia_connect_api and sends no brand header to Kia Connect.
 */
enum class Brand(
    val label: String,
    val company: String,
    /** What the owner calls the service, for text shown in the app. */
    val serviceName: String,
    val host: String,
    /** The "brand" request header the official app sends; null to leave it out. */
    val brandHeader: String?,
    val clientId: String,
    val clientSecret: String,
    /** Hyundai's Bluelink Store (features on demand). */
    val hasStore: Boolean,
    /** Hyundai's in-app charging: public chargers, trip planning and paid-session history. */
    val hasInAppCharging: Boolean,
) {
    HYUNDAI(
        "Hyundai", "Hyundai", "Bluelink", "mybluelink.ca", "H",
        "HATAHSPACA0232141ED9722C67715A0B", "CLISCR01AHSPA", hasStore = true, hasInAppCharging = true,
    ),
    KIA(
        "Kia", "Kia", "Kia Connect", "kiaconnect.ca", null,
        "HATAHSPACA0232141ED9722C67715A0B", "CLISCR01AHSPA", hasStore = false, hasInAppCharging = false,
    );

    val baseUrl: String get() = "https://$host/tods/api/"
}
