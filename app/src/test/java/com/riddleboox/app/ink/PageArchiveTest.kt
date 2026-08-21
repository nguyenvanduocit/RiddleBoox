package com.riddleboox.app.ink

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PageArchiveTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun archive(keep: Int = 12) = PageArchive(File(temp.root, "pages"), keep)

    @Test
    fun `a saved page keeps its bytes`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)

        val file = archive().save(png, timestampMs = 1_700_000_000_000)!!

        assertTrue(file.exists())
        assertEquals(png.toList(), file.readBytes().toList())
    }

    @Test
    fun `pages come back newest first`() {
        val diary = archive()
        diary.save(byteArrayOf(1), 1_700_000_000_000)
        diary.save(byteArrayOf(2), 1_700_000_001_000)
        diary.save(byteArrayOf(3), 1_700_000_002_000)

        assertEquals(
            listOf(3.toByte(), 2, 1),
            diary.pages().map { it.readBytes().single() },
        )
    }

    @Test
    fun `only the newest pages are kept`() {
        val diary = archive(keep = 2)
        diary.save(byteArrayOf(1), 1_700_000_000_000)
        diary.save(byteArrayOf(2), 1_700_000_001_000)
        diary.save(byteArrayOf(3), 1_700_000_002_000)

        assertEquals(
            listOf(3.toByte(), 2),
            diary.pages().map { it.readBytes().single() },
        )
    }

    /** A turn is worth more than its archive copy. */
    @Test
    fun `an unwritable directory gives back null instead of throwing`() {
        val blocked = temp.newFile("not-a-directory")

        assertNull(PageArchive(File(blocked, "pages")).save(byteArrayOf(1), 1_700_000_000_000))
    }

    @Test
    fun `an archive nobody has written to is empty, not missing`() {
        assertEquals(emptyList<File>(), archive().pages())
    }
}
