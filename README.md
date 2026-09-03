# Analytics SDK for Android

零三方依赖的 Android 埋点 SDK（Kotlin），实现《埋点协议与 SDK 规范》三端契约的 Android 端。
仅使用 Android 框架自带能力（`org.json`、`HttpURLConnection`、`SharedPreferences` 等），不引入任何第三方库。

- minSdk 24 / compileSdk 34
- 包名：`com.analytics.sdk`
- 存储：`filesDir/analytics/events.log`（JSONL 兜底，上限 5000 条）+ SharedPreferences（标识信息），不碰宿主数据库

## 集成

### 方式一：JitPack（推荐）

本 SDK 托管在 GitHub，通过 [JitPack](https://jitpack.io) 拉取。仓库根目录 `settings.gradle.kts`
加 JitPack 仓库后直接依赖：

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

// app/build.gradle.kts
dependencies {
    implementation("com.github.SweetBabyNet:analytics-sdk-android:v1.0.1")
}
```

> 把 `redrain39` 替换为实际托管账号。JitPack 首次构建需要在
> jitpack.io 上用仓库地址请求一次（或等首次引用时自动触发），成功后即可拉取。
> 版本号即 git tag（如 `v1.0.1`），SDK 发版 = 打 tag 并推送。

### 方式二：源码 module

1. 把 `analytics/` 目录复制到你的工程根目录。
2. `settings.gradle.kts` 中 `include(":analytics")`。
3. app module 依赖：

```kotlin
dependencies {
    implementation(project(":analytics"))
}
```

### 方式三：AAR

`./gradlew :analytics:assembleRelease`，产物在 `analytics/build/outputs/aar/analytics-release.aar`，
放入 app 的 `libs/` 后 `implementation(files("libs/analytics-release.aar"))`。

### 初始化

在 `Application.onCreate()` 中调用（SDK 自动注册生命周期回调，业务无需额外调用）：

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Analytics.setup(
            context = this,
            appKey = "cfood-help",
            appSecret = "your-secret",
            endpoint = "https://analytics.example.com",
            enable = true,          // 隐私政策未同意时传 false
            channel = "official",
        )
    }
}
```

未 setup 时所有 API 静默忽略；SDK 内部全异步、catch-all，不会向业务抛异常、不阻塞主线程。

### 权限

SDK 的 manifest 已声明并会自动合并：`INTERNET`、`ACCESS_NETWORK_STATE`。
可选：声明 `READ_PHONE_STATE` 可让蜂窝网络细分到 4g/5g，否则蜂窝网络上报为 `unknown`。

### 混淆

SDK 无反射调用，一般无需额外规则；`consumer-rules.pro` 已内置保留 `Analytics` 门面的规则并自动生效。

## API

```kotlin
Analytics.setup(context, appKey, appSecret, endpoint, enable = true, channel = "")
Analytics.enable()                      // 开启采集（补发 device_register，若未发过）
Analytics.disable()                     // 停止采集（事件不再入队）
Analytics.track("button_click", mapOf("id" to 1))        // 自定义事件，event_type=biz
Analytics.track("banner_click", mapOf("banner_id" to "b1"), eventType = "interact")
Analytics.track("banner_exposure", mapOf("banner_id" to "b1"), eventType = "exposure", durationMs = 2100)
// track 完整签名：track(eventName, props = emptyMap(), eventType = "biz", durationMs: Long? = null)
// eventType 仅允许 biz/interact/exposure，非法值按 biz 处理并打 debug 日志；durationMs 仅 exposure 等有时长语义的事件使用
Analytics.trackPage("HomePage")         // 页面进入；离开时补发 page_view（带停留时长/上一页）
Analytics.trackApiError("/v1/list", 500, bizCode = 1001) // 接口异常，网络层钩子调一行
Analytics.setUserId(12345L)             // 传 null 清除；持久化
Analytics.flush()                       // 手动立即上报
Analytics.setDebug(true)                // 打印日志，flush 阈值降为 5 条/5 秒
```

## 内置自动事件

| 事件 | 时机 |
|---|---|
| `device_register` | 每台设备仅首次一次（`enable()` 时补发），带屏幕宽高 |
| `app_start` | 冷启动（含启动耗时）/ 热启动（退后台 >30s 回前台） |
| `app_end` | 退到后台，并立即 flush |
| `page_view` | 页面离开时补发，停留 <100ms 不上报 |
| `app_crash` | 未捕获异常，先写文件，下次启动上报 |

## 缓冲与上报

- flush 触发（先到先触发）：队列满 50 条 / 前台 30 秒定时器 / 退后台；debug 模式 5 条/5 秒。
- 每批 ≤100 条，gzip 压缩，X-Sign 为对压缩后字节的 HMAC-SHA256 hex。
- 失败（429/5xx/网络异常）指数退避 5s → 15s → 60s → 5min 封顶；400/401 直接丢弃。
- 本地文件上限 5000 条，超出丢最旧；进程重启后残留事件自动读回续传。

## 隐私合规（先初始化后采集）

```kotlin
// Application.onCreate：先初始化但不采集
Analytics.setup(this, appKey, appSecret, endpoint, enable = false)

// 用户同意隐私政策后：
Analytics.enable()   // 开始采集，并补发 device_register
```

`enable=false` 期间所有 track/trackPage/trackApiError 调用不会入队，SDK 不发起任何上报请求。

## 手动联调

示例 app 在独立工程 `../demo-android/`（覆盖全部埋点类型）：`DemoApp` 中替换真实的 `appSecret` / `endpoint` 后运行，
`setDebug(true)` 下可在 logcat 过滤 `AnalyticsSDK` 查看事件与上报日志。
模拟器访问宿主机服务用 `http://10.0.2.2:port`。
