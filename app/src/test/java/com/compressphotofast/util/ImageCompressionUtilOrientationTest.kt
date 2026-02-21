package com.compressphotofast.util

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageCompressionUtilOrientationTest {

    @Test
    fun exifOrientation_constants_areCorrect() {
        assertEquals(1, ExifInterface.ORIENTATION_NORMAL)
        assertEquals(2, ExifInterface.ORIENTATION_FLIP_HORIZONTAL)
        assertEquals(3, ExifInterface.ORIENTATION_ROTATE_180)
        assertEquals(4, ExifInterface.ORIENTATION_FLIP_VERTICAL)
        assertEquals(5, ExifInterface.ORIENTATION_TRANSPOSE)
        assertEquals(6, ExifInterface.ORIENTATION_ROTATE_90)
        assertEquals(7, ExifInterface.ORIENTATION_TRANSVERSE)
        assertEquals(8, ExifInterface.ORIENTATION_ROTATE_270)
    }

    @Test
    fun orientationTransform_defaultValues_areZero() {
        val transform = OrientationTransform()
        assertEquals(0, transform.rotationDegrees)
        assertEquals(false, transform.flipHorizontal)
        assertEquals(false, transform.flipVertical)
    }

    @Test
    fun orientationTransform_customValues_areSet() {
        val transform = OrientationTransform(
            rotationDegrees = 90,
            flipHorizontal = true,
            flipVertical = true
        )
        assertEquals(90, transform.rotationDegrees)
        assertEquals(true, transform.flipHorizontal)
        assertEquals(true, transform.flipVertical)
    }

    private data class OrientationTransform(
        val rotationDegrees: Int = 0,
        val flipHorizontal: Boolean = false,
        val flipVertical: Boolean = false
    )
}
