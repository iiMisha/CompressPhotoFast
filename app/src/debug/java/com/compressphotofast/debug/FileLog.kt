package com.compressphotofast.debug

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import timber.log.Timber

/**
 * Файловый журнал debug-сборки: `filesDir/logs/compressphotofast.log`.
 * Его отправляет на сервер логов [LogServer]. Пишется через Timber-дерево [FileTree].
 */
internal object FileLog {
    private const val MAX_SIZE = 512 * 1024L
    private const val KEEP_SIZE = 256 * 1024
    private const val FILE_NAME = "compressphotofast.log"

    @Volatile
    private var file: File? = null

    fun init(context: Context) {
        file = File(context.filesDir, "logs").apply { mkdirs() }.let { File(it, FILE_NAME) }
    }

    fun file(): File? = file

    /** Очищает файл журнала (logcat не затрагивается). @return `false`, если файл не удалось очистить. */
    @Synchronized
    fun clear(): Boolean = try {
        file?.writeBytes(ByteArray(0))
        true
    } catch (_: Exception) {
        false
    }

    @Synchronized
    fun write(priority: Int, tag: String?, msg: String, t: Throwable?) {
        try {
            val f = file ?: return
            if (f.length() > MAX_SIZE) trim(f)
            val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val trace = t?.let { "\n" + it.stackTraceToString() } ?: ""
            val tagPart = tag?.let { "$it: " } ?: ""
            f.appendText("$time ${levelOf(priority)} [${Thread.currentThread().name}] $tagPart$msg$trace\n")
        } catch (_: Exception) {
            // Журнал не должен ронять приложение
        }
    }

    private fun levelOf(priority: Int): Char = when (priority) {
        Log.VERBOSE -> 'V'
        Log.DEBUG -> 'D'
        Log.INFO -> 'I'
        Log.WARN -> 'W'
        Log.ERROR -> 'E'
        else -> 'A'
    }

    /** Оставляет последние [KEEP_SIZE] байт, начиная с целой строки. */
    private fun trim(f: File) {
        val tail = ByteArray(KEEP_SIZE)
        RandomAccessFile(f, "r").use { it.seek(it.length() - KEEP_SIZE); it.readFully(tail) }
        val start = tail.indexOf('\n'.code.toByte()) + 1
        f.writeBytes(tail.copyOfRange(start, KEEP_SIZE))
    }
}

/** Пишет всё, что проходит через Timber, в [FileLog]. */
internal class FileTree : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        FileLog.write(priority, tag, message, t)
    }
}
