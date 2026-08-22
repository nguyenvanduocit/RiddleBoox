package com.riddleboox.app.handwriting

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShapeTest {

    @Test
    fun `each shape's own token round-trips back to it`() {
        for (shape in Shape.entries) {
            assertEquals(shape, Shape.fromToken(shape.token))
        }
    }

    @Test
    fun `an ordinary word is not mistaken for a shape`() {
        assertNull(Shape.fromToken("circle"))
        assertNull(Shape.fromToken("[[circle]]."))
        assertNull(Shape.fromToken("([[box]])"))
        assertNull(Shape.fromToken(""))
    }
}
