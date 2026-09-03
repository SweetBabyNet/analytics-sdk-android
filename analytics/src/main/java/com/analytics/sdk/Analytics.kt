package com.analytics.sdk

import android.content.Context
import com.analytics.sdk.internal.AnalyticsCore
import com.analytics.sdk.internal.Logg

/**
 * 埋点 SDK 对外门面。未调用 [setup] 前所有调用静默忽略；任何情况不向业务抛异常。
 *
 * 用法（Application.onCreate）：
 * ```
 * Analytics.setup(this, appKey, appSecret, endpoint, enable = false, channel = "official")
 * // 隐私政策同意后：
 * Analytics.enable()
 * ```
 */
object Analytics {

    @Volatile
    private var core: AnalyticsCore? = null

    /** track 允许的自定义事件类型（page/lifecycle/error 由专用 API 或 SDK 内部产生）。 */
    private val CUSTOM_EVENT_TYPES = setOf("biz", "interact", "exposure")

    /**
     * 初始化 SDK（建议在 Application.onCreate 中调用，重复调用仅首次生效）。
     *
     * @param context   任意 Context，内部取 applicationContext
     * @param appKey    项目标识
     * @param appSecret HMAC 签名密钥
     * @param endpoint  采集服务地址，如 https://analytics.example.com
     * @param enable    false 时仅初始化不采集（用于"同意隐私政策后再采集"）
     * @param channel   渠道标识
     */
    @JvmStatic
    @JvmOverloads
    fun setup(
        context: Context,
        appKey: String,
        appSecret: String,
        endpoint: String,
        enable: Boolean = true,
        channel: String = "",
    ) {
        if (core != null) return
        synchronized(this) {
            if (core != null) return
            try {
                core = AnalyticsCore(
                    context = context.applicationContext,
                    appKey = appKey,
                    appSecret = appSecret,
                    endpoint = endpoint,
                    enable = enable,
                    channel = channel,
                )
            } catch (t: Throwable) {
                Logg.e("setup failed", t)
            }
        }
    }

    /** 开启采集（含补发 device_register，若未发过）。 */
    @JvmStatic
    fun enable() {
        try {
            core?.setEnabled(true)
        } catch (t: Throwable) {
            Logg.e("enable failed", t)
        }
    }

    /** 停止采集。 */
    @JvmStatic
    fun disable() {
        try {
            core?.setEnabled(false)
        } catch (t: Throwable) {
            Logg.e("disable failed", t)
        }
    }

    /**
     * 自定义事件。
     *
     * @param eventName 事件名
     * @param props     事件属性
     * @param eventType 仅允许 biz / interact / exposure（page/lifecycle/error 由专用 API 或 SDK 内部产生）；
     *                  传非法值时按 biz 处理并打 debug 日志
     * @param durationMs 时长（毫秒），仅 exposure 等有时长语义的事件使用，其余传 null
     */
    @JvmStatic
    @JvmOverloads
    fun track(
        eventName: String,
        props: Map<String, Any?> = emptyMap(),
        eventType: String = "biz",
        durationMs: Long? = null,
    ) {
        try {
            val type = if (eventType in CUSTOM_EVENT_TYPES) {
                eventType
            } else {
                Logg.d("track: invalid eventType '$eventType', fallback to 'biz'")
                "biz"
            }
            core?.track(eventName, type, props, durationMs)
        } catch (t: Throwable) {
            Logg.e("track failed", t)
        }
    }

    /** 页面进入；离开该页（下次 trackPage 或退后台）时补发 page_view。 */
    @JvmStatic
    @JvmOverloads
    fun trackPage(pageName: String, props: Map<String, Any?> = emptyMap()) {
        try {
            core?.trackPage(pageName, props)
        } catch (t: Throwable) {
            Logg.e("trackPage failed", t)
        }
    }

    /** 接口异常，业务网络层钩子调用一行即可。 */
    @JvmStatic
    @JvmOverloads
    fun trackApiError(apiPath: String, httpCode: Int, bizCode: Int? = null) {
        try {
            core?.trackApiError(apiPath, httpCode, bizCode)
        } catch (t: Throwable) {
            Logg.e("trackApiError failed", t)
        }
    }

    /** 设置/清除用户 ID（持久化，杀进程重进仍在）。 */
    @JvmStatic
    fun setUserId(userId: Long?) {
        try {
            core?.setUserId(userId)
        } catch (t: Throwable) {
            Logg.e("setUserId failed", t)
        }
    }

    /** 手动立即上报（业务大动作后可选调用）。 */
    @JvmStatic
    fun flush() {
        try {
            core?.flush()
        } catch (t: Throwable) {
            Logg.e("flush failed", t)
        }
    }

    /** debug 模式：打印事件日志，flush 阈值降为 5 条/5 秒。 */
    @JvmStatic
    fun setDebug(debug: Boolean) {
        try {
            Logg.debug = debug
            core?.debug = debug
        } catch (t: Throwable) {
            // 静默
        }
    }
}
