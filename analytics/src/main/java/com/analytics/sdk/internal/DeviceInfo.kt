package com.analytics.sdk.internal

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.TelephonyManager
import java.util.Locale

/**
 * 设备与环境信息。静态字段在 setup 时采集一次；network 每次事件实时查询。
 */
internal class DeviceInfo(private val context: Context, channel: String) {

    val platform: String = "android"
    val appVersion: String
    val buildNumber: String
    val channel: String = channel
    val lang: String = Locale.getDefault().toLanguageTag() // e.g. zh-CN
    val osVersion: String = Build.VERSION.RELEASE ?: ""
    val deviceBrand: String = Build.BRAND ?: ""
    val deviceModel: String = Build.MODEL ?: ""
    val screenWidth: Int
    val screenHeight: Int

    init {
        var version = ""
        var build = ""
        try {
            @Suppress("DEPRECATION")
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            version = pi.versionName ?: ""
            build = if (Build.VERSION.SDK_INT >= 28) {
                pi.longVersionCode.toString()
            } else {
                @Suppress("DEPRECATION") pi.versionCode.toString()
            }
        } catch (t: Throwable) {
            Logg.e("read package info failed", t)
        }
        appVersion = version
        buildNumber = build
        val dm = context.resources.displayMetrics
        screenWidth = dm.widthPixels
        screenHeight = dm.heightPixels
    }

    /** wifi/5g/4g/3g/2g/none/unknown。无权限或异常时返回 unknown。 */
    fun network(): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return "unknown"
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "none"
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> cellularType()
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "wifi"
                else -> "unknown"
            }
        } catch (t: Throwable) {
            Logg.e("read network failed", t)
            "unknown"
        }
    }

    private fun cellularType(): String {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                ?: return "unknown"
            when (tm.dataNetworkType) {
                TelephonyManager.NETWORK_TYPE_NR -> "5g"
                TelephonyManager.NETWORK_TYPE_LTE -> "4g"
                TelephonyManager.NETWORK_TYPE_UMTS,
                TelephonyManager.NETWORK_TYPE_HSDPA,
                TelephonyManager.NETWORK_TYPE_HSUPA,
                TelephonyManager.NETWORK_TYPE_HSPA,
                TelephonyManager.NETWORK_TYPE_HSPAP,
                TelephonyManager.NETWORK_TYPE_EVDO_0,
                TelephonyManager.NETWORK_TYPE_EVDO_A,
                TelephonyManager.NETWORK_TYPE_EVDO_B,
                TelephonyManager.NETWORK_TYPE_EHRPD,
                -> "3g"
                TelephonyManager.NETWORK_TYPE_GPRS,
                TelephonyManager.NETWORK_TYPE_EDGE,
                TelephonyManager.NETWORK_TYPE_CDMA,
                TelephonyManager.NETWORK_TYPE_1xRTT,
                TelephonyManager.NETWORK_TYPE_IDEN,
                -> "2g"
                else -> "unknown"
            }
        } catch (t: Throwable) {
            // 无 READ_PHONE_STATE 权限时 dataNetworkType 可能抛 SecurityException
            Logg.e("read cellular type failed", t)
            "unknown"
        }
    }
}
