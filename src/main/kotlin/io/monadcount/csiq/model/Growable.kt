package io.monadcount.csiq.model

/**
 * Growable primitive arrays.
 *
 * The index holds one entry per record, and a capture runs to hundreds of
 * thousands. Boxing each field into a collection would cost an object header
 * per value, so the index is a structure of arrays and these are how it grows.
 */
internal class LongBuf(initial: Int = 1024) {
    var data = LongArray(initial)
        private set
    var size = 0
        private set

    fun add(v: Long) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    fun compact(): LongArray = data.copyOf(size)
}

internal class IntBuf(initial: Int = 1024) {
    var data = IntArray(initial)
        private set
    var size = 0
        private set

    fun add(v: Int) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    fun compact(): IntArray = data.copyOf(size)
}

internal class ShortBuf(initial: Int = 1024) {
    var data = ShortArray(initial)
        private set
    var size = 0
        private set

    fun add(v: Short) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    fun compact(): ShortArray = data.copyOf(size)
}

internal class ByteBuf(initial: Int = 1024) {
    var data = ByteArray(initial)
        private set
    var size = 0
        private set

    fun add(v: Byte) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }

    fun compact(): ByteArray = data.copyOf(size)
}
