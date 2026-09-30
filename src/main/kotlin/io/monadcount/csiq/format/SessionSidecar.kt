package io.monadcount.csiq.format

/**
 * The `metadata.json` that sits beside a capture in the archive.
 *
 * An S3 session directory holds three things: the container, the lossless raw
 * stream, and this. It matters for two reasons that the container alone cannot
 * cover.
 *
 *  * **A raw driver stream has no monitor width.** The 272-byte header does not
 *    carry one, so a `capture.raw` opened on its own can only report "unknown".
 *    `radio.width` here is the missing session constant.
 *  * **An embedded session block can be stale.** A container written before
 *    csid 0.2.0 re-read the sidecar at export and a segmented capture leaves
 *    that file at `status: capturing` until the export lands, so the embedded
 *    copy says `capturing` forever. This file states the real outcome.
 *
 * The schema is permissive and opaque to the container, so nothing here is
 * required and every accessor may return null.
 */
class SessionSidecar(val json: JsonValue) {

    val schema: String? get() = json.path("schema").asString()
    val sessionId: String? get() = json.path("session_id").asString()
    val runId: String? get() = json.path("run_id").asString()
    val experiment: String? get() = json.path("experiment").asString()

    /**
     * The profile's tag, copied verbatim from the Ansible inventory.
     *
     * Every session of a profile carries the same string, so it names the
     * profile and never the experiment a session belongs to.
     */
    val tag: String? get() = json.path("tag").asString()

    val status: String? get() = json.path("status").asString()
    val startedAt: String? get() = json.path("started_at").asString()
    val endedAt: String? get() = json.path("ended_at").asString()

    /** The configured monitor width, which the raw header does not carry. */
    val monitorWidth: Width?
        get() = json.path("radio", "width").asString()?.let { text ->
            Width.entries.firstOrNull { it.label.equals(text, ignoreCase = true) }
        }

    val band: String? get() = json.path("radio", "band").asString()
    val channel: Long? get() = json.path("radio", "channel").asLong()
    val controlFreqMhz: Long? get() = json.path("radio", "control_freq_mhz").asLong()
    val intervalUs: Long? get() = json.path("radio", "interval_us").asLong()
    val hostname: String? get() = json.path("environment", "hostname").asString()
    val csidVersion: String? get() = json.path("environment", "csid_version").asString()
    val filterFingerprint: String? get() = json.path("filter", "fingerprint").asString()

    /** Records the capturer says it wrote, for cross-checking the index. */
    val summaryRecords: Long? get() = json.path("summary", "records").asLong()
    val summaryEmptyRecords: Long? get() = json.path("summary", "empty_records").asLong()
    val summaryMeanRateHz: Double? get() = json.path("summary", "mean_rate_hz").asDouble()
    val summaryCaptureBytes: Long? get() = json.path("summary", "capture_bytes").asLong()

    /** True when this directory is one segment of a longer capture. */
    val isSegment: Boolean get() = SEGMENT.containsMatchIn(sessionId ?: "")

    /** The segment number, when this is a segment. */
    val segmentNumber: Int?
        get() = SEGMENT.find(sessionId ?: "")?.groupValues?.get(1)?.toIntOrNull()

    /** The base session this segment belongs to. */
    val baseSessionId: String?
        get() = sessionId?.let { SEGMENT.replace(it, "") }?.takeIf { it != sessionId }

    companion object {
        /** The file name the archive uses, beside every capture. */
        const val FILE_NAME = "metadata.json"

        private val SEGMENT = Regex("-seg(\\d{4})$")

        fun parse(text: String): SessionSidecar? = try {
            SessionSidecar(Json.parse(text))
        } catch (_: JsonParseException) {
            null
        }
    }
}
