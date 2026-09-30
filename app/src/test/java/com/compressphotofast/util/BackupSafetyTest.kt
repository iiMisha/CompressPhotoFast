package com.compressphotofast.util

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.compressphotofast.BaseUnitTest
import io.mockk.coEvery
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Страховка файловых операций: backup-реестр, восстановление после kill и
 * очистка временных файлов не должны терять оригинал.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BackupSafetyTest : BaseUnitTest() {

    private lateinit var context: Context
    private lateinit var target: File
    private lateinit var targetUri: Uri

    private val originalBytes = ByteArray(4096) { (it % 251).toByte() }

    @Before
    override fun setUp() {
        super.setUp()
        context = ApplicationProvider.getApplicationContext()
        target = File(context.filesDir, "photo.jpg").apply { writeBytes(originalBytes) }
        targetUri = Uri.fromFile(target)
        mockkObject(UriUtil)
        coEvery { UriUtil.isUriExistsSuspend(any(), any()) } answers { File(secondArg<Uri>().path!!).exists() }
    }

    @After
    override fun tearDown() {
        unmockkObject(UriUtil)
        BackupRegistry.getPendingBackups(context).keys.forEach { File(it).delete() }
        context.getSharedPreferences(Constants.PREF_FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        super.tearDown()
    }

    @Test
    fun `createBackup copies full content and registers it durably`() {
        val backup = BackupRegistry.createBackup(context, targetUri, "replace_backup_")

        assertNotNull(backup)
        assertArrayEquals(originalBytes, backup!!.readBytes())
        assertEquals(targetUri.toString(), BackupRegistry.getPendingBackups(context)[backup.absolutePath])
    }

    @Test
    fun `several backups for one uri coexist in registry`() {
        val first = BackupRegistry.createBackup(context, targetUri, "replace_backup_")!!
        val second = BackupRegistry.createBackup(context, targetUri, "exif_backup_")!!

        BackupRegistry.releaseBackup(context, second)

        assertTrue(first.absolutePath in BackupRegistry.getRegisteredBackupPaths(context))
        assertFalse(second.exists())
    }

    @Test
    fun `rollback restores truncated file and skips identical file`() {
        val backup = BackupRegistry.createBackup(context, targetUri, "replace_backup_")!!

        assertTrue("Неизменённый файл совпадает с backup", BackupRegistry.rollback(context, targetUri, backup))

        target.writeBytes(originalBytes.copyOf(100))
        assertTrue(BackupRegistry.rollback(context, targetUri, backup))
        assertArrayEquals(originalBytes, target.readBytes())
    }

    @Test
    fun `legacy registry format is still readable`() {
        val backup = File(BackupRegistry.getBackupDir(context), "replace_backup_legacy.jpg").apply { writeBytes(originalBytes) }
        context.getSharedPreferences(Constants.PREF_FILE_NAME, Context.MODE_PRIVATE).edit()
            .putString(Constants.PREF_PENDING_BACKUPS, "{\"$targetUri\":\"${backup.absolutePath}\"}")
            .commit()

        assertEquals(targetUri.toString(), BackupRegistry.getPendingBackups(context)[backup.absolutePath])
    }

    @Test
    fun `recovery restores file cut mid-write even if its header looks valid`() = runBlocking {
        val backup = BackupRegistry.createBackup(context, targetUri, "replace_backup_")!!
        backup.setLastModified(System.currentTimeMillis() - 60_000)
        // Kill посреди truncate-записи: начало файла на месте, хвоста нет
        target.writeBytes(originalBytes.copyOf(1000))

        BackupRecoveryHelper.recoverPendingBackups(context)

        assertArrayEquals(originalBytes, target.readBytes())
        assertTrue(BackupRegistry.getPendingBackups(context).isEmpty())
        assertFalse(backup.exists())
    }

    @Test
    fun `recovery keeps backup when neither restore nor copy is possible`() = runBlocking {
        val backup = BackupRegistry.createBackup(context, targetUri, "replace_backup_")!!
        backup.setLastModified(System.currentTimeMillis() - 60_000)
        target.delete()
        mockkObject(MediaStoreUtil)
        coEvery { MediaStoreUtil.createMediaStoreEntry(any(), any(), any(), any(), any()) } returns null
        try {
            BackupRecoveryHelper.recoverPendingBackups(context)
        } finally {
            unmockkObject(MediaStoreUtil)
        }

        assertTrue(backup.exists())
        assertTrue(backup.absolutePath in BackupRegistry.getRegisteredBackupPaths(context))
    }

    @Test
    fun `recovery ignores backups of live operations of current process`() = runBlocking {
        val backup = BackupRegistry.createBackup(context, targetUri, "replace_backup_")!!
        target.writeBytes(originalBytes.copyOf(10))

        BackupRecoveryHelper.recoverPendingBackups(context, processStartMs = System.currentTimeMillis() - 60_000)

        assertTrue(backup.exists())
        assertEquals(10, target.length().toInt())
    }

    @Test
    fun `temp cleaner never deletes registered backup regardless of age`() {
        val backup = BackupRegistry.createBackup(context, targetUri, "replace_backup_")!!
        val orphan = File(BackupRegistry.getBackupDir(context), "exif_marker_backup_orphan.jpg").apply { writeBytes(originalBytes) }
        val old = System.currentTimeMillis() - Constants.TEMP_FILE_MAX_AGE - 60_000
        backup.setLastModified(old)
        orphan.setLastModified(old)

        TempFilesCleaner.cleanupTempFiles(context)

        assertTrue(backup.exists())
        assertFalse(orphan.exists())
    }
}
