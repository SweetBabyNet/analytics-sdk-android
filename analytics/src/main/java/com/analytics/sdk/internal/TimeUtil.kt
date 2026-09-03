package com.analytics.sdk.internal

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object TimeUtil {
    fun dayStamp(timeMs: Long): String =
        SimpleDateFormat("yyyyMMdd", Locale.US).format(Date(timeMs))

    fun timeStamp(timeMs: Long): String =
        SimpleDateFormat("HHmmss", Locale.US).format(Date(timeMs))
}
