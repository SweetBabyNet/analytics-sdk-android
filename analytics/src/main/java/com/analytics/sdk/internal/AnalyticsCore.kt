package com.analytics.sdk.internal

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import java.util.UUID
import org.json.JSONObject

/**
 * SDK 核心：事件构建、入队、flush 调度与上传。
 * 所有队列/网络操作都在单个 worker HandlerThread 上串行执行。
 */
internal class AnalyticsCore(
    context: Context,
    private val appKey: String,
    appSecret: String,
    endpoint: String,
    enable: Boolean,
    channel: String,
) {

    private val appContext: Context = context.applicationContext
    private val thread = HandlerThread("analytics-worker").apply { start() }
    private val handler = Handler(thread.looper)

    private val store = IdentityStore(appContext)
    private val device = DeviceInfo(appContext, channel)
    private val session = SessionManager(store)
    private val queue = EventQueue(appContext)
    private val uploader = Uploader(endpoint, appKey, appSecret)
    private val pageTracker = PageTracker(this)
    private val lifecycleTracker = LifecycleTracker(this)

    /** SDK 初始化时刻，用于冷启动耗时（Application.onCreate 调 setup 时约等于应用创建时刻）。 */
    val initTimeMs: Long = System.currentTimeMillis()

    @Volatile
    var enabled: Boolean = enable
        private set

    @Volatile
    var debug: Boolean = false

    // ---- 以下状态仅在 worker 线程访问 ----
    private var foreground = false
    private var retryCount = 0
    private var retryScheduled = false
    private var timerScheduled = false

    init {
        handler.post {
            try {
                queue.loadFromDisk()
                (appContext as? Application)?.let { lifecycleTracker.register(it) }
                CrashReporter.install(this)
                if (enabled) {
                    maybeSendDeviceRegister()
                    startTimer()
                }
                // 启动时若文件有残留事件（含 app_crash），立即尝试上报
                if (queue.size > 0) doFlush(force = true)
            } catch (t: Throwable) {
                Logg.e("core init failed", t)
            }
        }
    }

    // ------------------------------------------------------------------
    // 对外入口（可在任意线程调用，内部切到 worker）
    // ------------------------------------------------------------------

    fun track(eventName: String, type: String, props: Map<String, Any?>?, durationMs: Long? = null) {
        if (!enabled) return
        handler.post {
            try {
                if (!enabled) return@post
                enqueueOnWorker(buildEvent(name = eventName, type = type, durationMs = durationMs, props = props))
                maybeThresholdFlush()
            } catch (t: Throwable) {
                Logg.e("track failed", t)
            }
        }
    }

    fun trackPage(pageName: String, props: Map<String, Any?>?) {
        if (!enabled) return
        handler.post {
            try {
                if (!enabled) return@post
                pageTracker.onTrackPage(pageName.trunc(MAX_PAGE) ?: return@post, props)
                maybeThresholdFlush()
            } catch (t: Throwable) {
                Logg.e("trackPage failed", t)
            }
        }
    }

    fun trackApiError(apiPath: String, httpCode: Int, bizCode: Int?) {
        if (!enabled) return
        val props = mapOf<String, Any?>(
            "api_path" to apiPath,
            "http_code" to httpCode,
            "biz_code" to bizCode,
        )
        track("api_error", "error", props)
    }

    fun setUserId(userId: Long?) {
        try {
            store.userId = userId
        } catch (t: Throwable) {
            Logg.e("setUserId failed", t)
        }
    }

    fun setEnabled(value: Boolean) {
        if (enabled == value) return
        enabled = value
        handler.post {
            try {
                if (value) {
                    // 隐私政策同意后开启采集：补发 device_register（若未发过）
                    maybeSendDeviceRegister()
                    startTimer()
                    if (queue.size > 0) doFlush(force = true)
                } else {
                    stopTimer()
                }
            } catch (t: Throwable) {
                Logg.e("setEnabled failed", t)
            }
        }
    }

    /** 手动立即上报。 */
    fun flush() {
        handler.post {
            try {
                doFlush(force = true)
            } catch (t: Throwable) {
                Logg.e("flush failed", t)
            }
        }
    }

    // ------------------------------------------------------------------
    // 生命周期回调（LifecycleTracker 在主线程触发，这里切 worker）
    // ------------------------------------------------------------------

    fun onColdStart() {
        handler.post {
            try {
                foreground = true
                session.touch()
                val duration = System.currentTimeMillis() - initTimeMs
                if (enabled) {
                    enqueueOnWorker(
                        buildEvent(
                            name = "app_start",
                            type = "lifecycle",
                            durationMs = duration,
                            props = mapOf("launch_type" to "cold"),
                        )
                    )
                }
            } catch (t: Throwable) {
                Logg.e("onColdStart failed", t)
            }
        }
    }

    fun onHotStart() {
        handler.post {
            try {
                foreground = true
                session.touch()
                if (enabled) {
                    enqueueOnWorker(
                        buildEvent(
                            name = "app_start",
                            type = "lifecycle",
                            props = mapOf("launch_type" to "hot"),
                        )
                    )
                }
            } catch (t: Throwable) {
                Logg.e("onHotStart failed", t)
            }
        }
    }

    fun onForeground() {
        handler.post {
            try {
                foreground = true
                session.touch()
                if (enabled) startTimer()
            } catch (t: Throwable) {
                Logg.e("onForeground failed", t)
            }
        }
    }

    fun onBackground() {
        handler.post {
            try {
                foreground = false
                stopTimer()
                pageTracker.endCurrentPage()
                if (enabled) {
                    enqueueOnWorker(buildEvent(name = "app_end", type = "lifecycle"))
                }
                // 退后台立即 flush
                doFlush(force = true)
            } catch (t: Throwable) {
                Logg.e("onBackground failed", t)
            }
        }
    }

    // ------------------------------------------------------------------
    // worker 线程内部方法
    // ------------------------------------------------------------------

    /** worker 线程入队（崩溃报告外的统一入口）。 */
    fun enqueueOnWorker(line: String) {
        queue.enqueue(line)
        Logg.d("enqueue: $line")
    }

    /** 崩溃场景：直接写文件，不走内存队列，可在任意线程调用。 */
    fun appendToFileDirect(line: String) {
        queue.appendToFileDirect(line)
    }

    /** 构建单条事件 JSON（线程安全，可在 worker 或崩溃线程调用）。 */
    fun buildEvent(
        name: String,
        type: String,
        page: String? = null,
        referPage: String? = null,
        durationMs: Long? = null,
        props: Map<String, Any?>? = null,
        includeScreen: Boolean = false,
        forceIsNew: Boolean = false,
    ): String {
        val o = JSONObject()
        o.put("event_id", UUID.randomUUID().toString().trunc(MAX_EVENT_ID))
        o.put("event_name", name.trunc(MAX_EVENT_NAME))
        o.put("event_type", type.trunc(MAX_EVENT_TYPE))
        o.put("event_time", System.currentTimeMillis())
        o.put("duration_ms", durationMs?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt() ?: JSONObject.NULL)
        o.put("page", page.trunc(MAX_PAGE) ?: JSONObject.NULL)
        o.put("refer_page", referPage.trunc(MAX_PAGE) ?: JSONObject.NULL)
        o.put("props", propsToJson(props))
        o.put("device_id", store.deviceId.trunc(MAX_DEVICE_ID))
        o.put("user_id", store.userId ?: JSONObject.NULL)
        o.put("session_id", session.ensureSession().trunc(MAX_SESSION_ID))
        o.put("is_new", if (forceIsNew || store.isNewToday()) 1 else 0)
        o.put("platform", device.platform.trunc(MAX_PLATFORM))
        o.put("app_version", device.appVersion.trunc(MAX_APP_VERSION))
        o.put("build_number", device.buildNumber.nullIfEmpty().trunc(MAX_BUILD_NUMBER) ?: JSONObject.NULL)
        o.put("channel", device.channel.nullIfEmpty().trunc(MAX_CHANNEL) ?: JSONObject.NULL)
        o.put("lang", device.lang.nullIfEmpty().trunc(MAX_LANG) ?: JSONObject.NULL)
        o.put("os_version", device.osVersion.nullIfEmpty().trunc(MAX_OS_VERSION) ?: JSONObject.NULL)
        o.put("device_brand", device.deviceBrand.nullIfEmpty().trunc(MAX_DEVICE_BRAND) ?: JSONObject.NULL)
        o.put("device_model", device.deviceModel.nullIfEmpty().trunc(MAX_DEVICE_MODEL) ?: JSONObject.NULL)
        o.put("network", device.network().trunc(MAX_NETWORK))
        o.put("screen_width", if (includeScreen) device.screenWidth else JSONObject.NULL)
        o.put("screen_height", if (includeScreen) device.screenHeight else JSONObject.NULL)
        return o.toString()
    }

    /** 每台设备仅首次一次：is_new=1，带屏幕宽高。 */
    private fun maybeSendDeviceRegister() {
        if (store.deviceRegisterSent) return
        store.deviceRegisterSent = true
        enqueueOnWorker(
            buildEvent(
                name = "device_register",
                type = "lifecycle",
                includeScreen = true,
                forceIsNew = true,
            )
        )
        maybeThresholdFlush()
    }

    private fun maybeThresholdFlush() {
        if (queue.size >= flushThreshold()) doFlush(force = false)
    }

    private fun flushThreshold(): Int = if (debug) DEBUG_THRESHOLD else NORMAL_THRESHOLD

    private fun flushIntervalMs(): Long = if (debug) DEBUG_INTERVAL_MS else NORMAL_INTERVAL_MS

    /**
     * 执行上传：每批 ≤100 条，串行。
     * force=true 时把队列全部发完；否则发到低于阈值为止。
     * 失败（429/5xx/网络异常）指数退避 5s→15s→60s→5min；400/401 丢弃。
     */
    private fun doFlush(force: Boolean) {
        while (queue.size > 0 && (force || queue.size >= flushThreshold())) {
            val batch = queue.peekBatch(BATCH_SIZE)
            when (uploader.upload(batch)) {
                Uploader.Result.SUCCESS -> {
                    queue.commitBatch(batch.size)
                    retryCount = 0
                }
                Uploader.Result.DISCARD -> {
                    queue.commitBatch(batch.size)
                    retryCount = 0
                }
                Uploader.Result.RETRY -> {
                    scheduleRetry()
                    return
                }
            }
        }
    }

    private fun scheduleRetry() {
        if (retryScheduled) return
        retryScheduled = true
        val delayMs = BACKOFF_MS[retryCount.coerceAtMost(BACKOFF_MS.size - 1)]
        retryCount++
        Logg.d("retry in ${delayMs}ms (attempt $retryCount)")
        handler.postDelayed({
            retryScheduled = false
            try {
                doFlush(force = false)
            } catch (t: Throwable) {
                Logg.e("retry flush failed", t)
            }
        }, delayMs)
    }

    // ---- 前台定时器：每 30s（debug 5s）flush 一次 ----

    private val timerRunnable = object : Runnable {
        override fun run() {
            timerScheduled = false
            try {
                if (foreground && enabled && queue.size > 0) doFlush(force = false)
            } catch (t: Throwable) {
                Logg.e("timer flush failed", t)
            }
            startTimer()
        }
    }

    private fun startTimer() {
        if (timerScheduled) return
        timerScheduled = true
        handler.postDelayed(timerRunnable, flushIntervalMs())
    }

    private fun stopTimer() {
        timerScheduled = false
        handler.removeCallbacks(timerRunnable)
    }

    // ------------------------------------------------------------------
    // props 序列化
    // ------------------------------------------------------------------

    private fun propsToJson(props: Map<String, Any?>?): JSONObject {
        val o = JSONObject()
        if (props != null) {
            for ((k, v) in props) {
                try {
                    o.put(k.trunc(MAX_PROPS_KEY), wrapProp(v))
                } catch (t: Throwable) {
                    Logg.e("bad prop $k", t)
                }
            }
        }
        return o
    }

    private fun wrapProp(v: Any?): Any = when (v) {
        null -> JSONObject.NULL
        is String -> v.trunc(MAX_PROPS_VALUE) ?: ""
        is Number, is Boolean -> v
        is Map<*, *> -> {
            @Suppress("UNCHECKED_CAST")
            propsToJson(v as? Map<String, Any?>)
        }
        is Collection<*> -> org.json.JSONArray(v.map { wrapProp(it) })
        is Array<*> -> org.json.JSONArray(v.map { wrapProp(it) })
        else -> v.toString().trunc(MAX_PROPS_VALUE) ?: ""
    }

    private fun String?.nullIfEmpty(): String? = if (isNullOrEmpty()) null else this

    companion object {
        // flush 策略
        private const val NORMAL_THRESHOLD = 50
        private const val NORMAL_INTERVAL_MS = 30_000L
        private const val DEBUG_THRESHOLD = 5
        private const val DEBUG_INTERVAL_MS = 5_000L
        private const val BATCH_SIZE = 100
        private val BACKOFF_MS = longArrayOf(5_000L, 15_000L, 60_000L, 300_000L)

        // 字段长度（对齐 sql/analytics.sql DDL）
        private const val MAX_EVENT_ID = 40
        private const val MAX_EVENT_NAME = 64
        private const val MAX_EVENT_TYPE = 16
        private const val MAX_DEVICE_ID = 40
        private const val MAX_SESSION_ID = 40
        private const val MAX_PLATFORM = 16
        private const val MAX_APP_VERSION = 16
        private const val MAX_BUILD_NUMBER = 16
        private const val MAX_CHANNEL = 32
        private const val MAX_LANG = 16
        private const val MAX_OS_VERSION = 32
        private const val MAX_DEVICE_BRAND = 32
        private const val MAX_DEVICE_MODEL = 64
        private const val MAX_NETWORK = 8
        private const val MAX_PAGE = 64

        // props 安全限制（DDL 为 JSON 无长度限制，SDK 侧防爆）
        private const val MAX_PROPS_KEY = 64
        private const val MAX_PROPS_VALUE = 512
    }
}
