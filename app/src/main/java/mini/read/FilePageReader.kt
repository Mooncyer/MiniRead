package mini.read

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import kotlin.math.min

internal data class Page(val start: Long, val nextStart: Long, val text: String)

/** Bounded UTF-8 page reader. Page navigation is driven by explicit start offsets. */
internal class FilePageReader(private val file: File) {
    companion object { private const val WINDOW_BYTES = 12 * 1024 }

    fun length(): Long = file.length()

    fun close() = Unit

    fun readPage(startOffset: Long): Page {
        synchronized(file) {
            return readPageInternal(normalizeStart(startOffset, file.length()))
        }
    }

    /** Returns page starts through the page containing [offset], including that page. */
    fun pageStartsUntil(offset: Long): List<Long> {
        synchronized(file) {
            val size = file.length()
            if (size == 0L) return listOf(0L)
            val target = offset.coerceIn(0L, size)
            val starts = ArrayList<Long>()
            var cursor = 0L
            while (true) {
                starts.add(cursor)
                if (cursor >= target || cursor >= size) return starts
                val page = readPageInternal(cursor)
                if (page.nextStart <= cursor) return starts
                cursor = page.nextStart
                if (cursor > target) return starts
            }
        }
    }

    private fun readPageInternal(start: Long): Page {
        val size = file.length()
        if (size == 0L || start >= size) return Page(start, size, "")
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(start)
            val available = min(WINDOW_BYTES.toLong(), size - start).toInt()
            val bytes = ByteArray(available)
            val count = raf.read(bytes)
            if (count <= 0) return Page(start, size, "")
            var end = utf8SafeLength(bytes, count).coerceAtLeast(1)
            if (start + end < size) {
                var newline = end - 1
                val lowerBound = (end / 2).coerceAtLeast(1)
                while (newline >= lowerBound && bytes[newline].toInt() != 10) newline--
                if (newline >= lowerBound) end = newline + 1
            }
            val next = (start + end).coerceAtMost(size)
            return Page(start, if (next > start) next else size, String(bytes, 0, end, StandardCharsets.UTF_8))
        }
    }

    private fun normalizeStart(offset: Long, size: Long): Long {
        var start = offset.coerceIn(0L, size)
        if (start == 0L || start == size) return start
        RandomAccessFile(file, "r").use { raf ->
            while (start > 0L) {
                raf.seek(start)
                val current = raf.read()
                if (current < 0 || current and 0xC0 != 0x80) break
                start--
            }
        }
        return start
    }

    private fun utf8SafeLength(bytes: ByteArray, count: Int): Int {
        var end = count
        val last = bytes[end - 1].toInt() and 0xff
        if (last and 0x80 == 0) return end
        if (last and 0xc0 != 0x80) return end - 1
        var lead = end - 1
        while (lead >= 0 && (bytes[lead].toInt() and 0xc0) == 0x80) lead--
        if (lead < 0) return 0
        val leadByte = bytes[lead].toInt() and 0xff
        val width = when {
            leadByte and 0xe0 == 0xc0 -> 2
            leadByte and 0xf0 == 0xe0 -> 3
            leadByte and 0xf8 == 0xf0 -> 4
            else -> 1
        }
        return if (lead + width <= end) end else lead
    }
}
