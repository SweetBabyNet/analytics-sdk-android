plugins {
    id("com.android.library")
    kotlin("android")
    `maven-publish`
}

android {
    namespace = "com.analytics.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

// JitPack 发布配置：groupId/version 会被 JitPack 按
// com.github.<github用户名>:<仓库名>:<git tag> 覆盖，这里的值仅用于本地发布
publishing {
    publications {
        register<MavenPublication>("release") {
            afterEvaluate {
                from(components["release"])
            }
            groupId = "com.analytics.sdk"
            artifactId = "analytics"
            version = "1.0.0"
        }
    }
}
// 零三方依赖：仅 Kotlin 标准库 + Android 框架（org.json 为框架内置）
