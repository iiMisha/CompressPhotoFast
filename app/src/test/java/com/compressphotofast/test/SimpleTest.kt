package com.compressphotofast.test

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.junit.Test
import org.junit.Assert.*
import org.robolectric.annotation.Config
import com.compressphotofast.test.TestApplication

/**
 * Простой тест в другом пакете для проверки проблемы
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = TestApplication::class)
class SimpleTest {

    @Test
    fun `simple test in different package`() {
        assertTrue("Простой тест", true)
    }
}
