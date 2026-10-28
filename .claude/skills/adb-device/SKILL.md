---
name: adb-device
description: 在本机 Android 设备（小米 14 Pro / Android 16）上操作与取证：root shell、取控件文本、截图、注入触摸、读 LSPosed 模块日志、查进程与加载的 so。需要验证真机行为、抓日志、装 APK、模拟手势时使用。含 su -c 引号陷阱、设备掉线、日志文件随进程切换等踩过的坑。
version: 1.0.0
---

# 真机操作（adb）

三条铁律：`su -c` 里不能套引号；设备会掉线；装完 APK 不重启进程等于没装。

## 环境

- 设备：小米 14 Pro，Android 16，1080×2400，已 root（`su` 可用）
- root 命令统一走 `tools/adb-root.sh`（等设备回来，最多 30 秒）
- 构建需要 `export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`
- 本仓目标应用：豆包输入法 `com.bytedance.android.doubaoime`

## 铁律 1：`su -c` 只接一条命令，别再套引号

```sh
# 对：管道可以用
sh tools/adb-root.sh 'grep -a DoubaoSlide /data/adb/lspd/log/modules_*.log | tail -5'

# 错：内层引号被吃掉，症状是 unexpected '(' 或把每个词当文件名
sh tools/adb-root.sh 'grep -a "a|b" file'
# 改用多条件
sh tools/adb-root.sh 'grep -a -e a -e b file'

# 错：`;` 与 `||` 会掉出 root，症状是假的 Permission denied / INJECT_EVENTS
sh tools/adb-root.sh 'input tap 1 1; input tap 2 2'
# 改用脚本
adb push /tmp/x.sh /data/local/tmp/x.sh && sh tools/adb-root.sh 'sh /data/local/tmp/x.sh'
```

## 铁律 2：设备会掉线

`adb devices` 会突然空掉，紧接着的命令报 `no devices/emulators found`。一次调试能碰上三五次。
一律用 `tools/adb-root.sh`；裸 `adb install` / `adb pull` 前先确认 `adb devices | grep -q device`。

## 铁律 3：装完 APK 必须重启进程

```sh
adb install -r app/build/outputs/apk/release/app-release.apk
sh tools/adb-root.sh 'am force-stop com.bytedance.android.doubaoime'
sh tools/adb-root.sh 'ime set com.bytedance.android.doubaoime/.ImeService'
```

force-stop 之后必须紧跟 `ime set`，否则系统回退到别的输入法，键盘没了。

## 取证：文本优先，截图兜底

顺序固定：控件文本 → 系统 dumpsys → 模块日志 → 截图。截图最贵，也最容易看错。

```sh
# 控件树（拿输入框内容、按钮文案、class）
sh tools/adb-root.sh 'uiautomator dump /sdcard/x.xml'
sh tools/adb-root.sh 'cat /sdcard/x.xml' | tr '<' '\n<' | grep -o 'text="[^"]*"'

# 键盘是否显示（后面的 Broken pipe 是正常的）
sh tools/adb-root.sh 'dumpsys input_method | grep -m1 mInputShown'

# 当前前台窗口
sh tools/adb-root.sh 'dumpsys window | grep -m1 mCurrentFocus'

# 进程与 pid（pidof 有时查不到，用 ps）
sh tools/adb-root.sh 'ps -A | grep doubao'

# 进程加载了哪些 so
sh tools/adb-root.sh 'grep -o "/[^ ]*\.so" /proc/<pid>/maps | sort -u'
```

### 模块日志：每次重启 IME 都会换文件

`/data/adb/lspd/log/modules_*.log` 按进程创建时间新建。**每次都重新取最新那个**，不能沿用路径：

```sh
L=$(sh tools/adb-root.sh 'ls -t /data/adb/lspd/log/modules_*.log | head -1')
```

打日志前先记行数，再按 `tail -n +$((M+1))` 取增量，避免把历史混进来：

```sh
M=$(sh tools/adb-root.sh "wc -l $L" | awk '{print $1}')
# ...做动作...
sh tools/adb-root.sh "tail -n +$((M+1)) $L | grep -a -e 'DOWN ' -e '手势'"
```

grep 里带方括号/引号会踩铁律 1；需要复杂文本处理就把脚本推上去跑。

## 截图

```sh
sh tools/adb-root.sh 'screencap -p /data/local/tmp/x.png'
adb pull /data/local/tmp/x.png /tmp/x.png
# 先裁掉无关区域再读：整图看不清细节，也浪费上下文
ffmpeg -y -i /tmp/x.png -vf "crop=1080:940:0:1390,scale=520:-1" /tmp/xs.png
```

然后用 Read 工具看 `/tmp/xs.png`。裁剪比例记在 `doc/02-调试与验收手册.md`。

## 注入触摸

```sh
sh tools/adb-root.sh 'input tap 540 1200'                  # 屏幕坐标
sh tools/adb-root.sh 'input swipe 432 2025 432 2163 300'   # 起点 终点 时长ms
sh tools/adb-root.sh 'input motionevent DOWN 432 2025'     # 逐条控制时序
```

- **每次 adb 调用有 150–250ms 延迟。** 需要 200ms 内完成的序列（双击、快速两击）必须写成
  脚本推上去，否则两次点击间隔必然超时。
- `input motionevent` 可以按住再拖（先 DOWN，sleep，再 MOVE，再 UP），用来模拟长按；
  但它**造不出真多指**。
- 点击返回成功 ≠ 事件到达。某个区域可能被别的视图吃掉，**必须用日志或控件文本确认**。

## 坐标：屏幕坐标与视图坐标是两套

- `input tap/swipe` 用**屏幕坐标**
- `KeyboardView.nativeTouch` 收的是**视图局部坐标**
- 本机键盘视图：1080×909，顶边在屏幕 y=1401（候选栏出现时也不变）
- 屏幕 y → 视图 y：`y_view = y_screen - 1401`

拿不准就先用设置页/日志里的 `DOWN x,y view=WxH` 标定，再谈结果。

## 停下来先问的情况

- 会改用户设置的操作（切换键盘类型、开关、清除数据）
- 可能让输入法崩溃的调用
- 卸载、清数据、重启设备
- 往设备写文件（除 `/data/local/tmp` 与 `/sdcard` 的临时取样）

## 证据纪律

- 拿不到文本证据就直接说拿不到，不用"看起来对了"代替。
- 日志里的 `ok=true` 只说明调用被受理，不说明动作生效（见 `ACCEPTANCE.md`）。
- 一次动作后 [日志] 与 [界面] 对不上时，先怀疑坐标或进程没重启，再怀疑代码。
