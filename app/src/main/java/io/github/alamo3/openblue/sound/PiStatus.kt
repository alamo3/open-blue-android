package io.github.alamo3.openblue.sound

/** The Pi's own sound engine (virtual-car-sound --native-sound), as its status reports it. */
data class PiNative(
    val gears: Boolean,
    /** The rest come from the player; null / 0 until it reports. */
    val gear: Int? = null,
    val rpm: Int = 0,
    val kmh: Float = 0f,
    val limiter: Boolean = false,
    /** The test drive's stage while one runs, else null. */
    val testDrive: String? = null,
    val underruns: Int = 0,
    val cpu: Float = 0f,
    val live: Boolean = false,
    /** Eased down for a steady cruise (cruise settle). */
    val settled: Boolean = false,
)

/** The Pi's status (once a second, and after each command). */
data class PiStatus(
    /** The sound it plays (with [native]) or its Python voice. */
    val voice: String,
    val volume: Float,
    val carLive: Boolean,
    /** The last problem it reported ("" when all is well). */
    val message: String,
    /** Cruise settle on (the Pi's saved "cruise quiet"). */
    val cruiseSettle: Boolean = true,
    /**
     * With the Pi's separate Bluetooth service: what its engine is doing -- "running", or
     * systemd's word for it while it isn't reporting ("activating": starting or updating,
     * "failed", "inactive"). Null from a Pi without that service.
     */
    val engineState: String? = null,
    /** Set when the Pi plays the sound itself with the native engine: then it can be remote-controlled. */
    val native: PiNative?,
)

/** The Pi's status JSON (ble_control.py), or null if it isn't one. */
internal fun parsePiStatus(json: String): PiStatus? = runCatching {
    val o = org.json.JSONObject(json)
    val n = o.optJSONObject("nat")
    PiStatus(
        voice = o.optString("v"),
        volume = o.optDouble("vol", 1.0).toFloat(),
        carLive = o.optBoolean("car"),
        message = o.optString("msg"),
        cruiseSettle = o.optBoolean("cq", true),
        engineState = o.optString("svc").takeIf { it.isNotEmpty() },
        native = n?.let {
            PiNative(
                gears = it.optBoolean("gears", true),
                gear = if (it.has("g")) it.optInt("g") else null,
                rpm = it.optInt("rpm"),
                kmh = it.optDouble("kmh", 0.0).toFloat(),
                limiter = it.optInt("lim") != 0,
                testDrive = it.optString("td").takeIf { s -> s.isNotEmpty() },
                underruns = it.optInt("ur"),
                cpu = it.optDouble("cpu", 0.0).toFloat(),
                live = it.optInt("live") != 0,
                settled = it.optInt("cs") != 0,
            )
        },
    )
}.getOrNull()
