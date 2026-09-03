package com.analytics.sdk.internal

import java.security.SecureRandom

/**
 * 会话管理：无活跃会话或距上一事件超过 30 分钟时生成新会话。
 * 格式：s-yyyyMMdd-HHmmss-xxxx（xxxx 为随机 4 位十六进制）。
 */
internal class SessionManager(private val store: IdentityStore) {

    private val lock = Any()
    private val random = SecureRandom()

    @Volatile
    private var current: String? = null

    /** 返回当前会话 ID；必要时创建新会话；同时刷新最后事件时间。 */
    fun ensureSession(): String {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val s = current
            if (s == null || now - store.lastEventTime > SESSION_TIMEOUT_MS) {
                current = newSessionId(now)
                Logg.d("new session: $current")
            }
            store.lastEventTime = now
            return current!!
        }
    }

    /** 仅刷新最后事件时间（不强制创建会话，无会话时则创建）。 */
    fun touch() {
        ensureSession()
    }

    private fun newSessionId(now: Long): String {
        val rand = Integer.toHexString(random.nextInt(0x10000)).padStart(4, '0')
        return "s-${TimeUtil.dayStamp(now)}-${TimeUtil.timeStamp(now)}-$rand"
    }

    companion object {
        const val SESSION_TIMEOUT_MS = 30L * 60L * 1000L
    }
}
