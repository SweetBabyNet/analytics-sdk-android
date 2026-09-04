package com.analytics.sdk.internal

/**
 * 页面停留追踪（全部在 SDK worker 线程上调用）。
 * trackPage 记录进入；下一次 trackPage 或退后台时给上一页补发 page_view，
 * 带 duration_ms 停留时长与 refer_page 上一页；停留 <100ms 不上报。
 */
internal class PageTracker(private val core: AnalyticsCore) {

    private var currentPage: String? = null
    private var currentProps: Map<String, Any?>? = null
    private var referPage: String? = null
    private var enterTimeMs: Long = 0L

    /** 当前停留页面名（handler 线程读取），自定义事件用于自动携带 page。 */
    val currentPageName: String? get() = currentPage

    fun onTrackPage(page: String, props: Map<String, Any?>?) {
        endCurrentPage()
        currentPage = page
        currentProps = props
        enterTimeMs = System.currentTimeMillis()
    }

    /** 结束当前页：停留 >=100ms 时补发 page_view。 */
    fun endCurrentPage() {
        val page = currentPage ?: return
        val duration = System.currentTimeMillis() - enterTimeMs
        val props = currentProps
        currentPage = null
        currentProps = null
        if (duration >= MIN_DURATION_MS) {
            core.enqueueOnWorker(
                core.buildEvent(
                    name = "page_view",
                    type = "page",
                    page = page,
                    referPage = referPage,
                    durationMs = duration,
                    props = props,
                )
            )
        }
        // 无论是否上报，访问过的页面都成为下一页的 refer_page
        referPage = page
    }

    companion object {
        const val MIN_DURATION_MS = 100L
    }
}
