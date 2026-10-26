# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 这是什么

一个**没有 Activity 的 APK**：靠清单里的 `xposedmodule` meta-data + `assets/xposed_init` 让 LSPosed 认出它，作用域只有 `com.bytedance.android.doubaoime`。运行时在豆包输入法的 `KeyboardView.nativeTouch(long handle, int x, int y, int action, long time)` 上挂钩，把「按住字母向下滑」翻译成快捷键。

工作区级的中文写作与证据分级规则在 `../AGENTS.md`（仅本地存在）；本文件只写本仓特有的东西，工程红线部分已自带。

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

装机环节的三个坑：

- **`su -c` 里别套引号和转义。** 管道本身没问题（上面最后一条 grep 就是 `| tail`），但 `grep -a 'x|y'`、`grep -aiE "a|b"` 这类内层引号会被吃掉，症状是 `unexpected '('` 或把每个词当文件名。多条件用 `grep -e x -e y`，或者拆成多次 `su -c`。同理 `su -c 'a || b'` 会被解析成错误参数，症状是假的 "Permission denied"。
- **force-stop 默认输入法会让系统回退到另一个输入法。** 紧跟的 `ime set` 负责改回来；漏了就是"键盘没了"。
- **重装 APK 不会自动重载代码。** LSPosed 会弹「已安装」通知，模块开关状态保留，但输入法进程必须重启（`am force-stop` + `ime set`）才会加载新版本。

## 验收

**没有单元测试，也没接 CI。** 唯一验收是真机手势 + 模块日志 + 截屏，结论和证据等级写进 `ACCEPTANCE.md`。改任何手势行为，都在那里补一行实测记录。

标定铁律，违反它会得到完全错误的结论：**候选栏/组合区一出现，整块键盘会整体上移并改变键宽**，同一个屏幕坐标会落到另一个键上。每次手势测试先用 `Log.DEBUG=true` 构建，从 `DOWN <x>,<y> view=<w>x<h> 字母=<c>` 这一行确认落键，再谈结果。

```bash
# 测试手势（需 root）
adb shell su -c 'input swipe <x> <y1> <x> <y2> <duration_ms>'
# 读日志
adb shell su -c 'grep -a DoubaoSlide /data/adb/lspd/log/modules_*.log | tail -20'
```

`Log.DEBUG` 只在排障构建里设 `true`，**交付前必须改回 `false`**——否则每次触摸都往 LSPosed 日志里写几行。

## 架构

五个类，边界别混：

| 类 | 职责 |
|---|---|
| `XposedEntry` | 按包名注入；不是目标包直接 return；`System.setProperty` 做跨 ClassLoader 去重 |
| `BoardGuard` | 棋盘守卫：`KeyboardJni.getCurrentKbdType()` 只认 `0`(拼音 26) / `2`(英文 26)，其余棋盘一律不武装 |
| `Geometry` | 比例化键位模型（按视图高度缩放），坐标 → 字母 |
| `SlideHook` | 手势闸门，唯一的判定中心 |
| `Actions` | 动作层：`InputConnection.performContextMenuAction`，copy/paste 有剪贴板兜底 |

`SlideHook` 的三段判定（`FIRE`=60px、`GATE`=12px，视图高 909 时）：

```
maxDy > FIRE                      触发快捷键（带触感）
向下 maxDy > GATE 且终点没回到起点上方   作废：不打字也不触发
其余（轻点 / 上滑 / 横滑）              按原坐标、原时间戳、原顺序整段补发
```

三条必须读懂的不变量：

1. **引擎永远看不到被映射键的 DOWN。** 引擎收到 DOWN 就把字母塞进自己的预编辑缓冲，吞掉后续事件撤不回来（`getTextBeforeCursor()` 看不到这段引擎内组合，用错探针会假通过）。
2. **补发必须整段，不能压平成 DOWN+UP。** 压平后引擎只能判成轻点，**上滑输符号会失效**——这是 `v1.0.0` 修掉的回退项，细节见 `ACCEPTANCE.md` 第 4 节。
3. **反射调用 `nativeTouch` 会再次进入钩子。** 必须用 `ThreadLocal REPLAYING` 放行，否则补发的 DOWN 被自己再扣一次，死循环。

## 硬约束

- **只用 legacy Xposed API 82**：`XposedBridge.hookMethod/hookAllConstructors`、`XposedHelpers.*`、`param.setResult(null)`。libxposed 现代 API 按设计**不提供 `XposedHelpers`**，迁移不是换依赖那么简单（见 `ACCEPTANCE.md` 开放问题）。
- **`app/libs/xposed-api-82.jar` 只能 `compileOnly`。** 运行时实现由 LSPosed 提供；打进 APK 会冲突。
- **绝不调用 `KeyboardJni` 的静态入口。** 没有实例时那样调会把输入法进程打挂（实测发生过）。只能拿实例、调实例方法，且每次按下重读类型、不缓存。
- **输入法是最不能崩的组件。** 任何异常都 `catch` 住并把事件交回引擎；宁可少一个快捷键，也不要让用户没有键盘。`BoardGuard` / `SlideHook` 的失败路径都遵守这条。
- 手势状态是全局单例：键盘单实例、触摸事件在 UI 线程串行到达。多指同时按下时后一个 DOWN 重置状态，最坏丢一个字母，不会错触发。
- `nativeTouch` 的坐标是**视图局部坐标**；本机视图 1080×909 时顶边在屏幕 y=1401。

## 工程红线（裁剪自 `~/SEC/tstools/CLAUDE.md`）

**P0 — 不许碰**

- 密码 / token / 授权码硬编码，或写进日志与对话记录。
- 把未经校验的外部输入直接交给解析器或反射（本仓对应：`Method.invoke` 前不判空、不校验目标与参数类型）。
- 空 `catch` 静默吞异常（Java 里的 `except: pass`）。至少 `Log.e` 一行。
- 大文件全量 `.read()` / 一次 `readBytes()`。

**P1 — 不炸但让人烦**：循环里做 I/O；网络请求无 timeout；文件句柄不用 try-with-resources；用异常兜业务分支并打堆栈。

**P2 — 规范**：命名混乱、嵌套 ≥ 3 层、硬编码数字/URL；注释写"这段代码做什么"而不是"为什么这样做"。

**例外必须认账**：`// 性能原因` / `// TODO 上线前删除` / `// 平台约束`。没有注释的破例就是偷懒。

**Spike 规则**：不确定性高的想法（库能不能用、接口是否可达、格式能否解析）先做最小可丢弃实现，文件名或目录带 `spike_` 前缀，免检上述红线，两小时出结果。**spike 代码不允许直接转正**——验证通过后按全量标准重写。

**方案讨论的三条**（防顺从）：立项前先过 Job/现状/收益三问，答不出数字就不立项；动手前仓内 grep + 仓外找现成实现，找到就复用、不用要写理由；验收先于实现，先写下"怎么检验"再写代码。

**认账**：每个取舍至少列一个被放弃的替代方案和放弃理由。本仓已有先例：参考实现 `InputMethodBarEnhance` 只借 LSPosed 骨架与 `InputConnection` 动作层，手势几何全部自己实测。

## 发布

```bash
./gradlew :app:assembleRelease
gh release create vX.Y.Z app/build/outputs/apk/release/app-release.apk \
  --title "vX.Y.Z — <一句话>" --notes-file <notes.md> --target main
```

- APK 用 Android 通用 debug 证书签名（`app/build.gradle.kts`）。换签名必须先卸载。
- 不往仓库或 release 里放打包好的第三方 APK（豆包本体、LSPatch 打出来的成品）。
- 发布前核对：`versionName`/`versionCode`、`Log.DEBUG == false`、`ACCEPTANCE.md` 是否补了新行。

## 证据在哪

- `ACCEPTANCE.md`：验收线、逐项实测与证据等级、回退项与根因、被放弃的方案、开放问题。**动手改手势行为之前先读它。**
- `README.md`：面向用户的用法、调阈值、排障日志格式。
- 模块日志：`/data/adb/lspd/log/modules_*.log`（grep `DoubaoSlide`）。
- 系统原生崩溃：`/data/system/dropbox/`（`system_app_native_crash@*.txt`）。
