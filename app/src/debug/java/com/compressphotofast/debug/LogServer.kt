package com.compressphotofast.debug

import android.content.Context
import timber.log.Timber
import java.net.HttpURLConnection
import java.net.URL

/**
 * «Сервер логов» (только debug): HTTP POST журнала [FileLog] на VPS, где демон
 * `tools/log_receiver.py` (порт 8093) перезаписывает файл `~/CompressPhotoFast/log`.
 *
 * URL и токен (`python3 tools/log_receiver.py --print-token`) хранятся в
 * SharedPreferences `log_server_prefs`; токен передаётся в заголовке `X-Log-Token`.
 */
internal object LogServer {
    private const val PREFS_NAME = "log_server_prefs"
    private const val KEY_URL = "url"
    private const val KEY_TOKEN = "token"
    private const val LOG_ENDPOINT = "/log"

    /** Публичный IPv4 VPS + порт демона-приёмника. */
    const val DEFAULT_URL = "http://5.183.253.236:8093"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getUrl(context: Context): String = prefs(context).getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL
    fun getToken(context: Context): String = prefs(context).getString(KEY_TOKEN, "") ?: ""
    fun save(context: Context, url: String, token: String) =
        prefs(context).edit().putString(KEY_URL, normalizeUrl(url)).putString(KEY_TOKEN, token.trim()).apply()

    fun isConfigured(context: Context): Boolean = getToken(context).isNotBlank()

    /** Краткое описание для меню: URL приёмника или «Не настроен». */
    fun summary(context: Context): String = if (isConfigured(context)) normalizeUrl(getUrl(context)) else "Не настроен"

    /** trim + удаление завершающих `/`; пустая строка → [DEFAULT_URL]. */
    fun normalizeUrl(raw: String?): String {
        val trimmed = raw?.trim().orEmpty()
        return if (trimmed.isEmpty()) DEFAULT_URL else trimmed.trimEnd('/')
    }

    /** Блокирующая отправка (вызывать вне UI-потока). @return `null` при успехе, иначе причина ошибки. */
    fun upload(context: Context): String? {
        val token = getToken(context)
        if (token.isBlank()) return "не задан токен"
        val body = try {
            FileLog.file()?.takeIf { it.exists() }?.readBytes()
        } catch (_: Exception) {
            return "не удалось прочитать журнал"
        }
        if (body == null || body.isEmpty()) return "журнал пуст"
        val base = normalizeUrl(getUrl(context))
        return try {
            val conn = URL(base + LOG_ENDPOINT).openConnection() as HttpURLConnection
            try {
                conn.requestMethod = "POST"
                conn.connectTimeout = 10_000
                conn.readTimeout = 30_000
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(body.size)
                conn.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
                conn.setRequestProperty("X-Log-Token", token)
                conn.outputStream.use { it.write(body) }
                val code = conn.responseCode
                if (code in 200..299) {
                    Timber.i("Журнал отправлен на $base (HTTP $code)")
                    null
                } else {
                    Timber.w("Сервер логов отклонил запрос: HTTP $code")
                    "HTTP $code"
                }
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Timber.e(e, "Ошибка отправки журнала на сервер")
            e.localizedMessage ?: "сетевая ошибка"
        }
    }
}
