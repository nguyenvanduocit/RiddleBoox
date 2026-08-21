package com.riddleboox.app.library

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A real EPUB file, written to [file], for tests that need one.
 *
 * Small but not simplified: a cover the spine excludes, a chapter the table of
 * contents names and one it does not, a document in a subfolder reached by a
 * relative href, and a percent-escaped file name. Those four are where every
 * bug in reading an EPUB actually lives; a two-file book would prove nothing.
 */
internal fun writeSampleEpub(file: File, fillerChars: Int = 0): File {
    val filler = if (fillerChars > 0) "<p>" + "day la mot doan rat dai. ".repeat(fillerChars / 25) + "</p>" else ""
    val entries = linkedMapOf(
        "mimetype" to "application/epub+zip",
        "META-INF/container.xml" to """
            <?xml version="1.0"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles>
                <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
        """.trimIndent(),
        "OEBPS/content.opf" to """
            <?xml version="1.0"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
              <manifest>
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                <item id="cover" href="cover.xhtml" media-type="application/xhtml+xml"/>
                <item id="c1" href="ch1.xhtml" media-type="application/xhtml+xml"/>
                <item id="c2" href="text/ch2.xhtml" media-type="application/xhtml+xml"/>
                <item id="c3" href="text/ch%20three.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine toc="ncx">
                <itemref idref="cover" linear="no"/>
                <itemref idref="c1"/>
                <itemref idref="c2"/>
                <itemref idref="c3"/>
              </spine>
            </package>
        """.trimIndent(),
        "OEBPS/toc.ncx" to """
            <?xml version="1.0"?>
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
              <navMap>
                <navPoint id="n1" playOrder="1">
                  <navLabel><text>Chương một</text></navLabel>
                  <content src="ch1.xhtml"/>
                </navPoint>
                <navPoint id="n3" playOrder="3">
                  <navLabel><text>Chương ba</text></navLabel>
                  <content src="text/ch%20three.xhtml#top"/>
                </navPoint>
              </navMap>
            </ncx>
        """.trimIndent(),
        "OEBPS/cover.xhtml" to "<html><body><h1>Bìa</h1></body></html>",
        "OEBPS/ch1.xhtml" to """
            <html><head><title>bỏ qua</title><style>p { color: red }</style></head>
            <body>
              <h1>Tiêu đề trong trang</h1>
              <p>Mèo con ngồi trên mái nhà&#8201;&mdash;&nbsp;lặng lẽ.</p>
              <p>Trời đã tối.</p>
              $filler
            </body></html>
        """.trimIndent(),
        "OEBPS/text/ch2.xhtml" to "<html><body><h2>Chương hai</h2><p>Con mèo lại đến.</p></body></html>",
        "OEBPS/text/ch three.xhtml" to "<html><body><p>Không có mèo nào ở đây.</p></body></html>",
    )
    ZipOutputStream(file.outputStream()).use { zip ->
        for ((name, body) in entries) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(body.toByteArray())
            zip.closeEntry()
        }
    }
    return file
}

/**
 * A shelf held in memory, for tests that must not touch a device.
 *
 * Removals really remove, so a test can ask what the shelf looks like
 * afterwards rather than only what the answer said.
 */
internal class FakeLibrary(
    books: List<Book>,
    marks: List<Highlight> = emptyList(),
) : Library {
    private val books = books.toMutableList()
    private val marks = marks.toMutableList()

    override fun books(): List<Book> = books.toList()

    override fun highlights(bookId: String?): List<Highlight> =
        if (bookId == null) marks.toList() else marks.filter { it.bookId == bookId }

    override fun deleteBook(bookId: String): Removed {
        val gone = marks.count { it.bookId == bookId }
        marks.removeAll { it.bookId == bookId }
        return Removed(entry = books.removeAll { it.id == bookId }, marks = gone)
    }

    override fun deleteHighlight(id: String): Boolean = marks.removeAll { it.id == id }
}

/** A shelf that cannot be opened at all — the reader app gone, or shut. */
internal class ClosedLibrary : Library {
    override fun books(): List<Book> = throw LibraryUnreachable("Metadata không mở được")
    override fun highlights(bookId: String?): List<Highlight> = throw LibraryUnreachable("Annotation không mở được")
    override fun deleteBook(bookId: String): Removed = throw LibraryUnreachable("Metadata không mở được")
    override fun deleteHighlight(id: String): Boolean = throw LibraryUnreachable("Annotation không mở được")
}
