package com.compressphotofast.util

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.compressphotofast.BaseUnitTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicLong

/**
 * Unit-тесты для [UriProcessingTracker].
 *
 * Фокус — самоисцеление залипших (stale) блокировок в processingUris: именно из-за их
 * отсутствия ~35 фото без EXIF-маркера бесконечно пропускались как "уже обрабатывается"
 * (см. баг: пустой батч, 0 фото сжато). Тесты покрывают:
 *  - базовый acquire/release (регрессия блокировки);
 *  - точечное самоисцеление stale в addProcessingUriSafe (главный тест бага);
 *  - периодическую cleanup через query-шлюзы (isProcessing/isImageBeingProcessed).
 *
 * Изоляция: каждый тест использует уникальный URI (через счётчик), чтобы не зависеть от
 * состояния singleton между тестами. Это устойчивее, чем сброс fallbackInstance.
 */
@RunWith(RobolectricTestRunner::class)
class UriProcessingTrackerTest : BaseUnitTest() {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val uriCounter = AtomicLong(0)

    /** Уникальный URI для каждого теста — изоляция от состояния singleton. */
    private fun freshUri(): Uri =
        Uri.parse("content://media/external/images/media/${uriCounter.incrementAndGet()}")

    private lateinit var tracker: UriProcessingTracker

    @Before
    override fun setUp() {
        super.setUp()
        resetSingleton()
        tracker = UriProcessingTracker.getInstance(context)
        // Большой порог по умолчанию — чтобы быстрые тесты (acquire/release) не считали
        // свежие блокировки stale из-за накладных расходов на логирование/корутины.
        // Stale-сценарии переопределяют порог локально через withSmallStaleThreshold.
        tracker.staleUriThresholdMs = 60_000L
    }

    /**
     * Локально устанавливает малый stale-порог для конкретного stale-теста и возвращает
     * реальную задержку, гарантированно превышающую порог.
     */
    private fun withSmallStaleThreshold(): Long {
        tracker.staleUriThresholdMs = 50L
        return 80L
    }

    // ==================== Базовый acquire/release ====================

    @Test
    fun `addProcessingUriSafe первый вызов возвращает true`() = runBlocking {
        val uri = freshUri()
        val added = tracker.addProcessingUriSafe(uri, "test")
        assertTrue("Первый acquire должен пройти", added)
    }

    @Test
    fun `addProcessingUriSafe повторный вызов возвращает false`() = runBlocking {
        val uri = freshUri()
        assertTrue(tracker.addProcessingUriSafe(uri, "test"))
        val added = tracker.addProcessingUriSafe(uri, "test")
        assertFalse("Повторный acquire того же URI должен быть отклонён", added)
    }

    @Test
    fun `removeProcessingUriSafe позволяет повторный acquire`() = runBlocking {
        val uri = freshUri()
        assertTrue(tracker.addProcessingUriSafe(uri, "test"))
        tracker.removeProcessingUriSafe(uri)
        val added = tracker.addProcessingUriSafe(uri, "test")
        assertTrue("После release повторный acquire должен пройти", added)
    }

    // ==================== Самоисцеление stale (главный тест бага) ====================

    @Test
    fun `stale блокировка перевыдаётся при повторном acquire`() = runBlocking {
        val uri = freshUri()
        val waitMs = withSmallStaleThreshold()
        // Захватываем URI (как это делает handleImage перед постановкой Worker).
        assertTrue(tracker.addProcessingUriSafe(uri, "ImageProcessingUtil"))

        // Имитируем, что Worker не доработал и не снял блокировку в finally,
        // а времени прошло больше stale-порога.
        Thread.sleep(waitMs)

        // Тот же самый путь, что в логе: processUncompressedImages -> compressMultipleImages
        // -> handleImage -> addProcessingUriSafe. Раньше вернул бы false -> пустой батч -> 0 фото.
        val readded = tracker.addProcessingUriSafe(uri, "ImageProcessingUtil")

        assertTrue(
            "Stale-блокировка должна быть снята и перевыдана (true), а не пропущена",
            readded
        )
    }

    @Test
    fun `молодая блокировка НЕ перевыдаётся`() = runBlocking {
        val uri = freshUri()
        assertTrue(tracker.addProcessingUriSafe(uri, "test"))
        // Сразу повторяем — блокировка свежая, не stale.
        val readded = tracker.addProcessingUriSafe(uri, "test")
        assertFalse("Свежая блокировка не должна перевыдаваться", readded)
    }

    // ==================== Периодическая cleanup в query-шлюзах ====================

    @Test
    fun `isProcessing возвращает false после устаревания и cleanup`() = runBlocking {
        val uri = freshUri()
        val waitMs = withSmallStaleThreshold()
        assertTrue(tracker.addProcessingUriSafe(uri, "test"))
        assertTrue("Сразу после acquire URI считается обрабатываемым", tracker.isProcessing(uri))

        // Ждём stale-порог + принудительно обнуляем троттлинг, чтобы cleanup сработала.
        Thread.sleep(waitMs)
        tracker.resetStaleCleanupThrottleForTest()

        assertFalse(
            "После stale-устаревания isProcessing должен вернуть false",
            tracker.isProcessing(uri)
        )
    }

    @Test
    fun `isImageBeingProcessed возвращает false после устаревания и cleanup`() = runBlocking {
        val uri = freshUri()
        val waitMs = withSmallStaleThreshold()
        assertTrue(tracker.addProcessingUriSafe(uri, "test"))
        assertTrue(
            "Сразу после acquire isImageBeingProcessed true",
            tracker.isImageBeingProcessed(uri)
        )

        Thread.sleep(waitMs)
        tracker.resetStaleCleanupThrottleForTest()

        assertFalse(
            "После stale-устаревания isImageBeingProcessed должен вернуть false",
            tracker.isImageBeingProcessed(uri)
        )
    }

    // ==================== Хелперы изоляции ====================

    /**
     * Сбрасывает статический singleton [UriProcessingTracker], чтобы минимизировать влияние
     * состояния между тестами. Пробует оба варианта расположения companion-поля
     * (внешний класс и Companion), т.к. компилятор может генерировать по-разному.
     * Дополнительно каждый тест использует уникальный URI.
     */
    private fun resetSingleton() {
        for (klass in listOf(
            UriProcessingTracker::class.java,
            UriProcessingTracker.Companion::class.java
        )) {
            try {
                val field = klass.getDeclaredField("fallbackInstance").apply { isAccessible = true }
                field.set(null, null)
            } catch (e: NoSuchFieldException) {
                // Поле в этом классе не найдено — пробуем следующий вариант
            }
        }
    }
}
