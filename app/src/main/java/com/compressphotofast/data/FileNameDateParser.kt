package com.compressphotofast.data

import java.util.Calendar
import java.util.TimeZone

/**
 * Извлекает дату съёмки из имени файла (epoch ms; локальная таймзона, если в имени
 * нет явного TZ-суффикса). Чистые функции без IO. Приоритет источников даты для
 * файлов без EXIF-дат; mtime намеренно не используется.
 *
 * Порядок шаблонов — первое совпадение выигрывает. Все цифровые шаблоны защищены
 * границами `(?<!\d)…(?!\d)`, чтобы не сматчиться внутри более длинной цифровой
 * последовательности (счётчики, числовые ID соцсетей); год-первый шаблоны
 * дополнительно не начинают матч после `цифра + разделитель` — иначе хвост
 * day-first имени (`31-12-2025_10-20`) распознавался бы как год-первая дата.
 *
 * Поддерживаются: epoch-ms/µs/нс с известными префиксами (`mmexport`,
 * `wx_camera_`, `snapchat-`) и цифровой basename (13/16/10 цифр), месяцы словами
 * по-английски (включая iOS-стиль `Jan 1, 2025 at 12;30;45`), компактные и
 * разделённые дата-время с годом первым (с `:` во времени и TZ-суффиксом
 * `Z`/`±HH[:MM]` при разделителе дата-время `T`/пробел), day-first с
 * разделителями `[-._/]` (fallback с DD-MM на MM-DD).
 *
 * Намеренно не парсится: 2-значные годы (`251201`, `25-12-01` — риск
 * счётчиков/ID), русские месяцы, слитный и пробельный day-first (`01122025`,
 * `01 02 2025`), числовые прогоны длины 9, 11–12, 15, 17–18 и 19+ без известного
 * префикса (19-значные снежинки Twitter/Instagram как наносекунды дают ложные
 * даты в окне валидации), счётчики без даты (`DSC01234`, `GOPR1234`),
 * `dd/MM` без года.
 */
object FileNameDateParser {

    /** Нижняя граница окна: 1990-01-01T00:00:00 локального времени. */
    const val MIN_VALID_MS = 631_152_000_000L

    /** Верхняя граница окна: сейчас + 1 сутки (константа, легко изменить). */
    const val MAX_FUTURE_WINDOW_MS = 24L * 60 * 60 * 1000

    // 1. Epoch с известным префиксом: WhatsApp/WeChat/Snapchat-экспорт (мс/µs/нс)
    private val EPOCH_PREFIX = Regex("""(?i)(?:mmexport|wx_camera_|snapchat-)(\d{13}|\d{16}|\d{19})(?!\d)""")

    // 2. Basename целиком из цифр проверяется без regex (см. parseDateMs)

    // 3. Месяц словами: Jan 1, 2025 / 01 Jan 2025 / 2025-Jan-01 / 01Jan2025
    //    (+ опциональное время, `;` — замена двоеточий, запрещённых на FAT)
    private const val MONTH_WORDS =
        "Jan(?:uary)?|Feb(?:ruary)?|Mar(?:ch)?|Apr(?:il)?|May|Jun(?:e)?|Jul(?:y)?|Aug(?:ust)?|" +
            "Sep(?:t(?:ember)?)?|Oct(?:ober)?|Nov(?:ember)?|Dec(?:ember)?"
    private const val MONTH_WORD = """(?<![A-Za-z])(?:$MONTH_WORDS)(?![A-Za-z])"""
    private val MONTH_NAME = Regex(
        """(?i)(?<!\d)(?:(?<yA>(19|20)\d{2})[-_ ](?<mA>$MONTH_WORD)[-_ ](?<dA>\d{1,2})""" +
            """|(?<mB>$MONTH_WORD)[ _](?<dB>\d{1,2}),?[ _](?<yB>(19|20)\d{2})""" +
            """|(?<dC>\d{1,2})[ _](?<mC>$MONTH_WORD)[ _](?<yC>(19|20)\d{2})""" +
            """|(?<dD>\d{1,2})(?<mD>$MONTH_WORD)(?<yD>(19|20)\d{2}))""" +
            """(?:[ T_]+(?:at )?(?<hh>[0-2]\d)[;:. _-](?<mi>[0-5]\d)(?:[;:. _-](?<ss>[0-5]\d))?)?(?!\d)"""
    )

    // 4. Компактные дата-время: 20250101123456, 20250101_123456, 20250101T123456
    private val COMPACT_DATETIME = Regex("""(?<!\d)(19|20)\d{2}[01]\d[0-3]\d[_\-. T]?[0-2]\d[0-5]\d[0-5]\d(?!\d)""")

    // 5a. Разделённые дата-время с зоной: 2025-01-01T12:30:45Z, 2025-01-01 12:30:45+03:00,
    //     2025-01-01 123045+0300. Разделитель дата-время — только T/пробел, чтобы
    //     дефисные мс-хвосты (`signal-…-123`) не съедались как ±HHMM.
    private val SEPARATED_TZ = Regex(
        """(?<!\d)(?<!\d[-_.])(19|20)\d{2}[-_.][01]\d[-_.][0-3]\d[ T]""" +
            """(?:[0-2]\d:[0-5]\d(?::[0-5]\d)?|[0-2]\d[0-5]\d[0-5]\d)(?:Z|[+-][01]\d:?[0-5]\d)?(?!\d)"""
    )
    private val TZ_SUFFIX = Regex("""(?:Z|[+-][01]\d:?[0-5]\d)$""")

    // 5b. Разделённые дата-время: 2025-01-02-03-04-05, 2025.01.01_12.30.45, 2025-01-01_12:30
    private val SEPARATED_DATETIME = Regex(
        """(?<!\d)(?<!\d[-_.])(19|20)\d{2}[-_.][01]\d[-_.][0-3]\d(?:[-_ ][0-2]\d[-_.:][0-5]\d(?:[-_.:][0-5]\d)?)?(?!\d)"""
    )

    // 6. Day-first: 01-02-2025, 31.12.2025_10-20-30; DD-MM, невалидно → MM-DD
    private val DAY_FIRST = Regex(
        """(?<!\d)(\d{1,2})[-._/](\d{1,2})[-._/]((?:19|20)\d{2})(?:[ T_]?([0-2]\d)[:._-]([0-5]\d)(?:[:._-]([0-5]\d))?)?(?!\d)"""
    )

    // 7. Только дата: 20250101, 2025-01-01, 2025.01.01
    private val DATE_ONLY = Regex("""(?<!\d)(?<!\d[-_.])(19|20)\d{2}[-_.]?[01]\d[-_.]?[0-3]\d(?!\d)""")

    private val ALL_DIGITS = Regex("""\d+""")
    private val SEPARATORS = Regex("""[_\-. T:]""")

    /**
     * @return epoch ms или null, если в имени нет надёжной даты
     */
    fun parseDateMs(displayName: String?): Long? {
        if (displayName.isNullOrEmpty()) return null
        val maxMs = System.currentTimeMillis() + MAX_FUTURE_WINDOW_MS

        fun valid(ms: Long): Long? = if (ms > MIN_VALID_MS && ms <= maxMs) ms else null

        EPOCH_PREFIX.find(displayName)?.let { match ->
            val digits = match.groupValues[1]
            val value = digits.toLong()
            val ms = when (digits.length) {
                13 -> value                       // epoch-ms
                16 -> value / 1000                // epoch-µs
                else -> value / 1_000_000         // epoch-ns
            }
            return valid(ms)
        }

        val base = displayName.substringBeforeLast('.')
        if (ALL_DIGITS.matches(base)) {
            when (base.length) {
                13 -> return valid(base.toLong())             // epoch-ms
                16 -> return valid(base.toLong() / 1000)      // epoch-µs
                10 -> return valid(base.toLong() * 1000)      // epoch-s
                // прочие длины (счётчики, ID) — не дата, но имя может
                // начинаться с компактной даты; проверка продолжается по шаблонам ниже
            }
        }

        MONTH_NAME.find(displayName)?.let { match ->
            val g = match.groups
            val year = (g["yA"] ?: g["yB"] ?: g["yC"] ?: g["yD"])?.value?.toInt()
            val monthWord = (g["mA"] ?: g["mB"] ?: g["mC"] ?: g["mD"])?.value
            val day = (g["dA"] ?: g["dB"] ?: g["dC"] ?: g["dD"])?.value?.toInt()
            if (year != null && monthWord != null && day != null) {
                val hour = g["hh"]?.value?.toInt() ?: 0
                val minute = g["mi"]?.value?.toInt() ?: 0
                val second = g["ss"]?.value?.toInt() ?: 0
                strictCalendarToMs(year, monthNumber(monthWord), day, hour, minute, second)?.let { return valid(it) }
            }
        }

        COMPACT_DATETIME.find(displayName)?.let { match ->
            return fromDigitRuns(match.value)?.let(::valid)
        }
        SEPARATED_TZ.find(displayName)?.let { match ->
            parseWithZone(match.value)?.let { return valid(it) }
        }
        SEPARATED_DATETIME.find(displayName)?.let { match ->
            return fromDigitRuns(match.value)?.let(::valid)
        }
        DAY_FIRST.find(displayName)?.let { match ->
            parseDayFirst(match)?.let { return valid(it) }
        }
        DATE_ONLY.find(displayName)?.let { match ->
            return fromDigitRuns(match.value)?.let(::valid)
        }
        return null
    }

    private fun monthNumber(word: String): Int = when (word.take(3).lowercase()) {
        "jan" -> 1
        "feb" -> 2
        "mar" -> 3
        "apr" -> 4
        "may" -> 5
        "jun" -> 6
        "jul" -> 7
        "aug" -> 8
        "sep" -> 9
        "oct" -> 10
        "nov" -> 11
        "dec" -> 12
        else -> 0
    }

    /**
     * Разбирает совпавший фрагмент, очищенный от разделителей, позиционно:
     * yyyyMMdd[HHmm[ss]]. Мс-хвосты (`signal-…-123`) отсекаются границами шаблонов.
     */
    private fun fromDigitRuns(fragment: String): Long? {
        val digits = SEPARATORS.replace(fragment, "")
        if (digits.length !in listOf(8, 12, 14)) return null
        return digitsToMs(digits, TimeZone.getDefault())
    }

    /** Разбор фрагмента 5a с TZ-суффиксом: `Z` → UTC, `±HH[:MM]` → фиксированная зона. */
    private fun parseWithZone(fragment: String): Long? {
        val tz = TZ_SUFFIX.find(fragment) ?: return null
        val core = fragment.removeRange(tz.range)
        val digits = SEPARATORS.replace(core, "")
        if (digits.length !in listOf(12, 14)) return null
        val zone = when (tz.value) {
            "Z" -> TimeZone.getTimeZone("UTC")
            else -> {
                val numbers = tz.value.substring(1).replace(":", "")
                val hours = numbers.substring(0, 2).toInt()
                val minutes = numbers.substring(2, 4).toInt()
                if (hours > 14 || minutes > 59) return null
                TimeZone.getTimeZone("GMT${tz.value[0]}${"%02d".format(hours)}:${"%02d".format(minutes)}")
            }
        }
        return digitsToMs(digits, zone)
    }

    /** Day-first: DD-MM-YYYY; календарно невалидно → MM-DD-YYYY; обе невалидны → null. */
    private fun parseDayFirst(match: MatchResult): Long? {
        val g = match.groupValues
        val a = g[1].toInt()
        val b = g[2].toInt()
        val year = g[3].toInt()
        val hour = g.getOrNull(4)?.ifEmpty { null }?.toInt() ?: 0
        val minute = g.getOrNull(5)?.ifEmpty { null }?.toInt() ?: 0
        val sec = g.getOrNull(6)?.ifEmpty { null }?.toInt() ?: 0
        return strictCalendarToMs(year, b, a, hour, minute, sec)
            ?: strictCalendarToMs(year, a, b, hour, minute, sec)
    }

    private fun digitsToMs(digits: String, zone: TimeZone): Long? {
        val year = digits.substring(0, 4).toInt()
        val month = digits.substring(4, 6).toInt()
        val day = digits.substring(6, 8).toInt()
        val hour = if (digits.length >= 12) digits.substring(8, 10).toInt() else 0
        val minute = if (digits.length >= 12) digits.substring(10, 12).toInt() else 0
        val second = if (digits.length == 14) digits.substring(12, 14).toInt() else 0
        return strictCalendarToMs(year, month, day, hour, minute, second, zone)
    }

    /** Strict-календарь: 31 февраля, месяц 13, час 24+ — reject. */
    private fun strictCalendarToMs(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int,
        zone: TimeZone = TimeZone.getDefault()
    ): Long? {
        return try {
            val calendar = Calendar.getInstance(zone)
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
