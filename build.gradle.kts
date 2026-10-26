// 顶层构建脚本：豆包输入法下滑快捷键（LSPosed 模块）
//
// 只声明 Android 应用插件。Xposed API 以本地 jar 形式 compileOnly 引入
// （见 app/build.gradle.kts），不联网也能构建。
plugins {
    id("com.android.application") version "8.5.2" apply false
}
