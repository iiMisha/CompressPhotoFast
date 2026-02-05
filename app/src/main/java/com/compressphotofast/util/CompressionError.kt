package com.compressphotofast.util

import android.net.Uri

enum class CompressionErrorType {
    OUT_OF_MEMORY,
    IO_ERROR,
    DECODE_ERROR,
    INVALID_FORMAT,
    UNKNOWN
}

class CompressionException(
    val errorType: CompressionErrorType,
    message: String,
    val uri: Uri? = null,
    cause: Throwable? = null
) : Exception(message, cause)
