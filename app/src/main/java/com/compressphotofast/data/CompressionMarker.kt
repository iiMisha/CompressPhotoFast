package com.compressphotofast.data

/**
 * Информация о маркере сжатия из EXIF UserComment.
 * Формат: CompressPhotoFast_Compressed:quality:timestamp:size:origSize
 *
 * @param isCompressed изображение было сжато ранее
 * @param quality качество сжатия или -1, если неизвестно
 * @param timestamp временная метка сжатия в миллисекундах или 0L, если неизвестна
 * @param fileSize размер файла в байтах на момент записи маркера;
 *                 null — старый формат маркера без размера, HEIC-маркер
 *                 или незавершённая двухфазная запись (заглушка)
 * @param originalFileSize исходный размер файла в байтах до сжатия;
 *                 null — старый формат маркера (без поля), HEIC-маркер
 *                 или неизвестный на момент записи размер
 */
data class CompressionMarkerInfo(
    val isCompressed: Boolean,
    val quality: Int,
    val timestamp: Long,
    val fileSize: Long?,
    val originalFileSize: Long? = null
) {
    companion object {
        val NOT_COMPRESSED = CompressionMarkerInfo(false, -1, 0L, null)
    }
}

/**
 * Формат маркера сжатия: сборка и разбор строки UserComment и HEIC-суффикса имени.
 * Чистые функции без IO; чтение/запись EXIF — в [ExifUtil].
 * Формат должен совпадать с CLI (`compressphotofast-cli`).
 */
object CompressionMarker {

    const val PREFIX = "CompressPhotoFast_Compressed"

    // Фиксированная ширина поля размера в маркере (ведущие нули).
    // Фаза 1 пишет заглушку из нулей, фаза 2 — реальный размер той же длины:
    // длина строки маркера не меняется, поэтому размер заглушки и финального
    // размера всегда укладываются в одно поле фиксированной ширины.
    const val SIZE_FIELD_WIDTH = 15

    // Фиксированная ширина поля исходного размера (до сжатия). Известен до
    // начала сжатия, поэтому пишется уже в фазе 1; фаза 2 перезаписывает
    // только поле [SIZE_FIELD_WIDTH], не трогая это поле.
    const val ORIG_SIZE_FIELD_WIDTH = 15

    /** Суффикс имени HEIC-файла, заменяющий EXIF-маркер. */
    const val HEIC_COMPRESSED_SUFFIX = "_compressed"

    private val HEIC_COMPRESSED_NAME = Regex("""_compressed\.(heic|heif)$""", RegexOption.IGNORE_CASE)

    /**
     * Собирает строку маркера сжатия. При size == null используется заглушка
     * из нулей фиксированной ширины [SIZE_FIELD_WIDTH].
     * При originalSize == null — заглушка [ORIG_SIZE_FIELD_WIDTH].
     */
    fun build(quality: Int, timestamp: Long, size: Long?, originalSize: Long?): String {
        val sizeField = (size ?: 0L).toString().padStart(SIZE_FIELD_WIDTH, '0')
        val origSizeField = (originalSize ?: 0L).toString().padStart(ORIG_SIZE_FIELD_WIDTH, '0')
        return "$PREFIX:$quality:$timestamp:$sizeField:$origSizeField"
    }

    /** Маркер с указанным качеством присутствует в UserComment (для верификации записи). */
    fun hasMarkerWithQuality(userComment: String?, quality: Int): Boolean =
        userComment?.contains("$PREFIX:$quality") == true

    /**
     * Разбирает UserComment.
     * @return информацию о маркере или null, если строка не является корректным маркером
     * @throws NumberFormatException если качество или timestamp не числа
     */
    fun parse(userComment: String?): CompressionMarkerInfo? {
        if (userComment.isNullOrEmpty() || !userComment.startsWith(PREFIX)) return null
        // CompressPhotoFast_Compressed:70:1742629672908:000000123456789:000000123456789
        val parts = userComment.split(":")
        if (parts.size < 3) return null
        val quality = parts[1].toInt()
        val timestamp = parts[2].toLong()
        // Размер: заглушка из нулей, мусор или отсутствие поля трактуется как null
        val fileSize = parts.getOrNull(3)?.toLongOrNull()?.takeIf { it > 0L }
        // Исходный размер: только в расширенном формате (5-е поле);
        // в старом 4-полевом формате отсутствует — null
        val originalFileSize = parts.getOrNull(4)?.toLongOrNull()?.takeIf { it > 0L }
        return CompressionMarkerInfo(true, quality, timestamp, fileSize, originalFileSize)
    }

    /**
     * Проверяет, есть ли у HEIC файла суффикс _compressed перед расширением .heic/.heif
     * @param displayName Имя файла (displayName из MediaStore)
     */
    fun hasHeicCompressedSuffix(displayName: String?): Boolean =
        displayName != null && HEIC_COMPRESSED_NAME.containsMatchIn(displayName)
}
