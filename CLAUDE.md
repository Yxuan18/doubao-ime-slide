# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 这是什么

一个**没有 Activity 的 APK**：靠清单里的 `xposedmodule` meta-data + `assets/xposed_init` 让 LSPosed 认出它，作用域只有 `com.bytedance.android.doubaoime`。运行时在豆包输入法的 `KeyboardView.nativeTouch(long handle, int x, int y, int action, long time)` 上挂钩，把「按住字母向下滑」翻译成快捷键。

工作区级的中文写作与证据分级规则在 `../AGENTS.md`（仅本地存在）；本文件只写本仓特有的东西。参考性的细节都拆到了 `doc/`，见文末索引。

## 构建与装机

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools   # 需要 platform 34
./gradlew :app:assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk

adb install -r app/build/outputs/apk/release/app-release.apk
adb shell su -c 'am force-stop com.bytedance.android.doubaoime'
adb shell su -c 'ime set com.bytedance.android.doubaoime/.ImeService'
adb shell su -c 'grep -a DoubaoSlide /data/adb/lspd/log/modules_*.log | tail -5'
```

三个必须记住的：`su -c` 里别套引号和转义；force-stop 之后必须紧跟 `ime set`，否则键盘没了；
**重装 APK 不会重载代码**，输入法进程必须重启。展开与其它坑见 `doc/02-调试与验收手册.md`。

## 验收

**手势行为没有自动化测试，也没接 CI。** 唯一验收是真机手势 + 模块日志 + 控件树，
结论和证据等级写进 `ACCEPTANCE.md`。**改任何手势行为，都在那里补一行实测记录。**

纯 JVM 的判定与几何有单元测试（`GestureGateTest` 28 项 + `GeometryTest` 11 项），
跑 `./gradlew :app:testDebugUnitTest`。它们锁五段判定（含多指、死区、阈值边界、LEAVE 语义）
和真机实测过的坐标命中；**不覆盖**反射、`InputConnection`、渲染，绿了也不代表手势对。
lane 配置在 `.claude/tdd-guardian/config.json`（门禁默认不自动拦截）。

**标定**：`nativeTouch` 收的是视图局部坐标（本机视图 1080×909、顶边在屏幕 y=1401）。
每次手势测试先用 `Log.DEBUG=true` 构建，从 `DOWN <x>,<y> view=<w>x<h> 字母=<c>` 确认落键，
再谈结果——候选栏出现时键盘在**屏幕上**的位置会变，屏幕坐标不能直接当视图坐标用。

`Log.DEBUG` **当前固定为 `true`**（排障期，见 `Log.java` 的注释）。

**待办**：把它做成"装完 APK 之后可运行时切换"——需要模块 App 进程与输入法进程的跨进程通信
（编译期常量做不到；API 82 的 jar 里有 `XSharedPreferences` 可用）。**在这个开关落地之前，
发版前必须手动改回 `false`**，否则交付包每次触摸都往 LSPosed 日志里写几行。

## 架构

七个类，边界别混：

| 类 | 职责 |
|---|---|
| `XposedEntry` | 按包名注入；不是目标包直接 return；`System.setProperty` 做跨 ClassLoader 去重；装配不完整时打警告 |
| `BoardGuard` | 棋盘守卫：`KeyboardJni.getCurrentKbdType()` 只认 `0`(拼音 26) / `2`(英文 26)，其余棋盘一律不武装 |
| `Geometry` | 比例化键位模型（按视图高度缩放），坐标 → 字母 |
| `GestureGate` | 纯判定状态机（`PASS`/`CAPTURE`/`FIRE`/`DEAD`/`REPLAY`/`HANDBACK`），不碰反射与 `View`，JVM 可测 |
| `SlideHook` | 反射调用 `nativeTouch`、扣事件、按判定结论补发；判定本身不在这里 |
| `Actions` | 动作层：`InputConnection.performContextMenuAction`，copy/paste 有剪贴板兜底 |
| `Log` | 日志出口：`i` 受 `DEBUG` 控制、`always` 生命周期、`once` 预期内失败只打一次、`e` 异常带堆栈 |

`GestureGate` 的判定（`FIRE`=60px、`GATE`=12px，视图高 909 时）：

```
maxDy > FIRE                      触发快捷键（带触感）
向下 maxDy > GATE 且终点没回到起点上方   作废：不打字也不触发
其余（轻点 / 上滑 / 横滑）              按原坐标、原时间戳、原顺序整段补发
见过第二根手指（action=5）              跳过快捷键判定，整段补发
按住 HAND_BACK_MS(300ms) 没滑过 FIRE    把这次按下归还给引擎，让长按浮层能画出来
引擎发来 LEAVE(-1)                     永远不清判定状态；已归还时放行（引擎要靠它画浮层第二格），
                                      未归还时吞掉
```

三条必须读懂的不变量：

1. **引擎看不到被映射键的 DOWN——直到归还为止。** 引擎收到 DOWN 就把字母塞进自己的预编辑缓冲，吞掉后续事件撤不回来（`getTextBeforeCursor()` 看不到这段引擎内组合，用错探针会假通过）。
   **例外**：按住 `HAND_BACK_MS`（300ms）还没滑过触发线时，把这一次按下补发给引擎，
   引擎的组合区会多出这个字母，触发快捷键时要发一条 `CANCEL` 撤掉。
   引擎的 native 长按浮层要画出来，需要同时看到 `nativeTouch(0)`（DOWN）和
   `nativeTouch(-1)`（LEAVE）——两样都拦就只剩中间一个组合预览气泡。
   所以归还之后**必须放行 LEAVE**，否则浮层画不出第二格。
   反过来，**未归还时 LEAVE 必须吞掉、且不能清判定状态**——那条 LEAVE 是 Java 层
   501ms 计时器发的，与引擎是否见过 DOWN 无关；用它清状态会让"先滑过触发线、
   再停住"的手势静默失效（2026-10-26 复审发现并修掉）。
2. **补发必须整段，不能压平成 DOWN+UP。** 压平后引擎只能判成轻点，**上滑输符号会失效**——这是 `v1.0.0` 修掉的回退项，细节见 `doc/05-历史修复记录.md` 第 4 节。
3. **反射调用 `nativeTouch` 会再次进入钩子。** 必须用 `ThreadLocal REPLAYING` 放行，否则补发的 DOWN 被自己再扣一次，死循环。

## 硬约束

- **只用 legacy Xposed API 82**：`XposedBridge.hookMethod/hookAllConstructors`、`XposedHelpers.*`、`param.setResult(null)`。libxposed 现代 API 按设计**不提供 `XposedHelpers`**，迁移不是换依赖那么简单（见 `ACCEPTANCE.md` 开放问题）。
- **`app/libs/xposed-api-82.jar` 只能 `compileOnly`。** 运行时实现由 LSPosed 提供；打进 APK 会冲突。
- **绝不调用 `KeyboardJni` 的静态入口。** 没有实例时那样调会把输入法进程打挂（实测发生过）。只能拿实例、调实例方法，且每次按下重读类型、不缓存。
- **输入法是最不能崩的组件。** 任何异常都 `catch` 住并把事件交回引擎；宁可少一个快捷键，也不要让用户没有键盘。`BoardGuard` / `SlideHook` 的失败路径都遵守这条。
- 手势状态是全局单例：键盘单实例、触摸事件在 UI 线程串行到达。**多指要单独处理**：第二根手指按下时引擎发的是 `action=5`（不是又一个 0），`handle` 在多指事件里是同一个值，`MOVE` 也不带指针下标——第二根手指落下之后，任何一条坐标都可能属于另一根手指。所以见到 `action=5` 就把这次手势标记为多指，放弃快捷键判定、只做补发。去掉这条限制时，A + C 两指实测误触发过一次 `selectAll`（`doc/05-历史修复记录.md` 第 5 节）。

## 工程红线（P0 摘要）

- 密码 / token / 授权码不进代码、日志与对话记录。
- `Method.invoke` 前判空、校验目标与参数类型。
- 不写空 `catch`；至少一行日志（本仓用 `Log.once` / `Log.e`）。
- 不做大文件全量读。

P1 / P2 清单、例外认账与 Spike 规则见 `doc/03-工程红线.md`（裁剪自 `~/SEC/tstools/CLAUDE.md`）。

## 方案讨论（防顺从）

默认动作是 **"我不信没有人做过，去找"**。新功能立项前先答 JTBD 三问（什么情境下的
什么 Job / 现在怎么解决 / 比现状好多少），答不出具体数字就不立项；最大的竞争者通常是
"手动做"和"不做"。完整规则与本仓例证见 `doc/04-方案讨论与防顺从.md`，
工作区级同一套规则在 `../AGENTS.md`。

## 发布

```bash
./gradlew :app:assembleRelease
gh release create vX.Y.Z app/build/outputs/apk/release/app-release.apk \
  --title "vX.Y.Z — <一句话>" --notes-file <notes.md> --target main
```

- APK 用 Android 通用 debug 证书签名（`app/build.gradle.kts`）。换签名必须先卸载。
- 不往仓库或 release 里放打包好的第三方 APK（豆包本体、LSPatch 打出来的成品）。
- 发布前核对：`versionName`/`versionCode`、`Log.DEBUG == false`、`ACCEPTANCE.md` 是否补了新行。

## 证据与文档

- `ACCEPTANCE.md`：验收线、逐项实测与证据等级、回退项与根因、被放弃的方案、开放问题。**动手改手势行为之前先读它。**
- `README.md`：面向用户的用法、调阈值、排障日志格式。
- 模块日志：`/data/adb/lspd/log/modules_*.log`（grep `DoubaoSlide`）；系统原生崩溃：`/data/system/dropbox/`（`system_app_native_crash@*.txt`）。

`doc/` 下是非主线参考，按需查，不必通读：

| 文件 | 内容 |
|---|---|
| `doc/01-长按浮层缺失根因与修复.md` | 长按浮层 bug 的完整定位过程、四组对照、复盘教训 |
| `doc/02-调试与验收手册.md` | 装机坑、文本探针表、截屏差分配方、标定与闭环、证据介质变更史 |
| `doc/03-工程红线.md` | P0/P1/P2 完整清单、例外认账、Spike 规则 |
| `doc/04-方案讨论与防顺从.md` | JTBD 三问、先查轮子、验收先于实现、数字化验收线、认账 |
| `doc/05-历史修复记录.md` | 三个已关闭问题的定位过程：上滑输符号、多指误触发、`GestureGate` 重构 |
