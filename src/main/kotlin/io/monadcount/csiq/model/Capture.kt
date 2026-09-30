package io.monadcount.csiq.model

import io.monadcount.csiq.format.ByteSource
import io.monadcount.csiq.format.CsiRecord
import io.monadcount.csiq.format.CsiqHeader
import io.monadcount.csiq.format.CsiqReader
import io.monadcount.csiq.format.Compression
import io.monadcount.csiq.format.FtmUnwrapper
import io.monadcount.csiq.format.Payload
import io.monadcount.csiq.format.RawStream
import io.monadcount.csiq.format.ScanEnd
import io.monadcount.csiq.format.SessionSidecar
import io.monadcount.csiq.format.TlvCodec
import io.monadcount.csiq.format.Width
import io.monadcount.csiq.format.asLong
import io.monadcount.csiq.format.asString
import io.monadcount.csiq.format.path

/** A capture, indexed and ready to view. */
class Capture(
    val source: ByteSource,
    /** What the bytes turned out to be, once any compression was undone. */
    val payload: Payload,
    /** The codec the file was wrapped in, or [Compression.NONE]. */
    val compression: Compression,
    /** Present for a container. A raw driver stream has no header. */
    val header: CsiqHeader?,
    /** The `metadata.json` beside the capture in the archive, when there is one. */
    val sidecar: SessionSidecar?,
    val index: CaptureIndex,
    val waterfall: List<WaterfallLayer>,
    val scanEnd: ScanEnd,
    val health: CaptureHealth,
    /** Wall-clock milliseconds the indexing pass took. */
    val indexMillis: Long,
) {
    val recordCount: Int get() = index.size

    /** Read one record in full, including the bytes the index does not keep. */
    fun readRecord(row: Int, keepRawTlvs: Boolean = false): CsiRecord? {
        if (row !in 0 until index.size) return null
        return when (payload) {
            Payload.RAW_DRIVER_STREAM -> readRawRecord(row)
            else -> CsiqReader.readRecordAt(source, index.offset[row], keepRawTlvs)
        }
    }

    private fun readRawRecord(row: Int): CsiRecord? {
        var out: CsiRecord? = null
        RawStream.scan(source, index.offset[row]) { msg ->
            out = RawStream.parseRecord(msg.hdr, msg.csi, index.monitorWidth)
            false
        }
        return out
    }

    /**
     * Where the lifecycle should be read from.
     *
     * A container written before csid 0.2.0 embeds `status: capturing` forever,
     * because the export re-read a sidecar that a segmented capture leaves at
     * that value until the export lands. When the sibling file disagrees, it is
     * the one that describes the finished capture.
     */
    val lifecycleIsStale: Boolean
        get() {
            val embedded = header?.session.path("status").asString() ?: return false
            val onDisk = sidecar?.status ?: return false
            return embedded == "capturing" && onDisk != "capturing"
        }

    /** Records the capturer claimed, against records this reader found. */
    val recordCountMismatch: Long?
        get() {
            val claimed = sidecar?.summaryRecords ?: return null
            return if (claimed == recordCount.toLong()) null else claimed - recordCount
        }
}

/**
 * What the capture says about itself, derived from the index.
 *
 * Every number here is counted, not estimated, and each one answers a question
 * that has cost this project a night of measurement at least once.
 */
class CaptureHealth(
    val records: Int,
    /** Duration on the baseband clock, the one immune to an NTP step. */
    val ftmSpanSeconds: Double,
    /** Duration on the host wallclock, which an NTP step moves. */
    val wallSpanSeconds: Double?,
    val meanRateHz: Double?,
    val allZeroRecords: Int,
    val staleChainRecords: Int,
    val geometryBadRecords: Int,
    /** Records with no `MONO_US`: the node's own transmissions, looped back. */
    val ownTransmissionRecords: Int,
    /** Records whose RSSI was stored as a positive magnitude. */
    val positiveRssiRecords: Int,
    val unknownTlvRecords: Int,
    val vendorHdrRecords: Int,
    val nodeStateRecords: Int,
    /** Places where the driver's report counter skipped. */
    val seqGaps: Int,
    /** Reports the counter says were dropped. Detectable only below 256 at a time. */
    val seqDroppedReports: Long,
    val toneCounts: List<Pair<Int, Int>>,
    val distinctSourceMacs: Int,
    val rssiRange: Pair<Short, Short>?,
    val monitorWidth: Width,
) {
    val allZeroShare: Double get() = if (records == 0) 0.0 else allZeroRecords.toDouble() / records
    val staleChainShare: Double get() = if (records == 0) 0.0 else staleChainRecords.toDouble() / records

    companion object {
        fun of(index: CaptureIndex): CaptureHealth {
            val n = index.size
            if (n == 0) {
                return CaptureHealth(
                    0, 0.0, null, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0L,
                    emptyList(), 0, null, index.monitorWidth,
                )
            }
            var allZero = 0
            var stale = 0
            var badGeom = 0
            var own = 0
            var positive = 0
            var unknown = 0
            var vendor = 0
            var nodeState = 0
            var rssiMin = Short.MAX_VALUE
            var rssiMax = Short.MIN_VALUE
            val macs = HashSet<Long>()
            for (i in 0 until n) {
                if (index.flag(i, CaptureIndex.FLAG_ALL_ZERO)) allZero++
                if (index.flag(i, CaptureIndex.FLAG_STALE_CHAIN)) stale++
                if (index.flag(i, CaptureIndex.FLAG_GEOMETRY_BAD)) badGeom++
                if (!index.flag(i, CaptureIndex.FLAG_MONO_PRESENT)) own++
                if (index.flag(i, CaptureIndex.FLAG_RSSI_POSITIVE)) positive++
                if (index.flag(i, CaptureIndex.FLAG_UNKNOWN_TLV)) unknown++
                if (index.flag(i, CaptureIndex.FLAG_VENDOR_HDR)) vendor++
                if (index.flag(i, CaptureIndex.FLAG_NODE_STATE)) nodeState++
                macs.add(index.srcMac[i])
                for (r in shortArrayOf(index.rssi0[i], index.rssi1[i])) {
                    if (r == CaptureIndex.RSSI_ABSENT) continue
                    if (r < rssiMin) rssiMin = r
                    if (r > rssiMax) rssiMax = r
                }
            }

            // The report counter wraps every 256, so a gap is detectable modulo
            // 256 and only for gaps smaller than 256.
            var gaps = 0
            var dropped = 0L
            for (i in 1 until n) {
                val d = ((index.seq[i].toInt() and 0xFF) - (index.seq[i - 1].toInt() and 0xFF) + 256) and 0xFF
                if (d != 1) {
                    gaps++
                    dropped += (d - 1).coerceAtLeast(0)
                }
            }

            val ftmSpan = (index.ftmUnwrapped[n - 1] - index.ftmUnwrapped[0]) * FtmUnwrapper.SECONDS_PER_TICK
            val firstWall = index.unixTsNs.firstOrNull { it != 0L }
            val lastWall = (n - 1 downTo 0).firstOrNull { index.unixTsNs[it] != 0L }?.let { index.unixTsNs[it] }
            val wallSpan = if (firstWall != null && lastWall != null && lastWall > firstWall) {
                (lastWall - firstWall) / 1e9
            } else {
                null
            }

            return CaptureHealth(
                records = n,
                ftmSpanSeconds = ftmSpan,
                wallSpanSeconds = wallSpan,
                meanRateHz = if (ftmSpan > 0) (n - 1) / ftmSpan else null,
                allZeroRecords = allZero,
                staleChainRecords = stale,
                geometryBadRecords = badGeom,
                ownTransmissionRecords = own,
                positiveRssiRecords = positive,
                unknownTlvRecords = unknown,
                vendorHdrRecords = vendor,
                nodeStateRecords = nodeState,
                seqGaps = gaps,
                seqDroppedReports = dropped,
                toneCounts = index.toneCountHistogram(),
                distinctSourceMacs = macs.size,
                rssiRange = if (rssiMax >= rssiMin) rssiMin to rssiMax else null,
                monitorWidth = index.monitorWidth,
            )
        }
    }
}

/** Progress of the single indexing pass. */
interface LoadProgress {
    fun onBytes(read: Long, total: Long)
    fun onRecords(count: Int)
    val isCancelled: Boolean
}

object CaptureLoader {

    /**
     * Index a capture in one pass.
     *
     * One pass matters: on a remote object store every extra pass is another
     * full transfer. The pass builds the record index and the overview
     * waterfall together, and the CSI matrices are decoded, used and dropped
     * rather than retained.
     */
    fun load(
        source: ByteSource,
        payload: Payload,
        progress: LoadProgress,
        compression: Compression = Compression.NONE,
        sidecar: SessionSidecar? = null,
    ): Capture {
        val started = System.currentTimeMillis()
        val total = source.length
        val header = if (payload == Payload.CSIQ) CsiqReader.readHeader(source) else null
        val firstOffset = header?.firstRecordOffset ?: 0L

        // The raw driver header has no width field: it is a session constant,
        // not a per-record one. The sibling metadata.json is the only place it
        // exists for a raw stream, so a capture opened beside its sidecar knows
        // its width and one opened alone honestly reports that it does not.
        val rawWidth = sidecar?.monitorWidth ?: Width.UNKNOWN

        val builder = CaptureIndexBuilder()
        val waterfall = WaterfallBuilder(firstOffset, if (total > 0) total else Long.MAX_VALUE / 2)

        val end = if (payload == Payload.RAW_DRIVER_STREAM) {
            RawStream.scan(source, 0L, onProgress = { progress.onBytes(it, total) }) { msg ->
                if (progress.isCancelled) return@scan false
                val rec = try {
                    RawStream.parseRecord(msg.hdr, msg.csi, rawWidth, keepVendorHdr = false)
                } catch (_: Exception) {
                    return@scan true
                }
                val row = builder.count
                builder.add(msg.offset, 4 + 4 + msg.hdr.size + 4 + msg.csi.size, rec)
                waterfall.accept(msg.offset, row, rec)
                if (row % PROGRESS_EVERY == 0) progress.onRecords(row)
                true
            }
        } else {
            CsiqReader.scan(source, firstOffset, onProgress = { progress.onBytes(it, total) }) { framed ->
                if (progress.isCancelled) return@scan false
                val rec = try {
                    TlvCodec.decode(framed.payload)
                } catch (_: Exception) {
                    // A record that does not satisfy the format is skipped, not
                    // fatal: the rest of the capture is still evidence.
                    return@scan true
                }
                val row = builder.count
                builder.add(framed.offset, framed.framedLength, rec)
                waterfall.accept(framed.offset, row, rec)
                if (row % PROGRESS_EVERY == 0) progress.onRecords(row)
                true
            }
        }

        val index = builder.build()
        progress.onRecords(index.size)
        return Capture(
            source = source,
            payload = payload,
            compression = compression,
            header = header,
            sidecar = sidecar,
            index = index,
            waterfall = waterfall.build(),
            scanEnd = end,
            health = CaptureHealth.of(index),
            indexMillis = System.currentTimeMillis() - started,
        )
    }

    private const val PROGRESS_EVERY = 512
}
