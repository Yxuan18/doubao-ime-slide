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
sh tools/adb-root.sh 'am force-stop com.bytedance.android.doubaoime'
sh tools/adb-root.sh 'ime set com.bytedance.android.doubaoime/.ImeService'
sh tools/adb-root.sh 'ls -t /data/adb/lspd/log/modules_*.log | head -1'
```

四个必须记住的：`su -c` 里别套引号和转义；force-stop 之后必须紧跟 `ime set`，否则键盘没了；
**重装 APK 不会重载代码**，输入法进程必须重启；**设备会掉线**，用 `tools/adb-root.sh`
而不是裸 `adb shell`（它会等设备回来再执行）。
展开与其它坑见 `doc/02-调试与验收手册.md`。

## 验收

**手势行为没有自动化测试，也没接 CI。** 唯一验收是真机手势 + 模块日志 + 控件树，
结论和证据等级写进 `ACCEPTANCE.md`。**改任何手势行为，都在那里补一行实测记录。**

纯 JVM 的判定与几何有单元测试（`GestureGateTest` 32 项 + `GeometryTest` 11 项），
跑 `./gradlew :app:testDebugUnitTest`。它们锁五段判定（含多指、死区、阈值边界、LEAVE 语义）
和真机实测过的坐标命中；**不覆盖**反射、`InputConnection`、渲染，绿了也不代表手势对。
lane 配置在 `.claude/tdd-guardian/config.json`（门禁默认不自动拦截）。

**标定**：`nativeTouch` 收的是视图局部坐标（本机视图 1080×909、顶边在屏幕 y=1401）。
每次手势测试先用 `Log.DEBUG=true` 构建，从 `DOWN <x>,<y> view=<w>x<h> kbd=<n> 字母=<c>` 确认落键与棋盘号，
再谈结果——屏幕坐标不能直接当视图坐标用（视图顶边实测恒为屏幕 y=1401、高度 909，
候选栏出现时也不变，见 `doc/08` §6）。

`Log.DEBUG` 是**运行时开关**：装完 APK 从桌面图标打开设置页拨一下，**下一次按键就生效，
不需要重启输入法**。配置文件读不到时回落到 `Config.DEFAULT_DEBUG`——**发版前把它改成 `false`**，
交付包默认应该是安静的。

它是 `volatile` 而不是 `static final`：编译期常量会被 javac 内联，取 `false` 时
`if (Log.DEBUG)` 整块被删掉，运行时就再也打不开了。

## 架构

九个类，边界别混：

| 类 | 职责 |
|---|---|
| `XposedEntry` | 按包名注入；不是目标包直接 return；`System.setProperty` 做跨 ClassLoader 去重；装配不完整时打警告 |
| `BoardGuard` | 棋盘守卫：`KeyboardJni.getCurrentKbdType()` 只认 `0`(拼音 26) / `2`(英文 26)，其余棋盘一律不武装 |
| `Geometry` | 比例化键位模型（按视图高度缩放），坐标 → 字母 |
| `GestureGate` | 纯判定状态机（`PASS`/`CAPTURE`/`FIRE`/`DEAD`/`REPLAY`/`HANDBACK`），不碰反射与 `View`，JVM 可测 |
| `SlideHook` | 反射调用 `nativeTouch`、扣事件、按判定结论补发；判定本身不在这里 |
| `Actions` | 动作层：`InputConnection.performContextMenuAction`，copy/paste 有剪贴板兜底 |
| `Log` | 日志出口：`i` 受 `DEBUG` 控制、`always` 生命周期、`once` 预期内失败只打一次、`e` 异常带堆栈；`refresh()` 每根手指读一次配置，`heartbeat()` 供设置页显示注入状态 |
| `Config` | 设置项的存储契约（prefs 名、键名、默认值）。模块 App 写、注入进程读，两端必须同名 |
| `SettingsActivity` | 设置页：一个 DEBUG 开关 + 一屏诊断。跑在模块自己的进程里，不在豆包进程里 |

`GestureGate` 的判定（`FIRE`=60px、`GATE`=12px，视图高 909 时）：

```
maxDy > FIRE                      触发快捷键（带触感）
向下 maxDy > GATE 且终点没回到起点上方   作废：不打字也不触发
其余（轻点 / 上滑 / 横滑）              按原坐标、原时间戳、原顺序整段补发
见过第二根手指（action=5）              跳过快捷键判定，整段补发
按住 HAND_BACK_MS(300ms) 没滑过 FIRE    把这次按下归还给引擎，让长按浮层能画出来
归还后横移 > 24px 且大于纵移           整段交给引擎（长按浮层里选字），不触发快捷键
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
   归还之后还要在「横拖选字」和「下滑快捷键」之间分叉：第一次横向位移超过 24px 且大于
   纵移时，这一下整个交给引擎（`GestureGate.engineOwned`）——豆包自己的长按浮层就是靠
   横向拖动切换大写/字符/小写的，全扣住会让浮层"画得出来但选不中"（`ACCEPTANCE.md` 第 47 项）。
   反过来，**未归还时 LEAVE 必须吞掉、且不能清判定状态**——那条 LEAVE 是 Java 层
   501ms 计时器发的，与引擎是否见过 DOWN 无关；用它清状态会让"先滑过触发线、
   再停住"的手势静默失效（2026-10-26 复审发现并修掉）。
2. **补发必须整段，不能压平成 DOWN+UP。** 压平后引擎只能判成轻点，**上滑输符号会失效**——这是 `v1.0.0` 修掉的回退项，细节见 `doc/05-历史修复记录.md` 第 4 节。
3. **反射调用 `nativeTouch` 会再次进入钩子。** 必须用 `ThreadLocal REPLAYING` 放行，否则补发的 DOWN 被自己再扣一次，死循环。

## 健康护栏

模块钩的是 `KeyboardView.nativeTouch(long,int,int,int,long)` 这个**具体签名**。豆包一改它，
模块就挂不上——而且是**静默**的：进程照常注入，键盘照常能用，只是手势没了。
用户看到的是"功能坏了"，不是"版本不兼容"。

**不用版本号判断。** 版本是**代理信号**：豆包每发一个小版本就会误报，而手势未必真的坏了；
反过来版本没变也可能坏（配置漂移）。要回答的是"模块此刻能不能干活"，只有运行时事实能答：

| 信号 | 谁写 | 说明 |
|---|---|---|
| 心跳 `hooked=` | `XposedEntry` 装配之后，值来自 `SlideHook.HOOKED` | `nativeTouch` 找到并 hook 成功没有 |
| 心跳 `svc=` | 同上，值来自 `SlideHook.install()` 的返回值 | 动作通道（`ImeService` 构造钩子）接上没有 |
| `fire.log` `result=` | 每次快捷键执行后由 `Log.fire()` 写 | `ok` / `nochannel` / `noresid` / `exception` / `editor` |

设置页只对**模块侧坏了**的三种结果报警（`nochannel` 找不到输入连接、`noresid` 动作名查不到
资源 id、`exception` 抛异常）。`editor`（没选区时复制、空撤销栈时撤销）是编辑器自己拒绝，
日常就会发生，**不报警**——拿它当故障和拿版本号当判据是同一个错误。

**心跳必须写在装配之后。** 写在前面的话，豆包改了钩子目标也会记成"已注入"，
护栏自己失效。`XposedEntry` 里因此把心跳挪到了 `SlideHook.install()` 之后，
异常路径单独写一条 `hooked=false svc=false`。改了装配顺序时记得同步这条。

升级豆包后的第一件事：看设置页顶部那句话。它只在这四件事之一成立时才转成提示：
没注入、钩子没挂上、动作通道没接上、上一次快捷键在模块侧失败。

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

## 输出风格

- **结论先行。** 第一段就是答案，不铺垫过程、不复述刚做的操作。
- **不重复已有记录。** 写进 `ACCEPTANCE.md` / `doc/` 的数据不再在对话里复述一遍。
- **能一句说完的不写三段。** 汇报改动只需：改了什么、为什么、验没验。
- **表格只在多列并排时用**，不要为了排版把两句话拆成表。
- 需要用户拍板时，只问那一个具体问题，不做开放式征询。

## 执行要求

- 先读文件，再动手。改动前先看 `ACCEPTANCE.md` 与相关 `doc/`。
- 倾向编辑，不倾向重写。
- **声称完成前必须测试**：改 `.java` 至少跑 `./gradlew :app:testDebugUnitTest`，
  改手势行为还必须真机跑一遍并补 `ACCEPTANCE.md` 一行。
- 用户指令始终优先于此文件。

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
| `doc/06-逆向-总览与可扩展点.md` | **豆包 1.5.3 反编译的裁决文档**：值得做/再看的扩展点、四个技术债、被放弃的方向。动手扩展前先读它 |
| `doc/07-逆向-手势与键盘视图.md` | 触摸链路、安全接缝、光标/浮动/连续删除等 native 能力 |
| `doc/08-逆向-布局棋盘与皮肤.md` | 布局 XML schema、15 种棋盘号、键位几何、工具栏与皮肤 |
| `doc/09-逆向-文本与编辑能力面.md` | 剪贴板历史/常用语/清空；撤销无效的源码解释 |
| `doc/10-逆向-架构与工程借鉴.md` | Java/native 分工、混淆策略、179 MB 的真实体积分布 |
| `doc/11-逆向-用户体验观察.md` | 豆包的设置体系与反馈设计；模块与原生行为的四处不一致 |

`tools/adb-root.sh`：等设备回来再执行一条 root shell 命令（USB 会掉线）。
