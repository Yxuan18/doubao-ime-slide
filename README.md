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
static final float GATE_FRAC = 12f / 909f;   // 超过它才算滑动
static final float FIRE_FRAC = 60f / 909f;   // 超过它才触发快捷键
private static final float STRIP_FRAC = 151f / 909f;  // 顶部候选栏/工具条占比
```

三个值都按视图高度做成比例，换分辨率自动缩放。键位横向权重写在同一个文件的
`ROW1` / `ROW2_MID` / `ROW3_MID` 与 `LEFT_BIG` / `RIGHT_BIG` 里。

## 排障日志

`Log.java` 里把 `DEBUG` 改成 `true` 重新构建，每次按下都会打出视图尺寸、算出的字母、
扣住的坐标与中间事件、最终判定（触发/补发/作废）以及 `performContextMenuAction` 的返回值：

```
DOWN 437,548 view=1080x909 字母=c
扣住 DOWN 420,657 字母=c (1080x909)
扣住 MOVE action=2 403,604
performContextMenuAction(copy, 16908321) = true
手势 c -> copy ok=true dy=80 (fire>60)
补发手势：c moves=31 dy=-164
```

排障时先看 `view=` 后面的尺寸：候选栏出现/消失会让整块键盘上下移动并改宽度，
同一个屏幕坐标可能落到不同的键上，所以标定时以这一行为准。

默认关闭：每个触摸都会打日志，长期开会淹掉 LSPosed 日志。

## 注意事项

- **别用 force-stop 重启输入法。** 在这台设备上，停掉当前默认输入法会让系统回退到另一个
  已启用输入法。恢复办法：
  `adb shell su -c 'ime set com.bytedance.android.doubaoime/.ImeService'`。
- 五个被映射的键（C V X A Z）不再弹豆包的长按备选符号。按住超过 900ms 不动再松开，
  仍然只打出一个字母。
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
  SlideHook.java     核心：在 KeyboardView.nativeTouch 上做三段闸门
  Actions.java       动作层：InputConnection + 剪贴板兜底
  BoardGuard.java    棋盘守卫：只在 kPinyin26 / kEnglish26 上武装
  Geometry.java      26 键几何模型
  Log.java           日志开关
```

## 验收

逐项数字、证据等级、被放弃的方案与已知限制见 [ACCEPTANCE.md](ACCEPTANCE.md)。

## 许可

MIT，见 [LICENSE](LICENSE)。`app/libs/xposed-api-82.jar` 是 rovo89 的 Xposed API
（Apache 2.0），只做编译期依赖，说明见 [app/libs/README.md](app/libs/README.md)。
