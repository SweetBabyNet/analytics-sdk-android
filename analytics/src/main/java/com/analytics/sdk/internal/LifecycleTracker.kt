package com.analytics.sdk.internal

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * 前后台判定：统计处于 started 状态的 Activity 数。
 * - 0 -> 1：回前台。首次（冷启动）在首个 Activity onResume 时发 app_start(cold)，
 *   duration_ms 为 SDK 初始化到首个 onResume 的耗时；距上次退后台 >30s 的回前台发 app_start(hot)。
 * - 1 -> 0：退后台，发 app_end 并立即 flush。
 */
internal class LifecycleTracker(private val core: AnalyticsCore) {

    private var startedCount = 0
    private var firstResumeDone = false
    private var lastBackgroundAtMs = 0L

    fun register(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {

            override fun onActivityStarted(activity: Activity) {
                startedCount++
                if (startedCount == 1) {
                    try {
                        core.onForeground()
                        if (firstResumeDone && lastBackgroundAtMs > 0L &&
                            System.currentTimeMillis() - lastBackgroundAtMs > HOT_START_INTERVAL_MS
                        ) {
                            core.onHotStart()
                        }
                    } catch (t: Throwable) {
                        Logg.e("onActivityStarted failed", t)
                    }
                }
            }

            override fun onActivityResumed(activity: Activity) {
                if (!firstResumeDone) {
                    firstResumeDone = true
                    try {
                        core.onColdStart()
                    } catch (t: Throwable) {
                        Logg.e("onColdStart failed", t)
                    }
                }
            }

            override fun onActivityStopped(activity: Activity) {
                if (startedCount > 0) startedCount--
                if (startedCount == 0) {
                    lastBackgroundAtMs = System.currentTimeMillis()
                    try {
                        core.onBackground()
                    } catch (t: Throwable) {
                        Logg.e("onBackground failed", t)
                    }
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    companion object {
        /** 距上次退出 >30s 回前台算热启动（新启动）。 */
        private const val HOT_START_INTERVAL_MS = 30_000L
    }
}
