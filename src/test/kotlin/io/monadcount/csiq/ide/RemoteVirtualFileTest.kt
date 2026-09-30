package io.monadcount.csiq.ide

import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileSystem
import com.intellij.openapi.vfs.VirtualFileListener
import com.intellij.testFramework.LightVirtualFileBase
import io.monadcount.csiq.format.ByteArrayByteSource
import io.monadcount.csiq.format.Payload
import io.monadcount.csiq.model.CaptureLoader
import io.monadcount.csiq.model.LoadProgress
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The compatibility claim, checked rather than asserted.
 *
 * Big Data Tools and Remote File Systems expose S3, HDFS and SFTP as ordinary
 * virtual files. The claim this plugin makes is that a capture read through one
 * of those indexes to exactly the same thing as the same bytes on disk. The
 * fake filesystem below behaves the way a remote one does: it reports itself as
 * not local, it hands out a forward-only stream, and it throws if anyone asks
 * for the whole file as a byte array.
 */
class RemoteVirtualFileTest {

    private fun fixture(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/ambient-24-legacy52.csiq"))
            .use { it.readBytes() }

    /** A virtual file that behaves like an object in a bucket. */
    private class RemoteFile(
        private val bytes: ByteArray,
        private val name: String,
        val onBulkRead: () -> Unit,
    ) : LightVirtualFileBase(name, null, 0) {

        var streamsOpened = 0
            private set

        private val fs = object : VirtualFileSystem() {
            override fun getProtocol(): String = "s3"
            override fun findFileByPath(path: String): VirtualFile? = null
            override fun refresh(asynchronous: Boolean) = Unit
            override fun refreshAndFindFileByPath(path: String): VirtualFile? = null
            override fun addVirtualFileListener(listener: VirtualFileListener) = Unit
            override fun removeVirtualFileListener(listener: VirtualFileListener) = Unit
            override fun deleteFile(requestor: Any?, file: VirtualFile) = Unit
            override fun moveFile(requestor: Any?, file: VirtualFile, newParent: VirtualFile) = Unit
            override fun renameFile(requestor: Any?, file: VirtualFile, newName: String) = Unit
            override fun createChildFile(requestor: Any?, parent: VirtualFile, name: String): VirtualFile =
                throw UnsupportedOperationException()
            override fun createChildDirectory(requestor: Any?, parent: VirtualFile, dir: String): VirtualFile =
                throw UnsupportedOperationException()
            override fun copyFile(
                requestor: Any?,
                file: VirtualFile,
                newParent: VirtualFile,
                copyName: String,
            ): VirtualFile = throw UnsupportedOperationException()
            override fun isReadOnly(): Boolean = true
        }

        override fun getFileSystem(): VirtualFileSystem = fs

        override fun getPath(): String = "bucket/$name"

        override fun getLength(): Long = bytes.size.toLong()

        override fun isInLocalFileSystem(): Boolean = false

        override fun contentsToByteArray(): ByteArray {
            // The platform refuses this above about 20 MB, so a viewer that
            // calls it works on a fixture and fails on a real capture. Failing
            // loudly here is the point of the fake.
            onBulkRead()
            throw UnsupportedOperationException("contentsToByteArray must not be called on a remote capture")
        }

        override fun getInputStream(): InputStream {
            streamsOpened++
            return ByteArrayInputStream(bytes)
        }

        override fun getOutputStream(requestor: Any?, newModificationStamp: Long, newTimeStamp: Long): OutputStream =
            throw UnsupportedOperationException()
    }

    @Test
    fun `a remote capture indexes identically to the same bytes on disk`() {
        val bytes = fixture()
        val remote = RemoteFile(bytes, "capture.csiq") { fail("contentsToByteArray was called") }
        val source = VirtualFileByteSource(remote)

        assertFalse(source.randomAccess, "a remote source must not claim random access")
        assertTrue(source.isRemote)
        assertEquals("s3", source.protocol)
        assertEquals(bytes.size.toLong(), source.length)

        val fromRemote = CaptureLoader.load(source, Payload.CSIQ, SilentProgress())
        val fromLocal = CaptureLoader.load(ByteArrayByteSource(bytes), Payload.CSIQ, SilentProgress())

        assertEquals(fromLocal.recordCount, fromRemote.recordCount, "record count")
        assertContentEquals(fromLocal.index.offset, fromRemote.index.offset, "record offsets")
        assertContentEquals(fromLocal.index.ftm, fromRemote.index.ftm, "ftm")
        assertContentEquals(fromLocal.index.rssi0, fromRemote.index.rssi0, "rssi chain 0")
        assertContentEquals(fromLocal.index.flags, fromRemote.index.flags, "per-record flags")
        assertEquals(fromLocal.health.ftmSpanSeconds, fromRemote.health.ftmSpanSeconds, 0.0)
        assertEquals(fromLocal.scanEnd, fromRemote.scanEnd)
    }

    @Test
    fun `the whole index costs one pass over a remote object`() {
        val bytes = fixture()
        val remote = RemoteFile(bytes, "capture.csiq") { fail("contentsToByteArray was called") }
        CaptureLoader.load(VirtualFileByteSource(remote), Payload.CSIQ, SilentProgress())
        assertEquals(
            2,
            remote.streamsOpened,
            "indexing should open the header stream and one record stream, not one per record",
        )
    }

    @Test
    fun `reading from an offset on a sequential source lands on the right byte`() {
        val bytes = fixture()
        val remote = RemoteFile(bytes, "capture.csiq") { fail("contentsToByteArray was called") }
        val source = VirtualFileByteSource(remote)
        val offset = 1000L
        source.open(offset).use { input ->
            val head = ByteArray(8)
            var read = 0
            while (read < head.size) {
                val r = input.read(head, read, head.size - read)
                if (r < 0) break
                read += r
            }
            assertContentEquals(bytes.copyOfRange(offset.toInt(), offset.toInt() + 8), head)
        }
    }

    private class SilentProgress : LoadProgress {
        override fun onBytes(read: Long, total: Long) = Unit
        override fun onRecords(count: Int) = Unit
        override val isCancelled: Boolean get() = false
    }
}
