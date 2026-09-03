package com.analytics.sdk.internal

import android.content.Context
import java.io.File

/**
 * 事件队列：内存队列 + JSONL 文件兜底（filesDir/analytics/events.log）。
 * 除 [appendToFileDirect]（崩溃场景）外，所有方法都必须在 SDK worker 线程上调用。
 * 上限 [MAX_EVENTS] 条，超出丢最旧。
 */
internal class EventQueue(context: Context) {

    private val file = File(File(context.filesDir, DIR_NAME), FILE_NAME)
    private val mem = ArrayDeque<String>()
    private val fileLock = Any()

    /** 启动时把文件残留事件读回内存（含上次崩溃写入的 app_crash）。 */
    fun loadFromDisk() {
        try {
            if (!file.exists()) return
            val lines = file.readLines(Charsets.UTF_8).filter { it.isNotBlank() }
            lines.takeLast(MAX_EVENTS).forEach { mem.addLast(it) }
            if (lines.isNotEmpty()) {
                Logg.d("loaded ${mem.size} pending events from disk")
            }
        } catch (t: Throwable) {
            Logg.e("load events failed", t)
        }
    }

    val size: Int
        get() = mem.size

    /** 入队：内存 + 追加文件；超上限丢最旧。 */
    fun enqueue(line: String) {
        try {
            mem.addLast(line)
            appendLine(line)
            if (mem.size > MAX_EVENTS) {
                while (mem.size > MAX_EVENTS) mem.removeFirst()
                rewriteFile()
            }
        } catch (t: Throwable) {
            Logg.e("enqueue failed", t)
        }
    }

    /** 取队首至多 n 条（不移除，发送成功/确认丢弃后调 [commitBatch]）。 */
    fun peekBatch(n: Int): List<String> = mem.take(n)

    /** 确认移除队首 n 条并同步文件。 */
    fun commitBatch(n: Int) {
        try {
            repeat(minOf(n, mem.size)) { mem.removeFirst() }
            rewriteFile()
        } catch (t: Throwable) {
            Logg.e("commit batch failed", t)
        }
    }

    /**
     * 崩溃场景专用：把一行事件直接追加到文件（不走内存队列）。
     * 可在任意线程调用；下次启动时随 loadFromDisk 进入正常上报流程。
     */
    fun appendToFileDirect(line: String) {
        try {
            appendLine(line)
        } catch (t: Throwable) {
            Logg.e("append direct failed", t)
        }
    }

    private fun appendLine(line: String) {
        synchronized(fileLock) {
            file.parentFile?.mkdirs()
            file.appendText(line + "\n", Charsets.UTF_8)
        }
    }

    private fun rewriteFile() {
        synchronized(fileLock) {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.bufferedWriter(Charsets.UTF_8).use { w ->
                mem.forEach { w.write(it); w.newLine() }
            }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        }
    }

    companion object {
        const val MAX_EVENTS = 5000
        private const val DIR_NAME = "analytics"
        private const val FILE_NAME = "events.log"
    }
}
