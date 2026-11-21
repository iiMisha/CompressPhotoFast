package com.compressphotofast.data

import java.util.Calendar

/**
 * Извлекает дату съёмки из имени файла (epoch ms, локальная таймзона).
 * Чистые функции без IO. Приоритет источников даты для файлов без EXIF-дат;
 * mtime намеренно не используется.
 *
 * Порядок шаблонов — первое совпадение выигрывает. Все цифровые шаблоны защищены
 * границами `(?<!\d)…(?!\d)`, чтобы не сматчиться внутри более длинной
 * цифровой последовательности (счётчики, числовые ID соцсетей).
 *
 * Намеренно не парсится: dd-MM-yyyy / dd/MM/yyyy (день и месяц неоднозначны),
 * месяцы словами (`Jan 2025`), числовые прогоны длины 9, 11–12, 14+ как epoch,
 * счётчики без даты (`DSC01234`, `GOPR1234`).
 */
object FileNameDateParser {

    /** Нижняя граница окна: 1990-01-01T00:00:00 локального времени. */
    const val MIN_VALID_MS = 631_152_000_000L

    /** Верхняя граница окна: сейчас + 1 сутки (константа, легко изменить). */
    const val MAX_FUTURE_WINDOW_MS = 24L * 60 * 60 * 1000

    // 1. Epoch-ms с известным префиксом: WhatsApp/WeChat/Snapchat-экспорт
    private val EPOCH_MS_PREFIX = Regex("""(?i)(?:mmexport|wx_camera_|snapchat-)(\d{13})(?!\d)""")

    // 2. Basename целиком из цифр проверяется без regex (см. parseDateMs)

    // 3. Компактные дата-время: 20250101123456, 20250101_123456, 20250101T123456
    private val COMPACT_DATETIME = Regex("""(?<!\d)(19|20)\d{2}[01]\d[0-3]\d[_\-. T]?[0-2]\d[0-5]\d[0-5]\d(?!\d)""")

    // 4. Разделённые дата-время: 2025-01-02-03-04-05, 2025.01.01_12.30.45, 2025-01-01
    private val SEPARATED_DATETIME = Regex(
        """(?<!\d)(19|20)\d{2}[-_.][01]\d[-_.][0-3]\d(?:[-_ ][0-2]\d[-_.][0-5]\d(?:[-_.][0-5]\d)?)?(?!\d)"""
    )

    // 5. Только дата: 20250101, 2025-01-01, 2025.01.01
    private val DATE_ONLY = Regex("""(?<!\d)(19|20)\d{2}[-_.]?[01]\d[-_.]?[0-3]\d(?!\d)""")

    private val ALL_DIGITS = Regex("""\d+""")
    private val SEPARATORS = Regex("""[_\-. T]""")

    /**
     * @return epoch ms или null, если в имени нет надёжной даты
     */
    fun parseDateMs(displayName: String?): Long? {
        if (displayName.isNullOrEmpty()) return null
        val maxMs = System.currentTimeMillis() + MAX_FUTURE_WINDOW_MS

        fun valid(ms: Long): Long? = if (ms > MIN_VALID_MS && ms <= maxMs) ms else null

        EPOCH_MS_PREFIX.find(displayName)?.let { match ->
            return valid(match.groupValues[1].toLong())
        }

        val base = displayName.substringBeforeLast('.')
        if (ALL_DIGITS.matches(base)) {
            when (base.length) {
                13 -> return valid(base.toLong())                // epoch-ms
                10 -> return valid(base.toLong() * 1000)         // epoch-s
                // прочие длины (счётчики, ID) — не дата, но имя может
                // начинаться с компактной даты; проверка продолжается по шаблонам ниже
            }
        }

        COMPACT_DATETIME.find(displayName)?.let { match ->
            return fromDigitRuns(match.value)?.let(::valid)
        }
        SEPARATED_DATETIME.find(displayName)?.let { match ->
            return fromDigitRuns(match.value)?.let(::valid)
        }
        DATE_ONLY.find(displayName)?.let { match ->
            return fromDigitRuns(match.value)?.let(::valid)
        }
        return null
    }

    /**
     * Разбирает совпавший фрагмент, очищенный от разделителей, позиционно:
     * yyyyMMdd[HHmm[ss]]. Мс-хвосты (`signal-…-123`) отсекаются границами шаблонов.
     */
    private fun fromDigitRuns(fragment: String): Long? {
        val digits = SEPARATORS.replace(fragment, "")
        if (digits.length !in listOf(8, 12, 14)) return null
        val year = digits.substring(0, 4).toInt()
        val month = digits.substring(4, 6).toInt()
        val day = digits.substring(6, 8).toInt()
        val hour = if (digits.length >= 12) digits.substring(8, 10).toInt() else 0
        val minute = if (digits.length >= 12) digits.substring(10, 12).toInt() else 0
        val second = if (digits.length == 14) digits.substring(12, 14).toInt() else 0
        return strictCalendarToMs(year, month, day, hour, minute, second)
    }

    /** Strict-календарь: 31 февраля, месяц 13, час 24+ — reject. */
    private fun strictCalendarToMs(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long? {
        return try {
            val calendar = Calendar.getInstance()
            calendar.clear()
            calendar.isLenient = false
            calendar.set(year, month - 1, day, hour, minute, second)
            calendar.set(Calendar.MILLISECOND, 0)
            calendar.timeInMillis
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}
