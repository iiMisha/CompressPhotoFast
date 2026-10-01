package com.compressphotofast.util

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.system.Os
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Низкоуровневые durable-операции с файлами и дескрипторами.
 * Листовой модуль: зависит только от [LogUtil].
 */
object FileIoUtil {

    /**
     * Буфер durable-копий: через FUSE каждый write() — отдельный запрос
     * к MediaProvider, буфер `copyTo` по умолчанию (8 КБ) дробит файл на сотни запросов.
     */
    const val COPY_BUFFER_SIZE = 256 * 1024

    /**
     * Фактический размер файла через дескриптор (fstat → statSize), в обход
     * кэша MediaStore. Перехват Throwable вокруг fstat нужен, т.к. в JVM-среде
     * (Robolectric) нативный вызов может быть недоступен.
     *
     * @return размер в байтах или null, если его не удалось определить (или файл пуст)
     */
    fun getDescriptorSize(context: Context, uri: Uri): Long? {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                try {
                    Os.fstat(pfd.fileDescriptor).st_size.takeIf { it > 0L }
                } catch (t: Throwable) {
                    null
                } ?: pfd.statSize.takeIf { it > 0L }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Открывает поток с неотредактированным содержимым файла MediaStore
     * ([MediaStore.setRequireOriginal]): без него MediaProvider может отдать
     * копию с вырезанной геолокацией, и restore из такого backup затёр бы GPS.
     * При отсутствии прав или для не-MediaStore URI — обычный поток.
     */
    fun openOriginalInputStream(context: Context, uri: Uri): InputStream? {
        if (uri.authority == MediaStore.AUTHORITY) {
            try {
                context.contentResolver.openInputStream(MediaStore.setRequireOriginal(uri))?.let { return it }
            } catch (e: Exception) {
                // Нет ACCESS_MEDIA_LOCATION или URI не поддерживает оригинал — читаем как есть
            }
        }
        return context.contentResolver.openInputStream(uri)
    }

    /**
     * Копирует поток в файл и сбрасывает данные на носитель (fsync).
     * @return количество записанных байт
     */
    fun writeDurably(input: InputStream, target: File): Long {
        return FileOutputStream(target).use { output ->
            val copied = input.copyTo(output, COPY_BUFFER_SIZE)
            output.flush()
            output.fd.sync()
            copied
        }
    }

    /**
     * Сбрасывает на носитель данные файла по URI (fsync по дескриптору чтения:
     * на Linux fsync применим к любому открытому дескриптору, а открытие на
     * чтение не вызывает у MediaProvider рескан при закрытии).
     *
     * @return true, если fsync выполнен
     */
    fun syncUri(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                pfd.fileDescriptor.sync()
                true
            } ?: false
        } catch (e: Exception) {
            LogUtil.warning(uri, "fsync", "Не удалось сбросить файл на носитель: ${e.message}")
            false
        }
    }
}
