package io.monadcount.csiq.ide

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service

@Service(Service.Level.APP)
@State(
    name = "CsiqSettings",
    storages = [Storage("csiq.xml", roamingType = RoamingType.DISABLED)],
)
class CsiqSettings : PersistentStateComponent<CsiqSettings.State> {

    class State {
        /**
         * Copy a remote capture to a local cache before indexing it.
         *
         * A remote filesystem gives a forward-only stream, so without a cache
         * every jump to a record costs a fresh request and a re-read from the
         * start of the file. With one, a capture is fetched once and then
         * behaves exactly like a local one.
         */
        var cacheRemoteCaptures: Boolean = true

        /** Captures larger than this are streamed rather than cached, in MB. */
        var cacheLimitMb: Int = 4096

        /** Total cache budget before the least recently used files are dropped, in MB. */
        var cacheBudgetMb: Int = 16384

        /**
         * Exclude records whose chain reported the no-measurement sentinel.
         *
         * Such a chain's CSI is a byte-identical stale copy of an earlier
         * frame, so including it draws a previous moment's channel.
         */
        var excludeStaleChains: Boolean = true

        /**
         * Exclude records the capturing node transmitted itself.
         *
         * These are identified by an absent `MONO_US`. They measure the
         * injector's loopback, not the room.
         */
        var excludeOwnTransmissions: Boolean = false

        /** Columns in the overview waterfall. */
        var overviewColumns: Int = 1600

        /** Name of the amplitude colour map. */
        var colorMap: String = "Viridis"
    }

    private var state = State()

    override fun getState(): State = state

    override fun loadState(loaded: State) {
        state = loaded
    }

    val cacheRemoteCaptures: Boolean get() = state.cacheRemoteCaptures
    val cacheLimitBytes: Long get() = state.cacheLimitMb.toLong() * 1024 * 1024
    val cacheBudgetBytes: Long get() = state.cacheBudgetMb.toLong() * 1024 * 1024
    val excludeStaleChains: Boolean get() = state.excludeStaleChains
    val excludeOwnTransmissions: Boolean get() = state.excludeOwnTransmissions
    val overviewColumns: Int get() = state.overviewColumns.coerceIn(200, 8000)

    companion object {
        fun getInstance(): CsiqSettings = service()
    }
}
