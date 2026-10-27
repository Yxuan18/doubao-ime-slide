#!/bin/sh
# 等设备回来再执行一条 root shell 命令。
#
# 为什么需要它：USB 连接不稳时 `adb devices` 会突然空掉，紧接着的 adb 命令报
# "no devices/emulators found"。这台机器上这个现象一次调试里能碰上三五次，
# 每次都得重试。
#
# 用法：sh tools/adb-root.sh 'am force-stop com.bytedance.android.doubaoime'
#      （参数整体是**一条**命令；引号在调用方那层加好）
#
# 等待上限 30 秒；等不到就照常执行，让 adb 自己报错——不吞错误。
i=0
while [ $i -lt 15 ]; do
  adb devices 2>/dev/null | grep -q 'device$' && break
  sleep 2
  i=$((i + 1))
done
exec adb shell su -c "$1"
