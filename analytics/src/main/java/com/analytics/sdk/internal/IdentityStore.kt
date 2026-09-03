package com.analytics.sdk.internal

import android.content.Context
import java.util.UUID

/**
 * 设备与用户标识持久化（SharedPreferences）。
 * device_id 首启生成 UUID；user_id / device_register 发送标记 / 首日标记 / 最后事件时间。
 */
internal class IdentityStore(context: Context) {

    private val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val deviceId: String
        get() {
            var id = sp.getString(KEY_DEVICE_ID, null)
            if (id.isNullOrEmpty()) {
                id = UUID.randomUUID().toString()
                sp.edit().putString(KEY_DEVICE_ID, id).apply()
            }
            return id
        }

    var userId: Long?
        get() = if (sp.contains(KEY_USER_ID)) sp.getLong(KEY_USER_ID, 0L) else null
        set(value) {
            val e = sp.edit()
            if (value == null) e.remove(KEY_USER_ID) else e.putLong(KEY_USER_ID, value)
            e.apply()
        }

    /** device_register 是否已发送过（每台设备仅一次）。 */
    var deviceRegisterSent: Boolean
        get() = sp.getBoolean(KEY_REGISTER_SENT, false)
        set(v) {
            sp.edit().putBoolean(KEY_REGISTER_SENT, v).apply()
        }

    /** 首次启动日期（yyyyMMdd），用于 is_new 判定：首日事件 is_new=1。 */
    val firstLaunchDay: String
        get() {
            var d = sp.getString(KEY_FIRST_DAY, null)
            if (d.isNullOrEmpty()) {
                d = TimeUtil.dayStamp(System.currentTimeMillis())
                sp.edit().putString(KEY_FIRST_DAY, d).apply()
            }
            return d
        }

    /** 最近一次事件时间（跨进程存活，供 30 分钟 session 超时判定）。 */
    var lastEventTime: Long
        get() = sp.getLong(KEY_LAST_EVENT, 0L)
        set(v) {
            sp.edit().putLong(KEY_LAST_EVENT, v).apply()
        }

    fun isNewToday(): Boolean =
        TimeUtil.dayStamp(System.currentTimeMillis()) == firstLaunchDay

    private companion object {
        const val PREFS_NAME = "analytics_identity"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_USER_ID = "user_id"
        const val KEY_REGISTER_SENT = "device_register_sent"
        const val KEY_FIRST_DAY = "first_launch_day"
        const val KEY_LAST_EVENT = "last_event_time"
    }
}
