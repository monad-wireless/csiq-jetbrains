package io.monadcount.csiq.ide.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.thisLogger
import io.monadcount.csiq.format.CsiRecord
import io.monadcount.csiq.model.Capture
import java.util.Collections

/**
 * Reads one record at a time, off the event thread, with a small cache.
 *
 * A record's CSI matrix is the only large thing in a capture and the index
 * deliberately does not keep it, so every view that draws a waveform has to go
 * back to the source. On a local file that costs a seek. On a capture streamed
 * from S3 it costs a request, which is precisely why it must never happen on
 * the event thread and why the last few records are kept.
 */
class RecordLoader(private val capture: Capture, private val cacheSize: Int = 48) {

    private val cache: MutableMap<Int, CsiRecord> = Collections.synchronizedMap(
        object : LinkedHashMap<Int, CsiRecord>(cacheSize, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, CsiRecord>): Boolean =
                size > cacheSize
        },
    )

    /** Generation counter, so a slow read for an old selection is discarded. */
    @Volatile
    private var generation = 0

    /** The record if it is already decoded, without touching the source. */
    fun cached(row: Int): CsiRecord? = cache[row]

    /**
     * Ask for a record.
     *
     * If it is cached, [onLoaded] runs immediately on the calling thread.
     * Otherwise the read happens on a pooled thread and [onLoaded] runs on the
     * event thread, unless a newer request superseded this one.
     */
    fun request(row: Int, keepRawTlvs: Boolean = false, onLoaded: (CsiRecord?) -> Unit) {
        cache[row]?.let { if (!keepRawTlvs || it.rawTlvs != null) { onLoaded(it); return } }
        val token = ++generation
        ApplicationManager.getApplication().executeOnPooledThread {
            val record = try {
                capture.readRecord(row, keepRawTlvs)
            } catch (e: Exception) {
                thisLogger().warn("could not read record $row of ${capture.source.displayName}", e)
                null
            }
            if (record != null) cache[row] = record
            ApplicationManager.getApplication().invokeLater {
                if (token == generation) onLoaded(record)
            }
        }
    }
}
