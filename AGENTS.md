# AGENTS.md — AI 协作入口

> Codex / agy 的入口；Claude Code 读同目录 [`CLAUDE.md`](CLAUDE.md)。本文件是**摘要 + 链接**：
> 每条只留一句，细节一律去链接的文件里看；与本文件冲突时以 [`CLAUDE.md`](CLAUDE.md) 为准。
> 注意：工作区（仓库外）的约定是"规则只进 AGENTS.md"，**本仓相反**——细节进 CLAUDE.md。

## 项目是什么

一个 LSPosed 模块：在豆包输入法（`com.bytedance.android.doubaoime`）的 26 键键盘上，
按住字母向下滑执行复制 / 粘贴 / 剪切 / 全选 / 撤销；轻点、上滑、横滑、长按保持原生行为。
Java 17；单测只覆盖纯 JVM 的判定与几何，**手势行为没有自动化测试**，只能真机验收。

## 必读顺序

1. [`README.md`](README.md) — 用户视角：能做什么、怎么装、怎么排障
2. [`CLAUDE.md`](CLAUDE.md) — 仓库手册：构建命令、架构、三条不变量、硬约束
3. [`ACCEPTANCE.md`](ACCEPTANCE.md) — 逐项编号的实测/复核台账。**动手改手势行为前必读**
4. [`doc/06-逆向-总览与可扩展点.md`](doc/06-逆向-总览与可扩展点.md) — 扩展裁决书：值得做 / 不该做 / 技术债

改代码时第 2、3 两份不能跳。

## 常用命令

| 做什么 | 命令 |
|---|---|
| 构建 | `export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools && ./gradlew :app:assembleRelease` |
| 单元测试 | `./gradlew :app:testDebugUnitTest` |
| 装机 | `adb install -r app/build/outputs/apk/release/app-release.apk` |
| 重启输入法 | `sh tools/adb-root.sh 'am force-stop com.bytedance.android.doubaoime'`，随后 `sh tools/adb-root.sh 'ime set com.bytedance.android.doubaoime/.ImeService'` |
| 看模块日志 | `sh tools/adb-root.sh 'ls -t /data/adb/lspd/log/modules_*.log \| head -1'` |

装机的四个坑（`su -c` 引号、force-stop 后必须 `ime set`、重装不重载代码、设备掉线）
见 [`doc/02-调试与验收手册.md`](doc/02-调试与验收手册.md)。

## 验证要求（硬性）

1. 改 `.java` → 单测必须绿。绿了**也不代表手势对**：单测只覆盖纯 JVM 的判定与几何。
2. 改手势 / 触摸行为 → 真机跑一遍，并在 [`ACCEPTANCE.md`](ACCEPTANCE.md) 补一行：
   做了什么、看到什么、证据等级。
3. 声称完成前必须真的执行过；没执行就写"没执行"。
4. 验收判据看**输入框文本**，不看 `ok=true`（`performContextMenuAction` 无条件返回 true，
   见 `doc/06` §2.2）。

## 文档地图

| 位置 | 内容 |
|---|---|
| `doc/01`–`05` | 已关闭问题的定位过程、调试手册、工程红线、防顺从规则、历史修复 |
| `doc/06`–`11` | 豆包 1.5.3 反编译：`doc/06` 是裁决文档（扩展点、技术债、放弃的方向），`doc/07`–`11` 是手势、布局、文本、架构、UX 五份分册 |
| [`doc/12-产品需求.md`](doc/12-产品需求.md) | 目标、已实现功能、功能边界 |
| [`doc/13-架构.md`](doc/13-架构.md) | 技术栈、目录、调用链、数据流 |
| [`doc/14-设计规范.md`](doc/14-设计规范.md) | 设置页与图标的既有设计规范 |
| [`doc/15-待办任务.md`](doc/15-待办任务.md) | 待办、优先级、验收标准 |
| `ACCEPTANCE.md` | 台账：验收线、逐项证据、回退项、开放问题 |

## 硬规则（违反即回退）

- 只用 legacy Xposed API 82；`app/libs/xposed-api-82.jar` 必须 `compileOnly`
- 绝不调用 `KeyboardJni` 的静态入口（没有实例时那样调会打挂输入法进程）
- 输入法是最不能崩的组件：异常一律吞掉，并把触摸事件交回引擎
- 不写空 `catch`；日志里不放密码 / token / 授权码
- 完整清单：[`doc/03-工程红线.md`](doc/03-工程红线.md)

## 真机操作

手册在 [`.claude/skills/adb-device/SKILL.md`](.claude/skills/adb-device/SKILL.md)；
整段设备任务派 `.claude/agents/adb-runner` 子代理（只碰设备、不改代码）。**子代理只有
Claude Code 能派**；Codex / agy 照手册里的命令直接执行，不走子代理。

## 输出风格

结论先行；不复述已有记录；能一句说完不写三段；需要拍板只问一个问题。
