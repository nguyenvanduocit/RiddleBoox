package com.riddleboox.app.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StorageAccessTest {

    @Test
    fun `documentIdFor prefixes the primary volume and trims slashes`() {
        assertEquals("primary:Books/foo.epub", documentIdFor("Books/foo.epub"))
        assertEquals("primary:.ksync/document/abc", documentIdFor("/.ksync/document/abc/"))
    }

    @Test
    fun `relativePathUnder strips the root and a trailing slash`() {
        assertEquals(
            "Books/foo.epub",
            relativePathUnder("/storage/emulated/0", "/storage/emulated/0/Books/foo.epub"),
        )
        assertEquals(
            "Books/foo.epub",
            relativePathUnder("/storage/emulated/0/", "/storage/emulated/0/Books/foo.epub"),
        )
    }

    @Test
    fun `relativePathUnder is null when the path is not under the root`() {
        assertNull(relativePathUnder("/storage/emulated/0", "/data/data/com.riddleboox.app/foo"))
    }
}
