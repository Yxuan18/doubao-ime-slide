# 豆包下滑快捷键（doubao-ime-slide）

给豆包输入法的 26 键键盘加一层手势：**按住一个字母往下滑，执行对应快捷键；轻点照常打字。**

| 字母 | 动作 | 落地方式 |
|---|---|---|
| C | 复制 | `InputConnection.performContextMenuAction(android.R.id.copy)` |
| V | 粘贴 | 同上，`paste` |
| X | 剪切 | 同上，`cut` |
| A | 全选 | 同上，`selectAll` |
| Z | 撤销 | 同上，`undo` |

动作全部走 Android 标准 `InputConnection` 通道，不碰豆包自己的编辑逻辑。

## 前置条件

- 已 root，已装 LSPosed（本项目在 LSPosed 2.2.0-it 上验收，Android 16 / API 36）
- 豆包输入法 `com.bytedance.android.doubaoime`（验收版本 1.5.3）

## 安装

```bash
export ANDROID_HOME=<Android SDK 路径>
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

然后在 LSPosed 管理器里：

1. 打开「模块」，启用「豆包下滑快捷键」。
2. 点进模块的作用域，只勾 `豆包输入法`（`com.bytedance.android.doubaoime`）。
   不要勾「系统框架」。
3. 让输入法进程重新起来，让模块注入。两条路：
   - 重启手机（最稳）；
   - 或者 `adb shell su -c 'am force-stop com.bytedance.android.doubaoime'`，
     然后再把豆包设回默认输入法（见下面的注意事项）。

注入成功后，LSPosed 日志里出现三行：

```
[DoubaoSlide] 注入 com.bytedance.android.doubaoime pid=…
[DoubaoSlide] 棋盘守卫就绪：只武装 kPinyin26 / kEnglish26
[DoubaoSlide] 手势闸门就绪：C 复制 / V 粘贴 / X 剪切 / A 全选 / Z 撤销
```

日志在 LSPosed 管理器的「日志」页，或 `/data/adb/lspd/log/modules_*.log`。

## 用法

- **轻点**：正常打出一个字母。
- **按住往下滑超过约 60px 再松手**：执行该字母的快捷键，伴随一次触感反馈。
- **下滑 12–60px 之间**：作废，既不打字也不触发——留给手抖的空间。
- **上滑、横滑**：按原始轨迹补发回引擎，所以豆包原本的上滑输符号照旧工作
  （实测：C 上滑出 `#`，G 上滑出 `(`）。

只在 26 键字母面（拼音与英文）生效。数字面、符号面、表情面完全不受影响。

## 改键位

编辑 `app/src/main/java/app/xuan/doubaoslide/SlideHook.java` 里的 `SHORTCUTS`：

```java
map.put('c', "copy");
map.put('v', "paste");
```

动作名要与 `Actions` 认识的一致：`copy`、`paste`、`cut`、`selectAll`、`undo`、`redo`。
`copy` 和 `paste` 有剪贴板兜底通道；其余动作只有 `performContextMenuAction` 一条路。

## 调阈值

`Geometry.java`：

```java
static final float GATE_FRAC = 12f / 909f;    // 超过它才算滑动
static final float FIRE_FRAC = 60f / 909f;    // 超过它才触发快捷键
private static final float TOP_FRAC = 156.75f / 909f;     // 顶部非键区（键框顶）
private static final float BOTTOM_FRAC = 3.75f / 909f;    // 底部内边距
```

四个值都按视图高度做成比例，换分辨率自动缩放。键位横向权重写在同一个文件的
`ROW1` / `ROW2_MID` / `ROW3_MID` 与 `LEFT_BIG` / `RIGHT_BIG` 里。

`TOP_FRAC` 是从豆包的布局资源推出来的：棋盘类的 `padding1x` 上边距 57 减去
`inset` 的上内缩 4.75，得 52.25 个设计单位；设计单位 × 3.0（`min(屏宽,屏高)/360`）
= 156.75 px。它是**键框**的顶边，不是键面（键面在 171）。改这个值之前先看
`doc/08-逆向-布局棋盘与皮肤.md` §3。

## 设置页

装完 APK 后桌面上会多一个「豆包下滑快捷键」图标，点开就是设置页：

- **详细日志（DEBUG）**：拨一下即可。**下一次按键就生效，不用重启输入法**，也不用重新打包。
- **诊断信息**：模块与豆包输入法的版本、当前配置值、配置文件路径、键盘进程最近一次被注入的
  时间。模块没生效时先看这一屏——「键盘进程」一直是空的，说明模块没注入到豆包，
  问题在 LSPosed 那边，不在这几个开关上。

配置存在模块 App 的 `SharedPreferences` 里，由 LSPosed 重定向到一个输入法进程读得到的
共享目录。配置读不到时会回落到代码里的默认值（`Config.DEFAULT_DEBUG`），键盘不会因此变哑。

## 排障日志

在设置页把「详细日志」打开，每次按下都会打出视图尺寸、算出的字母、
扣住的坐标与中间事件、最终判定（触发/补发/作废）以及 `performContextMenuAction` 的返回值：

```
DOWN 437,548 view=1080x909 字母=c
事件 action=0 handle=-5476376677671717424 437,548 deferred=false
扣住 DOWN 420,657 字母=c (1080x909)
扣住 MOVE action=2 403,604
performContextMenuAction(copy, 16908321) = true
手势 c -> copy ok=true dy=80 (fire>60)
补发手势：c moves=31 dy=-164
多指输入：本次手势不判快捷键
```

`事件 action=…` 是逐事件日志：`action` 是引擎透出的动作码（0 按下、1 抬起、
2 移动、5 第二根手指按下），`handle` 在同一段触摸里保持不变。多指问题只能靠它定位。

排障时先看 `view=` 后面的尺寸和 `字母=`：键盘在**屏幕上**的位置会随版面变化，
屏幕坐标不能直接当视图坐标用（视图局部坐标 = 屏幕坐标 − 视图顶边，本机 1401）。
2026-10-26 的复测里，带与不带候选栏都是 `view=1080x909` 且落键不变；
但顶层边本身会变，所以标定以这一行为准，不要缓存那个换算。

默认值见 `Config.DEFAULT_DEBUG`（发版应为 `false`）：每个触摸都会打日志，长期开会淹掉 LSPosed 日志。

## 注意事项

- **别用 force-stop 重启输入法。** 在这台设备上，停掉当前默认输入法会让系统回退到另一个
  已启用输入法。恢复办法：
  `adb shell su -c 'ime set com.bytedance.android.doubaoime/.ImeService'`。
- **五个被映射的键（C V X A Z）长按仍然会弹出豆包自带的浮层**（大写 / 字符 / 小写），
  和未映射的字母一样。按住约 0.3 秒后模块会把这次按下归还给引擎，浮层随即出现。
  代价是浮层比未映射键晚约 0.3 秒——因为模块要先确认「你没有在下滑」。
  浮层出现后继续往下滑，快捷键照常触发（会看到触感反馈），字母不会残留在输入框里。
  只想打字不想触发快捷键：短按（0.3 秒内松手）即可，走的是原生轻点路径。
- **两根手指同时落在键盘上时，本次手势不触发快捷键**，只把原始轨迹补发回引擎。
  这是刻意的：引擎在 `MOVE` 里不带指针下标，`handle` 在多指事件里也是同一个值，
  分不清哪条坐标属于哪根手指。实测不做这个限制时，A + C 两指会误触发一次全选。
  单指输入（包括两指打字但每次只落一指）不受影响。
- 豆包会同时保留多个进程（主进程、`:push`、`:upgrade`、`:sandboxed_process0`），
  模块会注入每一个；手势逻辑只在带键盘视图的那个进程里实际生效。
- 输入法升级后如果包名、`KeyboardView` 或 `KeyboardJni` 改名，模块会自动退化为不启用
  （日志里有 “找不到 …，模块未启用”），键盘回到原样，不会吃掉触摸。

## 卸载

在 LSPosed 管理器里停用模块，再 `adb uninstall app.xuan.doubaoslide`。

## 代码结构

```
app/src/main/java/app/xuan/doubaoslide/
  XposedEntry.java   入口，按包名注入，跨 ClassLoader 去重
  SlideHook.java     反射调用 nativeTouch、扣事件、整段补发
  GestureGate.java   纯判定状态机（FIRE / DEAD / REPLAY …），JVM 可测
  Actions.java       动作层：InputConnection + 剪贴板兜底
  BoardGuard.java    棋盘守卫：只在 kPinyin26 / kEnglish26 上武装
  Geometry.java      26 键几何模型
  Log.java           日志开关
```

## 验收

逐项数字、证据等级、被放弃的方案与已知限制见 [ACCEPTANCE.md](ACCEPTANCE.md)。

`GestureGate`（五段判定）与 `Geometry`（键位换算与阈值比例）有 JVM 单元测试，不需要设备：

```bash
./gradlew :app:testDebugUnitTest
```

手势闸门、动作层、棋盘守卫绑在 Android 与 Xposed 反射上，只能真机验收。

## 许可

MIT，见 [LICENSE](LICENSE)。`app/libs/xposed-api-82.jar` 是 rovo89 的 Xposed API
（Apache 2.0），只做编译期依赖，说明见 [app/libs/README.md](app/libs/README.md)。
