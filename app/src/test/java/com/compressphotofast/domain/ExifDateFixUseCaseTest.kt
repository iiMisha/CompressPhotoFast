package com.compressphotofast.domain

import android.content.ContentResolver
import android.content.Context
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.compressphotofast.data.CompressionMarker
import com.compressphotofast.data.ExifUtil
import com.compressphotofast.data.MediaItemSnapshot
import com.compressphotofast.data.UriUtil
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Проход восстановления дат: happy path (дата из имени), файл без источников,
 * HEIC-пропуск; сериализация через CompressionExecutionGate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ExifDateFixUseCaseTest {

    private lateinit var context: Context

    private val uri1 = Uri.parse("content://media/external/images/media/1")
    private val uri2 = Uri.parse("content://media/external/images/media/2")
    private val uri3 = Uri.parse("content://media/external/images/media/3")

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        val resolver = mockk<ContentResolver>()
        every { context.contentResolver } returns resolver
        every { resolver.query(any(), any(), any(), any(), any()) } answers {
            MatrixCursor(arrayOf(MediaStore.MediaColumns._ID)).apply {
                addRow(arrayOf(1L)); addRow(arrayOf(2L)); addRow(arrayOf(3L))
            }
        }

        mockkObject(MediaItemSnapshot, ExifUtil, UriUtil)
        coEvery { UriUtil.isPendingEffective(any(), any()) } returns false

        val snapshot1 = snapshot(uri1, "IMG_20250101_123456.jpg", "image/jpeg")
        val snapshot2 = snapshot(uri2, "DSC01234.jpg", "image/jpeg")
        val snapshot3 = snapshot(uri3, "IMG_0001.heic", "image/heic")
        coEvery { MediaItemSnapshot.query(context, uri1) } returns snapshot1
        coEvery { MediaItemSnapshot.query(context, uri2) } returns snapshot2
        coEvery { MediaItemSnapshot.query(context, uri3) } returns snapshot3

        val exif = mockk<ExifInterface> {
            every { getAttribute(ExifInterface.TAG_USER_COMMENT) } returns null
        }
        coEvery { ExifUtil.getExifInterface(context, uri1) } returns exif
        coEvery { ExifUtil.getExifInterface(context, uri2) } returns exif
        coEvery { ExifUtil.hasAnyDate(any()) } returns false
        coEvery {
            ExifUtil.writeMissingDates(context, uri1, any(), includeDateTimeOriginal = true)
        } returns true
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    private fun snapshot(uri: Uri, name: String, mime: String) = MediaItemSnapshot(
        uri = uri,
        displayName = name,
        relativePath = "DCIM/",
        mimeType = mime,
        size = 1000L,
        isPendingRaw = false,
        dateAddedSec = 1_700_000_000L,
        dateModifiedSec = 1_700_000_000L
    )

    @Test
    fun `happy path - фикс из имени, файл без источников, HEIC пропущен`() = runTest {
        val useCase = ExifDateFixUseCase(CompressionExecutionGate())

        val outcome = useCase.fixAll(context)

        assertEquals(3, outcome.scanned)
        assertEquals(1, outcome.fixed)
        assertEquals(1, outcome.noSource)
        assertEquals(0, outcome.failed)
        // HEIC: ни чтения EXIF, ни записи
        coVerify(exactly = 0) { ExifUtil.getExifInterface(context, uri3) }
        coVerify(exactly = 0) {
            ExifUtil.writeMissingDates(context, uri2, any(), any())
        }
    }

    @Test
    fun `дата из маркера сжатия - запись без DateTimeOriginal`() = runTest {
        val marker = CompressionMarker.build(85, 1_700_000_123_456L, 500L, 4000L)
        val exif = mockk<ExifInterface> {
            every { getAttribute(ExifInterface.TAG_USER_COMMENT) } returns marker
        }
        coEvery { ExifUtil.getExifInterface(context, uri2) } returns exif
        coEvery {
            ExifUtil.writeMissingDates(context, uri2, 1_700_000_123_456L, includeDateTimeOriginal = false)
        } returns true

        val useCase = ExifDateFixUseCase(CompressionExecutionGate())
        val outcome = useCase.fixAll(context)

        assertEquals(2, outcome.fixed)
        assertEquals(0, outcome.noSource)
    }

    @Test
    fun `прогресс - сканирование, рост по файлам, null после завершения`() = runTest {
        val useCase = ExifDateFixUseCase(CompressionExecutionGate())

        assertNull(useCase.progress.value)
        val outcome = useCase.fixAll(context)
        assertEquals(3, outcome.scanned)

        // Прогресс сброшен после завершения; итоговый снапшот сохранён
        assertNull(useCase.progress.value)
        assertEquals(3, useCase.lastOutcome?.scanned)
        assertEquals(1, useCase.lastOutcome?.fixed)
        assertEquals(1, useCase.lastOutcome?.noSource)
        assertEquals(0, useCase.lastOutcome?.failed)
    }

    @Test
    fun `отмена - CE проброшен, частичный итог в lastOutcome, progress сброшен`() = runTest {
        val useCase = ExifDateFixUseCase(CompressionExecutionGate())

        // Останавливаем проход после первого файла
        val stopGate = kotlinx.coroutines.channels.Channel<Unit>(1)
        coEvery { MediaItemSnapshot.query(context, uri2) } coAnswers {
            stopGate.send(Unit)
            awaitCancellation()
        }

        val job = launch { useCase.fixAll(context) }
        stopGate.receive()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertEquals(null, useCase.progress.value)
        // uri1 обработан (fixed=1), uri2 завис в snapshot-запросе — scanned уже засчитан
        assertEquals(2, useCase.lastOutcome?.scanned)
        assertEquals(3, useCase.lastOutcome?.total)
        assertEquals(1, useCase.lastOutcome?.fixed)
    }

    @Test
    fun `наложение проходов - отмена старого не сбрасывает прогресс нового`() = runTest {
        val useCase = ExifDateFixUseCase(CompressionExecutionGate())

        // Проход 1 зависает в snapshot-запросе uri1, удерживая gate
        val hang = Channel<Unit>(1)
        coEvery { MediaItemSnapshot.query(context, uri1) } coAnswers {
            hang.send(Unit)
            awaitCancellation()
        }

        val job1 = launch { useCase.fixAll(context) }
        hang.receive()

        // Проход 2 (REPLACE): новое поколение; блокируется на gate
        val job2 = launch { useCase.fixAll(context) }
        // Ждём фактического старта прохода 2, иначе отмена прохода 1
        // опередит инкремент поколения и гонка станет недетерминированной
        while (useCase.currentGeneration < 2) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                Thread.sleep(1)
            }
        }

        // Отмена старого прохода: finally не должен обнулить прогресс нового
        job1.cancelAndJoin()
        assertEquals(ExifDateFixUseCase.Progress(1, 3, 0, 0, 0), useCase.progress.value)
        assertNull(useCase.lastOutcome)

        // Отмена нового прохода — прогресс сбрасывается (поколение совпадает)
        job2.cancelAndJoin()
        assertNull(useCase.progress.value)
        assertEquals(1, useCase.lastOutcome?.scanned)
        assertEquals(3, useCase.lastOutcome?.total)
    }
}
