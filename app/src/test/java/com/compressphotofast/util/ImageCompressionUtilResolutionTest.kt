package com.compressphotofast.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Unit тесты для логики выбора разрешения (computeScalePlan)
 */
@RunWith(JUnit4::class)
class ImageCompressionUtilResolutionTest {

    @Test
    fun `computeScalePlan returns null for original resolution`() {
        assertNull(ImageCompressionUtil.computeScalePlan(4000, 3000, Constants.RESOLUTION_ORIGINAL))
    }

    @Test
    fun `computeScalePlan returns null when image smaller than limit`() {
        assertNull(ImageCompressionUtil.computeScalePlan(1920, 1080, Constants.RESOLUTION_1920))
        assertNull(ImageCompressionUtil.computeScalePlan(1000, 800, Constants.RESOLUTION_1280))
    }

    @Test
    fun `computeScalePlan scales landscape to longest side`() {
        val plan = ImageCompressionUtil.computeScalePlan(4000, 3000, Constants.RESOLUTION_1920)!!
        assertEquals(1920, plan.targetWidth)
        assertEquals(1440, plan.targetHeight)
    }

    @Test
    fun `computeScalePlan scales portrait to longest side`() {
        val plan = ImageCompressionUtil.computeScalePlan(3000, 4000, Constants.RESOLUTION_1280)!!
        assertEquals(960, plan.targetWidth)
        assertEquals(1280, plan.targetHeight)
    }

    @Test
    fun `computeScalePlan keeps aspect ratio`() {
        val plan = ImageCompressionUtil.computeScalePlan(4032, 3024, Constants.RESOLUTION_1920)!!
        assertEquals(4032.0 / 3024.0, plan.targetWidth.toDouble() / plan.targetHeight.toDouble(), 0.01)
    }

    @Test
    fun `computeScalePlan inSampleSize is power of two not below target`() {
        data class Case(val width: Int, val height: Int, val limit: Int)

        val cases = listOf(
            Case(4000, 3000, Constants.RESOLUTION_1920),
            Case(8000, 6000, Constants.RESOLUTION_1920),
            Case(12000, 9000, Constants.RESOLUTION_1280),
            Case(3024, 4032, Constants.RESOLUTION_1280)
        )

        cases.forEach { case ->
            val plan = ImageCompressionUtil.computeScalePlan(case.width, case.height, case.limit)!!
            assertTrue("inSampleSize=${plan.inSampleSize} должен быть степенью 2", 
                plan.inSampleSize > 0 && (plan.inSampleSize and (plan.inSampleSize - 1)) == 0)
            assertTrue(
                "После сэмплинга размер не должен упасть ниже цели",
                case.width / plan.inSampleSize >= plan.targetWidth &&
                    case.height / plan.inSampleSize >= plan.targetHeight
            )
        }
    }

    @Test
    fun `computeScalePlan handles very large image`() {
        val plan = ImageCompressionUtil.computeScalePlan(16384, 12288, Constants.RESOLUTION_1920)!!
        assertEquals(1920, plan.targetWidth)
        assertEquals(1440, plan.targetHeight)
        assertEquals(8, plan.inSampleSize)
    }
}
