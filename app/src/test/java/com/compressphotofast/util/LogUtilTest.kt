package com.compressphotofast.util

import com.compressphotofast.BaseUnitTest
import org.junit.Test

/**
 * Unit тесты для класса LogUtil.
 * LogUtil предоставляет структурированное логирование с категориями.
 *
 * Примечание: Поскольку LogUtil использует Timber для логирования,
 * а Timber уже инициализирован в BaseUnitTest, мы просто проверяем,
 * что методы LogUtil вызываются без исключений.
 *
 * Тесты с URI пропущены, так как они требуют Android контекста
 * для работы с Uri (Robolectric или instrumentation тесты).
 */
class LogUtilTest : BaseUnitTest() {

    // ========== Тесты общего логирования ==========

    @Test
    fun `log с тегом и сообщением`() {
        // Arrange
        val tag = "TestTag"
        val message = "Test message"

        // Act & Assert - просто проверяем, что метод не выбрасывает исключение
        LogUtil.log(tag, message)
    }

    // ========== Тесты логирования процесса ==========

    @Test
    fun `processInfo с сообщением`() {
        // Arrange
        val message = "Process started"

        // Act & Assert
        LogUtil.processInfo(message)
    }

    @Test
    fun `processDebug с сообщением`() {
        // Arrange
        val message = "Debug info"

        // Act & Assert
        LogUtil.processDebug(message)
    }

    @Test
    fun `processWarning с сообщением`() {
        // Arrange
        val message = "Warning message"

        // Act & Assert
        LogUtil.processWarning(message)
    }

    // ========== Тесты логирования ошибок ==========

    @Test
    fun `errorSimple с операцией и сообщением`() {
        // Arrange
        val operation = "compression"
        val message = "Compression failed"

        // Act & Assert
        LogUtil.errorSimple(operation, message)
    }

    @Test
    fun `errorWithException с операцией и исключением`() {
        // Arrange
        val operation = "compression"
        val exception = RuntimeException("Test exception")

        // Act & Assert
        LogUtil.errorWithException(operation, exception)
    }

    @Test
    fun `errorWithMessageAndException с операцией, сообщением и исключением`() {
        // Arrange
        val operation = "compression"
        val message = "Compression failed"
        val exception = RuntimeException("Test exception")

        // Act & Assert
        LogUtil.errorWithMessageAndException(operation, message, exception)
    }

    @Test
    fun `error с null URI, операцией и сообщением`() {
        // Arrange
        val uri: android.net.Uri? = null
        val operation = "compression"
        val message = "Compression failed"

        // Act & Assert
        LogUtil.error(uri, operation, message)
    }

    // ========== Тесты логирования уведомлений ==========

    @Test
    fun `notification с сообщением`() {
        // Arrange
        val message = "Notification sent"

        // Act & Assert
        LogUtil.notification(message)
    }

    // ========== Тесты отладочного логирования ==========

    @Test
    fun `debug с категорией и сообщением`() {
        // Arrange
        val category = "TEST"
        val message = "Debug message"

        // Act & Assert
        LogUtil.debug(category, message)
    }

    // ========== Тесты логирования разрешений ==========

    @Test
    fun `permissionsInfo с сообщением`() {
        // Arrange
        val message = "Permission granted"

        // Act & Assert
        LogUtil.permissionsInfo(message)
    }

    @Test
    fun `permissionsWarning с сообщением`() {
        // Arrange
        val message = "Permission warning"

        // Act & Assert
        LogUtil.permissionsWarning(message)
    }

    @Test
    fun `permissionsError с сообщением`() {
        // Arrange
        val message = "Permission denied"

        // Act & Assert
        LogUtil.permissionsError(message)
    }

    @Test
    fun `permissionsError с сообщением и исключением`() {
        // Arrange
        val message = "Permission denied"
        val exception = SecurityException("Permission denied")

        // Act & Assert
        LogUtil.permissionsError(message, exception)
    }

    // ========== Тесты batch логирования ==========

    @Test
    fun `batchStart начинает batch операцию`() {
        // Arrange
        val operationId = "test-batch-1"
        val totalCount = 50

        // Act & Assert
        LogUtil.batchStart(operationId, totalCount)
    }

    @Test
    fun `batchProgress обновляет прогресс`() {
        // Arrange
        val operationId = "test-batch-2"
        val fileName = "image1.jpg"

        // Act & Assert
        LogUtil.batchStart(operationId, 20)
        LogUtil.batchProgress(operationId, fileName)
        LogUtil.batchProgress(operationId, "image2.jpg")
    }

    @Test
    fun `batchComplete завершает batch операцию`() {
        // Arrange
        val operationId = "test-batch-3"
        val success = 45
        val failed = 3
        val skipped = 2

        // Act & Assert
        LogUtil.batchComplete(operationId, success, failed, skipped)
    }

    @Test
    fun `batchError логирует ошибку`() {
        // Arrange
        val operationId = "test-batch-4"
        val fileName = "corrupted.jpg"
        val error = "Invalid image format"

        // Act & Assert
        LogUtil.batchError(operationId, fileName, error)
    }
}
