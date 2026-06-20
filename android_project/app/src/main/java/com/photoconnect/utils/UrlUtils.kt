package com.photoconnect.utils

import com.photoconnect.BuildConfig

fun String?.toAbsoluteMediaUrl(): String? {
    val raw = this?.trim().orEmpty()
    if (raw.isBlank()) return null
    if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) {
        return raw
    }
    val base = BuildConfig.BASE_URL.substringBeforeLast('/').trimEnd('/')
    return "$base/${raw.trimStart('/')}"
}
