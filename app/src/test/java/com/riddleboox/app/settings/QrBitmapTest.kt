package com.riddleboox.app.settings

import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QrBitmapTest {

    @Test
    fun `the bitmap is exactly the requested size`() {
        val bitmap = qrBitmap("http://192.168.1.5:8080/?t=abc", 200)
        assertEquals(200, bitmap.width)
        assertEquals(200, bitmap.height)
    }

    @Test
    fun `the render actually has both black and white pixels`() {
        val bitmap = qrBitmap("http://192.168.1.5:8080/?t=abc", 200)
        var sawBlack = false
        var sawWhite = false
        for (x in 0 until bitmap.width) {
            for (y in 0 until bitmap.height) {
                when (bitmap.getPixel(x, y)) {
                    Color.BLACK -> sawBlack = true
                    Color.WHITE -> sawWhite = true
                }
            }
        }
        assertTrue(sawBlack)
        assertTrue(sawWhite)
    }
}
