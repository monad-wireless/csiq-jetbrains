package io.monadcount.csiq.format

import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile

/**
 * A readable span of bytes, which may or may not be local.
 *
 * The whole plugin reaches a capture through this interface, and never through
 * a filesystem path. That is what lets a capture on S3 behave like one on disk:
 * an S3-backed source reports [randomAccess] as false, and every reader here
 * already has a sequential path for that case.
 */
interface ByteSource : Closeable {
    /** Total length in bytes, or -1 when the source cannot state one. */
    val length: Long

    /** A name for messages. Not a path, and not safe to open as one. */
    val displayName: String

    /**
     * True when [open] at an arbitrary offset costs about the same as at zero.
     *
     * False for a remote object store, where a seek is a new HTTP request, and
     * for a decompressing stream, where it is a re-read from the start.
     */
    val randomAccess: Boolean

    /** Open a stream positioned at [offset]. The caller closes it. */
    fun open(offset: Long = 0L): InputStream

    override fun close() {}
}

/** A source over a local file. */
class FileByteSource(private val file: File) : ByteSource {
    override val length: Long get() = file.length()
    override val displayName: String get() = file.name
    override val randomAccess: Boolean get() = true

    override fun open(offset: Long): InputStream {
        val raf = RandomAccessFile(file, "r")
        raf.seek(offset)
        return BufferedInputStream(RafInputStream(raf), DEFAULT_BUFFER)
    }

    private class RafInputStream(private val raf: RandomAccessFile) : InputStream() {
        override fun read(): Int = raf.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = raf.read(b, off, len)
        override fun skip(n: Long): Long {
            val pos = raf.filePointer
            val target = minOf(pos + n, raf.length())
            raf.seek(target)
            return target - pos
        }
        override fun close() = raf.close()
    }
}

/** A source over bytes already in memory. */
class ByteArrayByteSource(
    private val bytes: ByteArray,
    override val displayName: String = "memory",
) : ByteSource {
    override val length: Long get() = bytes.size.toLong()
    override val randomAccess: Boolean get() = true
    override fun open(offset: Long): InputStream =
        ByteArrayInputStream(bytes, offset.toInt(), bytes.size - offset.toInt())
}

internal const val DEFAULT_BUFFER = 1 shl 16

/**
 * Read exactly [n] bytes, or throw.
 *
 * `InputStream.read` is allowed to return short, and over a network filesystem
 * it routinely does. Every fixed-width field here goes through this.
 */
internal fun InputStream.readFully(n: Int): ByteArray {
    val b = ByteArray(n)
    var read = 0
    while (read < n) {
        val r = read(b, read, n - read)
        if (r < 0) throw EOFException("wanted $n bytes, got $read")
        read += r
    }
    return b
}

/** Skip exactly [n] bytes, or throw. */
internal fun InputStream.skipFully(n: Long) {
    var left = n
    while (left > 0) {
        val s = skip(left)
        if (s > 0) {
            left -= s
        } else {
            if (read() < 0) throw EOFException("wanted to skip $n bytes, $left remain")
            left -= 1
        }
    }
}
