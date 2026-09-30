package io.monadcount.csiq.ide

import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.util.IconLoader
import com.intellij.openapi.vfs.VirtualFile
import io.monadcount.csiq.format.Compression
import javax.swing.Icon

/**
 * The CSIQ capture file type.
 *
 * Binary and read-only. Declaring it binary keeps the platform from indexing,
 * highlighting or trying to guess a charset for a file that is mostly i16 CSI
 * coefficients, and read-only states the truth: a capture is evidence, and
 * nothing in this plugin writes one.
 */
class CsiqFileType private constructor() : FileType {

    override fun getName(): String = "CSIQ"

    override fun getDescription(): String = "Wi-Fi channel state information capture"

    override fun getDefaultExtension(): String = "csiq"

    override fun getIcon(): Icon = Icons.FILE

    override fun isBinary(): Boolean = true

    override fun isReadOnly(): Boolean = true

    override fun getCharset(file: VirtualFile, content: ByteArray): String? = null

    companion object {
        @JvmField
        val INSTANCE: CsiqFileType = CsiqFileType()

        /**
         * Names this plugin opens.
         *
         * Any `.csiq`, with or without a codec extension after it. The archive
         * writes `capture.csiq` and `capture.csiq.zst`, and a capture that has
         * been through another tool can arrive as `.gz`, `.xz`, `.bz2`, `.lz4`
         * or `.lzma`.
         *
         * `capture.raw` is matched by its exact name rather than by a `.raw`
         * extension, because `.raw` belongs to half a dozen unrelated formats,
         * and `capture.raw` is what csid writes beside every container.
         */
        fun matches(file: VirtualFile): Boolean {
            val name = file.name.lowercase()
            val bare = Compression.stripExtension(name)
            return bare.endsWith(".csiq") || bare == "capture.raw"
        }

        /** Every name pattern, for the `fileType` extension point. */
        fun namePatterns(): String =
            (listOf("*.csiq", "capture.raw") +
                Compression.ALL_EXTENSIONS.flatMap { listOf("*.csiq$it", "capture.raw$it") })
                .joinToString(";")
    }
}

object Icons {
    val FILE: Icon = IconLoader.getIcon("/icons/csiq.svg", Icons::class.java)
}
