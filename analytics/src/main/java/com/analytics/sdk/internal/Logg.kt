package com.analytics.sdk.internal

import android.util.Log

/** 内部日志，仅 debug 模式输出。 */
internal object Logg {
    private const val TAG = "AnalyticsSDK"

    @Volatile
    var debug: Boolean = false

    fun d(msg: String) {
        if (debug) Log.d(TAG, msg)
    }

    fun w(msg: String) {
        if (debug) Log.w(TAG, msg)
    }

    fun e(msg: String, t: Throwable? = null) {
        if (debug) Log.e(TAG, msg, t)
    }
}

/** 按 DDL 长度截断（按字符数，与 MySQL VARCHAR(n) 的语义一致）。 */
internal fun String?.trunc(max: Int): String? {
    if (this == null) return null
    return if (length <= max) this else substring(0, max)
}
