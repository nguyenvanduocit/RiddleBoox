package com.riddleboox.app.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileTreeTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `JavaFileTree reports the same shape as the wrapped File`() {
        val root = folder.newFolder()
        File(root, "sub").mkdirs()
        File(root, "sub/leaf.txt").writeText("hi")

        val tree = JavaFileTree(root)
        val sub = tree.listChildren().single()
        val leaf = sub.listChildren().single()

        assertTrue(sub.isDirectory)
        assertFalse(leaf.isDirectory)
        assertTrue(leaf.isFile)
        assertEquals("sub/leaf.txt", leaf.path)
        assertEquals("hi", leaf.openInputStream()!!.bufferedReader().readText())
    }

    @Test
    fun `JavaFileTree delete removes the file`() {
        val root = folder.newFolder()
        val file = File(root, "leaf.txt").apply { writeText("x") }

        assertTrue(JavaFileTree(file, root).delete())
        assertFalse(file.exists())
    }
}
