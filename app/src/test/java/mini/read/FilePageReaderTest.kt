package mini.read

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FilePageReaderTest {
    @Test
    fun forwardPagesReconstructUtf8Document() {
        val content = buildString {
            repeat(9000) { append("第${it}段：中文🙂 café\n") }
        }
        withDocument(content) { file ->
            val reader = FilePageReader(file)
            val pages = mutableListOf<Page>()
            var offset = 0L
            while (true) {
                val page = reader.readPage(offset)
                pages += page
                if (page.nextStart >= reader.length()) break
                offset = page.nextStart
            }
            assertEquals(content, pages.joinToString(separator = "") { it.text })
            assertEquals(0L, pages.first().start)
            assertEquals(reader.length(), pages.last().nextStart)
            assertEquals(pages.size, pages.map { it.start }.distinct().size)
        }
    }

    @Test
    fun previousPageWorksFromSavedMiddleOffset() {
        val content = ("0123456789中文🙂abcdef\n").repeat(1800)
        withDocument(content) { file ->
            val firstReader = FilePageReader(file)
            val starts = mutableListOf<Long>()
            var offset = 0L
            while (true) {
                val page = firstReader.readPage(offset)
                starts += page.start
                if (page.nextStart >= firstReader.length()) break
                offset = page.nextStart
            }
            val saved = starts[starts.size / 2]
            val reader = FilePageReader(file)
            val current = reader.readPage(saved)
            val startsFromSaved = reader.pageStartsUntil(saved)
            assertEquals(starts[starts.indexOf(saved)], startsFromSaved.last())
            val previousStart = startsFromSaved[startsFromSaved.lastIndex - 1]
            val previous = reader.readPage(previousStart)
            assertNotNull(previous)
            assertEquals(current.start, previous.nextStart)
            assertEquals(starts[starts.indexOf(saved) - 1], previous.start)
            val restored = reader.readPage(previous.start)
            assertEquals(previous, restored)
            assertEquals(current.start, restored.nextStart)
            assertEquals(readAfter(reader, previous.start), previous.text + current.text + readAfter(reader, current.nextStart))
        }
    }

    @Test
    fun emptyFileAndBeginningHaveNoPreviousPage() {
        withDocument("") { file ->
            val reader = FilePageReader(file)
            assertEquals(Page(0L, 0L, ""), reader.readPage(0))
            assertEquals(listOf(0L), reader.pageStartsUntil(0L))
        }
        withDocument("开头🙂\n结尾") { file ->
            val reader = FilePageReader(file)
            assertEquals(listOf(0L), reader.pageStartsUntil(0L))
        }
    }

    @Test
    fun middleOffsetResolvesToContainingPage() {
        val content = ("段落：中文🙂abcdef\n").repeat(2400)
        withDocument(content) { file ->
            val reader = FilePageReader(file)
            val offset = 12345L.coerceAtMost(reader.length())
            val starts = reader.pageStartsUntil(offset)
            val page = reader.readPage(starts.last())
            assert(starts.last() <= offset)
            assert(page.nextStart > offset || page.nextStart == reader.length())
            assertEquals(-1, page.text.indexOf('\uFFFD'))
        }
    }

    @Test
    fun adjacentWindowPagesRemainContiguous() {
        val content = ("行：中文🙂abcdefghijklmnopqrstuvwxyz\n").repeat(2600)
        withDocument(content) { file ->
            val reader = FilePageReader(file)
            val starts = reader.pageStartsUntil(16000L)
            val pages = starts.takeLast(3).map { reader.readPage(it) }
            for (index in 0 until pages.lastIndex) {
                assertEquals(pages[index].nextStart, pages[index + 1].start)
            }
            val joined = pages.joinToString("") { it.text }
            val bytes = content.toByteArray(StandardCharsets.UTF_8)
            val expected = String(bytes, pages.first().start.toInt(), (pages.last().nextStart - pages.first().start).toInt(), StandardCharsets.UTF_8)
            assertEquals(expected, joined)
        }
    }

    @Test
    fun arbitraryUtf8OffsetIsNormalizedWithoutReplacementCharacters() {
        val content = "🙂中文 café\n".repeat(3000)
        withDocument(content) { file ->
            val reader = FilePageReader(file)
            val page = reader.readPage(3L)
            assertEquals(0L, page.start)
            assertEquals(-1, page.text.indexOf('\uFFFD'))
        }
    }

    private fun readAfter(reader: FilePageReader, offset: Long): String {
        val output = StringBuilder()
        var cursor = offset
        while (cursor < reader.length()) {
            val page = reader.readPage(cursor)
            output.append(page.text)
            if (page.nextStart <= cursor) break
            cursor = page.nextStart
        }
        return output.toString()
    }

    private fun withDocument(content: String, block: (File) -> Unit) {
        val file = Files.createTempFile("miniread-page-test", ".txt").toFile()
        file.writeBytes(content.toByteArray(StandardCharsets.UTF_8))
        try {
            block(file)
        } finally {
            file.delete()
        }
    }
}
