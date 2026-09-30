package io.monadcount.csiq.ide

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel

/** Settings under `Tools | CSI captures`. */
class CsiqConfigurable : BoundConfigurable("CSI Captures") {

    private val settings = CsiqSettings.getInstance()

    override fun createPanel(): DialogPanel {
        val state = settings.state
        return panel {
            group("Remote captures") {
                row {
                    checkBox("Copy a remote capture to a local cache before indexing")
                        .bindSelected(state::cacheRemoteCaptures)
                        .comment(
                            "A remote filesystem gives a forward-only stream, so without a cache every " +
                                "jump to a record re-reads from the start of the file. Fetching once makes " +
                                "a capture on S3 behave like one on disk.",
                        )
                }
                row("Do not cache captures larger than:") {
                    intTextField(1..1_048_576).bindIntText(state::cacheLimitMb)
                    label("MB")
                }
                row("Total cache budget:") {
                    intTextField(1..1_048_576).bindIntText(state::cacheBudgetMb)
                    label("MB")
                        .comment("Least recently used captures are dropped when the budget is exceeded.")
                }
            }
            group("Views") {
                row("Overview waterfall columns:") {
                    intTextField(200..8000).bindIntText(state::overviewColumns)
                        .comment(
                            "Time resolution of the whole-capture image. Zooming reads the records " +
                                "themselves, so this bounds memory rather than detail.",
                        )
                }
            }
        }
    }
}
