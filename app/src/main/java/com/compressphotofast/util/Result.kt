package com.compressphotofast.util

/**
 * Минимальный Result pattern для критических файловых операций
 *
 * Используется для возврата результата операции с детализацией возможных ошибок.
 * Применяется только для критических операций, где важна дифференциация ошибок.
 *
 * @param T Тип успешного результата
 */
sealed class FileOperationResult<out T> {
    /**
     * Успешное выполнение операции с результатом
     */
    data class Success<T>(val data: T) : FileOperationResult<T>()

    /**
     * Ошибка выполнения операции с детализацией типа ошибки
     */
    data class Error(
        val errorType: FileErrorType,
        val message: String,
        val cause: Throwable? = null
    ) : FileOperationResult<Nothing>()
}

/**
 * Типы ошибок для детализации и корректной обработки
 */
enum class FileErrorType {
    /** Файл не найден */
    FILE_NOT_FOUND,
    /** Отказано в разрешении */
    PERMISSION_DENIED,
    /** Недостаточно памяти */
    OUT_OF_MEMORY,
    /** Некорректный URI */
    INVALID_URI,
    /** Недостаточно места на диске */
    DISK_FULL,
    /** Ошибка ввода/вывода */
    IO_ERROR,
    /** Неизвестная ошибка */
    UNKNOWN
}

/**
 * Возвращает результат или значение по умолчанию при ошибке
 *
 * @param defaultValue Значение, которое будет возвращено при ошибке
 * @return Результат операции или defaultValue
 */
fun <T> FileOperationResult<T>.getOrElse(defaultValue: T): T {
    return when (this) {
        is FileOperationResult.Success -> data
        is FileOperationResult.Error -> defaultValue
    }
}

/**
 * Выполняет действие при ошибке, если результат является Error
 *
 * @param action Действие, выполняемое при ошибке. Принимает тип ошибки, сообщение и причину
 * @return тот же результат для цепочки вызовов
 */
fun <T> FileOperationResult<T>.onError(action: (FileErrorType, String, Throwable?) -> Unit): FileOperationResult<T> {
    if (this is FileOperationResult.Error) {
        action(errorType, message, cause)
    }
    return this
}

/**
 * Проверяет, является ли результат успешным
 */
fun <T> FileOperationResult<T>.isSuccess(): Boolean = this is FileOperationResult.Success

/**
 * Проверяет, является ли результат ошибкой
 */
fun <T> FileOperationResult<T>.isError(): Boolean = this is FileOperationResult.Error
