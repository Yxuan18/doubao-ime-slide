// 豆包输入法下滑快捷键（LSPosed 模块）
//
// 构建约束（本机实测）：Gradle 8.9 / AGP 8.5.2 / SDK platform 34 已缓存，
// Kotlin Gradle 插件未缓存。模块因此用 Java 编写：离线可构建，且不把 Kotlin
// 标准库塞进 APK。
plugins {
    id("com.android.application")
}

android {
    namespace = "app.xuan.doubaoslide"

    compileSdk = 34

    defaultConfig {
        // 模块自己的 applicationId，与目标（豆包输入法）无关：LSPosed 靠
        // assets/xposed_init + meta-data 识别模块，不靠包名。
        applicationId = "app.xuan.doubaoslide"
        minSdk = 26
        targetSdk = 34

        versionCode = 3
        versionName = "1.1.0"
    }

    buildTypes {
        release {
            // 自用模块：复用 debug 签名，未签名的 APK 系统会拒绝安装。
            // 换签名需要先卸载旧包（见 AGENTS.md 的安装步骤）。
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Xposed API 82：仅编译期可见，运行时由 LSPosed 提供，绝不能打包进 APK。
    compileOnly(files("libs/xposed-api-82.jar"))

    // 只有 Geometry 是纯 JVM 代码，能用单元测试锁住；手势闸门仍旧只能真机验收。
    testImplementation("junit:junit:4.13.2")
}
