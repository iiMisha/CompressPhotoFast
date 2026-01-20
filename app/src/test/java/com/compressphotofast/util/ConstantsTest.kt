package com.compressphotofast.util

import com.compressphotofast.BaseUnitTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit тесты для проверки констант приложения.
 */
class ConstantsTest : BaseUnitTest() {

    // ========== Тесты настроек приложения ==========

    @Test
    fun `проверка имени файла настроек`() {
        // Arrange & Act & Assert
        assertEquals("compress_photo_prefs", Constants.PREF_FILE_NAME)
    }

    @Test
    fun `проверка ключа автоматического сжатия`() {
        // Arrange & Act & Assert
        assertEquals("auto_compression", Constants.PREF_AUTO_COMPRESSION)
    }

    @Test
    fun `проверка ключа качества сжатия`() {
        // Arrange & Act & Assert
        assertEquals("compression_quality", Constants.PREF_COMPRESSION_QUALITY)
    }

    @Test
    fun `проверка ключа режима сохранения`() {
        // Arrange & Act & Assert
        assertEquals("save_mode", Constants.PREF_SAVE_MODE)
    }

    // ========== Тесты режимов сохранения ==========

    @Test
    fun `проверка режима сохранения - замена`() {
        // Arrange & Act & Assert
        assertEquals(1, Constants.SAVE_MODE_REPLACE)
    }

    @Test
    fun `проверка режима сохранения - отдельная папка`() {
        // Arrange & Act & Assert
        assertEquals(2, Constants.SAVE_MODE_SEPARATE)
    }

    // ========== Тесты ограничений размера файлов ==========

    @Test
    fun `проверка минимального размера файла`() {
        // Arrange & Act & Assert
        assertEquals(50 * 1024L, Constants.MIN_FILE_SIZE)
    }

    @Test
    fun `проверка максимального размера файла`() {
        // Arrange & Act & Assert
        assertEquals(100 * 1024 * 1024L, Constants.MAX_FILE_SIZE)
    }

    @Test
    fun `проверка оптимального размера файла`() {
        // Arrange & Act & Assert
        // OPTIMUM_FILE_SIZE = 0.1 * 1024 * 1024L
        // Проверяем, что значение соответствует ожидаемому (около 0.1 MB)
        // 0.1 * 1024 * 1024 = 104857.6, при приведении к Long = 104857
        // Используем assertTrue для проверки диапазона значений
        assertTrue(Constants.OPTIMUM_FILE_SIZE > 100000L && Constants.OPTIMUM_FILE_SIZE < 110000L)
    }

    @Test
    fun `проверка минимального обрабатываемого размера файла`() {
        // Arrange & Act & Assert
        assertEquals(100 * 1024L, Constants.MIN_PROCESSABLE_FILE_SIZE)
    }

    // ========== Тесты качества сжатия ==========

    @Test
    fun `проверка качества сжатия - низкое`() {
        // Arrange & Act & Assert
        assertEquals(60, Constants.COMPRESSION_QUALITY_LOW)
    }

    @Test
    fun `проверка качества сжатия - среднее`() {
        // Arrange & Act & Assert
        assertEquals(70, Constants.COMPRESSION_QUALITY_MEDIUM)
    }

    @Test
    fun `проверка качества сжатия - высокое`() {
        // Arrange & Act & Assert
        assertEquals(85, Constants.COMPRESSION_QUALITY_HIGH)
    }

    @Test
    fun `проверка качества сжатия по умолчанию`() {
        // Arrange & Act & Assert
        assertEquals(85, Constants.DEFAULT_COMPRESSION_QUALITY)
    }

    @Test
    fun `проверка минимального качества сжатия`() {
        // Arrange & Act & Assert
        assertEquals(50, Constants.MIN_COMPRESSION_QUALITY)
    }

    @Test
    fun `проверка максимального качества сжатия`() {
        // Arrange & Act & Assert
        assertEquals(100, Constants.MAX_COMPRESSION_QUALITY)
    }

    // ========== Тесты экономии при сжатии ==========

    @Test
    fun `проверка минимального процента экономии`() {
        // Arrange & Act & Assert
        assertEquals(30f, Constants.MIN_COMPRESSION_SAVING_PERCENT)
    }

    @Test
    fun `проверка минимального коэффициента сжатия`() {
        // Arrange & Act & Assert
        assertEquals(0.8f, Constants.MIN_COMPRESSION_RATIO)
    }

    // ========== Тесты интервалов ==========

    @Test
    fun `проверка интервала фонового сканирования`() {
        // Arrange & Act & Assert
        assertEquals(30L, Constants.BACKGROUND_SCAN_INTERVAL_MINUTES)
    }

    @Test
    fun `проверка окна недавнего сканирования`() {
        // Arrange & Act & Assert
        assertEquals(15 * 60L, Constants.RECENT_SCAN_WINDOW_SECONDS)
    }

    @Test
    fun `проверка задержки ContentObserver`() {
        // Arrange & Act & Assert
        assertEquals(10L, Constants.CONTENT_OBSERVER_DELAY_SECONDS)
    }

    // ========== Тесты уведомлений ==========

    @Test
    fun `проверка ID канала уведомлений`() {
        // Arrange & Act & Assert
        assertEquals("compression_channel", Constants.NOTIFICATION_CHANNEL_ID)
    }

    @Test
    fun `проверка ID уведомления сжатия`() {
        // Arrange & Act & Assert
        assertEquals(1, Constants.NOTIFICATION_ID_COMPRESSION)
    }

    @Test
    fun `проверка ID уведомления фонового сервиса`() {
        // Arrange & Act & Assert
        assertEquals(2, Constants.NOTIFICATION_ID_BACKGROUND_SERVICE)
    }

    @Test
    fun `проверка ID уведомления результата сжатия`() {
        // Arrange & Act & Assert
        assertEquals(4, Constants.NOTIFICATION_ID_COMPRESSION_RESULT)
    }

    // ========== Тесты директорий и файлов ==========

    @Test
    fun `проверка имени директории приложения`() {
        // Arrange & Act & Assert
        assertEquals("CompressPhotoFast", Constants.APP_DIRECTORY)
    }

    @Test
    fun `проверка суффикса сжатого файла`() {
        // Arrange & Act & Assert
        assertEquals("_compressed", Constants.COMPRESSED_FILE_SUFFIX)
    }

    // ========== Тесты WorkManager ==========

    @Test
    fun `проверка ключа URI изображения в WorkManager`() {
        // Arrange & Act & Assert
        assertEquals("image_uri", Constants.WORK_INPUT_IMAGE_URI)
    }

    @Test
    fun `проверка ключа качества сжатия в WorkManager`() {
        // Arrange & Act & Assert
        assertEquals("compression_quality", Constants.WORK_COMPRESSION_QUALITY)
    }

    @Test
    fun `проверка ключа batch ID в WorkManager`() {
        // Arrange & Act & Assert
        assertEquals("batch_id", Constants.WORK_BATCH_ID)
    }

    // ========== Тесты кодов запросов ==========

    @Test
    fun `проверка кода запроса удаления файла`() {
        // Arrange & Act & Assert
        assertEquals(12345, Constants.REQUEST_CODE_DELETE_FILE)
    }

    @Test
    fun `проверка кода запроса разрешения на удаление`() {
        // Arrange & Act & Assert
        assertEquals(12346, Constants.REQUEST_CODE_DELETE_PERMISSION)
    }

    // ========== Тесты действий BroadcastReceiver ==========

    @Test
    fun `проверка действия обработки изображения`() {
        // Arrange & Act & Assert
        assertEquals("com.compressphotofast.PROCESS_IMAGE", Constants.ACTION_PROCESS_IMAGE)
    }

    @Test
    fun `проверка действия завершения сжатия`() {
        // Arrange & Act & Assert
        assertEquals("com.compressphotofast.ACTION_COMPRESSION_COMPLETED", Constants.ACTION_COMPRESSION_COMPLETED)
    }

    @Test
    fun `проверка действия пропуска сжатия`() {
        // Arrange & Act & Assert
        assertEquals("com.compressphotofast.ACTION_COMPRESSION_SKIPPED", Constants.ACTION_COMPRESSION_SKIPPED)
    }

    // ========== Тесты временных файлов ==========

    @Test
    fun `проверка максимального возраста временного файла`() {
        // Arrange & Act & Assert
        assertEquals(30 * 60 * 1000L, Constants.TEMP_FILE_MAX_AGE)
    }
}
