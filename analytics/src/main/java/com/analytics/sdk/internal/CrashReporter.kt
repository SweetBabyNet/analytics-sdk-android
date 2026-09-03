package com.analytics.sdk.internal

/**
 * 崩溃捕获：包装原 UncaughtExceptionHandler，把 app_crash 事件直接写入
 * JSONL 文件（不走内存队列），下次启动随正常 flush 上报，然后链式调用原 handler。
 */
internal object CrashReporter {

    private const val MAX_MESSAGE_LEN = 200

    fun install(core: AnalyticsCore) {
        try {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    val line = core.buildEvent(
                        name = "app_crash",
                        type = "error",
                        props = mapOf(
                            "exception" to throwable.javaClass.name,
                            "message" to (throwable.message?.take(MAX_MESSAGE_LEN) ?: ""),
                        ),
                    )
                    core.appendToFileDirect(line)
                } catch (t: Throwable) {
                    Logg.e("persist crash failed", t)
                }
                previous?.uncaughtException(thread, throwable)
            }
        } catch (t: Throwable) {
            Logg.e("install crash reporter failed", t)
        }
    }
}
