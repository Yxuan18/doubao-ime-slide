# 第三方 jar

`xposed-api-82.jar` 是 rovo89 的 Xposed API 82 编译产物，来自
<https://api.xposed.info/repo/de/robv/android/xposed/api/82/api-82.jar>
（对应源码 <https://github.com/rovo89/XposedBridge>，Apache License 2.0）。

它只以 `compileOnly` 引入：编译期提供 `IXposedHookLoadPackage`、`XposedBridge` 等符号，
运行期由 LSPosed 自带实现提供，**不会打包进 APK**。仓库里放一份是为了不联网也能构建。

如果嫌仓库里带二进制，可以改成从 Maven 仓库取（需要一个可用的 api.xposed.info 镜像）：

```kotlin
repositories { maven("https://api.xposed.info/") }
dependencies { compileOnly("de.robv.android.xposed:api:82") }
```
