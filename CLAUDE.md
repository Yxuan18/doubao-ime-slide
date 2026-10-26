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

**手势行为没有自动化测试，也没接 CI。** 唯一验收是真机手势 + 模块日志 + 控件树，结论和证据等级写进 `ACCEPTANCE.md`。改任何手势行为，都在那里补一行实测记录。

纯 JVM 的判定与几何有单元测试：`app/src/test/java/app/xuan/doubaoslide/`（`GestureGateTest` 16 项 +
`GeometryTest` 11 项），跑 `./gradlew :app:testDebugUnitTest`。它们锁两件事——五段判定（含多指、
死区、阈值边界）和真机实测过的坐标命中；**不覆盖**候选栏位移、反射、`InputConnection`，
所以绿了也不代表手势对。lane 配置在 `.claude/tdd-guardian/config.json`（门禁默认不自动拦截）。

标定铁律，违反它会得到完全错误的结论：**候选栏/组合区一出现，整块键盘会整体上移并改变键宽**，同一个屏幕坐标会落到另一个键上。每次手势测试先用 `Log.DEBUG=true` 构建，从 `DOWN <x>,<y> view=<w>x<h> 字母=<c>` 这一行确认落键，再谈结果。

```bash
# 测试手势（需 root）
adb shell su -c 'input swipe <x> <y1> <x> <y2> <duration_ms>'
# 读日志
adb shell su -c 'grep -a DoubaoSlide /data/adb/lspd/log/modules_*.log | tail -20'
```

### 调试规范：不看图

**不用 `screencap` / `screenrecord`，不生成、不打开、不分析截图，不把图片放进上下文。** 证据只走文本：模块日志、`logcat`、`dumpsys`、`uiautomator dump`、编译与测试结果。

| 要确认的事 | 纯文本怎么确认 |
|---|---|
| 这一下按到了哪个键 | `Log.DEBUG=true` 构建，读 `DOWN <x>,<y> view=<w>x<h> 字母=<c>` |
| 手势被判成什么 | 读 `手势 c -> copy ok=true dy=…` / `手势作废：…` / `补发手势：…` |
| 输入框里现在有什么字 | `adb shell uiautomator dump /sdcard/ui.xml` 后 grep 编辑框的 `text` |
| 当前输入法是哪个、在不在 | `adb shell su -c 'dumpsys input_method \| grep -m2 -e mCurId -e mCurMethodId'` |
| 当前前台页面 | `adb shell su -c 'dumpsys activity \| grep -m1 mCurrentFocus'` |
| 多指有没有被识别 | 有 `多指输入：本次手势不判快捷键`、且**没有** `手势 … -> …` |

**做不到的要明说。** 触感反馈、选中高亮、候选栏长相、键位视觉对齐——这些是渲染结果，纯文本拿不到。它们不算已验收，写进 `ACCEPTANCE.md` 的受限项；不要为了"确认一下"退回截图。

**闭环**：改代码 → 编译 → 安装 → 触发一次 → 读日志或控件树 → 下结论。满足验证条件就停手，不反复戳手机。

> `ACCEPTANCE.md` 里 2026-10-26 之前的条目按当时的标准引用过截屏，保留不改；此后新增证据只用文本。

`Log.DEBUG` 只在排障构建里设 `true`，**交付前必须改回 `false`**——否则每次触摸都往 LSPosed 日志里写几行。

## 架构

六个类，边界别混：

| 类 | 职责 |
|---|---|
| `XposedEntry` | 按包名注入；不是目标包直接 return；`System.setProperty` 做跨 ClassLoader 去重；装配不完整时打警告 |
| `BoardGuard` | 棋盘守卫：`KeyboardJni.getCurrentKbdType()` 只认 `0`(拼音 26) / `2`(英文 26)，其余棋盘一律不武装 |
| `Geometry` | 比例化键位模型（按视图高度缩放），坐标 → 字母 |
| `GestureGate` | 纯判定状态机（`PASS`/`CAPTURE`/`FIRE`/`DEAD`/`REPLAY`），不碰反射与 `View`，JVM 可测 |
| `SlideHook` | 反射调用 `nativeTouch`、扣事件、按判定结论补发；判定本身不在这里 |
| `Actions` | 动作层：`InputConnection.performContextMenuAction`，copy/paste 有剪贴板兜底 |

`GestureGate` 的判定（`FIRE`=60px、`GATE`=12px，视图高 909 时）：

```
maxDy > FIRE                      触发快捷键（带触感）
向下 maxDy > GATE 且终点没回到起点上方   作废：不打字也不触发
其余（轻点 / 上滑 / 横滑）              按原坐标、原时间戳、原顺序整段补发
见过第二根手指（action=5）              跳过快捷键判定，整段补发
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
- 手势状态是全局单例：键盘单实例、触摸事件在 UI 线程串行到达。**多指要单独处理**：第二根手指按下时引擎发的是 `action=5`（不是又一个 0），`handle` 在多指事件里是同一个值，`MOVE` 也不带指针下标——第二根手指落下之后，任何一条坐标都可能属于另一根手指。所以见到 `action=5` 就把这次手势标记为多指，放弃快捷键判定、只做补发。去掉这条限制时，A + C 两指实测误触发过一次 `selectAll`（`ACCEPTANCE.md` 第 5 节）。
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

## 方案讨论（防顺从）

AI 默认顺着说话：提出一个想法，它会用提问者的认知范围组织出一套看起来合理的论证。方案讨论时主动跳出来，不顺着走。默认动作是"我不信没有人做过，去找"。

### JTBD 三问——新功能 / 新工具立项前必答

1. **什么情境下要完成什么 Job？** 描述情境与目标，不描述解决方案。
   本仓的例子：*在手机输入框里要完成"把刚看到的一段话抄到别处"，手指已经在键盘上。*
2. **这个 Job 现在怎么解决？** 必须落实为三种现状之一：**手动做**、**已有脚本或工具**、**干脆不做**。
   本仓的例子：*手动做——长按选词、点"复制"、切应用、长按粘贴，四到五次点击。*
3. **新方案比现状好在哪？** 说不出具体数字就不立项。
   本仓的例子：*四到五次点击压到一次下滑，且拇指不离开键盘。*

**最大的竞争者往往不是另一个工具，是"手动做"和"不做"。** 只跟同类工具比较的方案论证不成立。

### 先查轮子

动手前两件事：仓内 `grep` 确认没有重复实现 + 仓外找成熟实现（现成库、现成 CLI、已有开源项目）。找到就优先复用；决定不用要写明理由。标准库/平台自带能力能做的，先看平台。

本仓的既有结论：LSPosed 骨架与 `InputConnection` 动作层借 `InputMethodBarEnhance`，手势拦截与几何模型没有现成实现，自己实测。

### 验收先于实现

动手前先回答"这个结果怎么检验"，把验收方式（自动化测试 / E2E / 手动观察）写下来，再写实现。无法验证的选择不做。

本仓没有自动化测试，所以这条落成：**先在 `ACCEPTANCE.md` 写下判定线，再改代码**，改完补一行实测。

### 数字化验收线 + 显式降级

每个功能定义可测的验收线（位移阈值、识别率、超时上限）。达不到的部分显式设计降级路径（跳过 + 日志 + 人工处理），不静默吞掉。97% 达标就按 97% 验收，为剩余失败率设计手动纠正，比追 100% 更划算。

本仓的例子：几何只在 1080×909 上对齐过（等级 B），所以阈值全按比例写成 `FIRE_FRAC` / `GATE_FRAC`，换分辨率是"降级到未标定"，不是"算错就乱触发"。

### 认账

涉及取舍时，列出至少一个被放弃的替代方案及放弃理由。本仓已有先例：参考实现 `InputMethodBarEnhance` 只借骨架，手势几何全部自己实测；`v1.0.0` 之前放弃过"UP 时只补一次合成轻点"的写法，理由写在 `ACCEPTANCE.md` 第八节。

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
