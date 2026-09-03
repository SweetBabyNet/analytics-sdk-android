package com.analytics.sdk.internal

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPOutputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

/**
 * 上报器：HttpURLConnection + gzip + HMAC-SHA256 签名。
 * 全部在 SDK worker 线程上同步执行，天然串行。
 */
internal class Uploader(
    endpoint: String,
    private val appKey: String,
    private val appSecret: String,
) {

    enum class Result { SUCCESS, DISCARD, RETRY }

    private val url = endpoint.trimEnd('/') + PATH

    /** eventLines 为已序列化好的单条事件 JSON 字符串。 */
    fun upload(eventLines: List<String>): Result {
        return try {
            val body = buildBody(eventLines)
            val raw = body.toByteArray(Charsets.UTF_8)
            val gz = gzip(raw)
            val sign = hmacSha256Hex(gz, appSecret)

            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Content-Encoding", "gzip")
                setRequestProperty("X-App-Key", appKey)
                setRequestProperty("X-Timestamp", System.currentTimeMillis().toString())
                setRequestProperty("X-Sign", sign)
                setFixedLengthStreamingMode(gz.size)
            }
            conn.outputStream.use { it.write(gz) }

            val code = conn.responseCode
            if (Logg.debug) {
                val resp = try {
                    (if (code in 200..299) conn.inputStream else conn.errorStream)
                        ?.bufferedReader()?.use { it.readText() } ?: ""
                } catch (_: Throwable) { "" }
                Logg.d("upload ${eventLines.size} events -> HTTP $code $resp")
            }
            conn.disconnect()

            when (code) {
                in 200..299 -> Result.SUCCESS
                // 400 格式错误 / 401 鉴权失败：重试无意义，丢弃该批
                400, 401 -> {
                    Logg.w("upload got $code, drop batch of ${eventLines.size}")
                    Result.DISCARD
                }
                // 429 / 5xx / 其他：指数退避重试
                else -> {
                    Logg.w("upload got $code, will retry")
                    Result.RETRY
                }
            }
        } catch (t: Throwable) {
            Logg.e("upload failed, will retry", t)
            Result.RETRY
        }
    }

    private fun buildBody(eventLines: List<String>): String {
        val events = eventLines.joinToString(separator = ",", prefix = "[", postfix = "]")
        val sb = StringBuilder(events.length + 128)
        sb.append("{\"app_key\":").append(JSONObject.quote(appKey))
            .append(",\"sent_time\":").append(System.currentTimeMillis())
            .append(",\"sdk_version\":\"").append(SDK_VERSION).append("\"")
            .append(",\"events\":").append(events)
            .append("}")
        return sb.toString()
    }

    private fun gzip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(data) }
        return bos.toByteArray()
    }

    private fun hmacSha256Hex(data: ByteArray, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data).joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val SDK_VERSION = "1.0.0"
        private const val PATH = "/v1/track/batch"
        private const val TIMEOUT_MS = 15_000
    }
}
