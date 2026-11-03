#!/bin/zsh
set -eu
TASK_ROOT="$(cd -- "$(dirname -- "$0")/.." && pwd)"
export ADB="$TASK_ROOT/work/android-tools/platform-tools/adb"
SCRCPY="$TASK_ROOT/work/android-tools/scrcpy-macos-aarch64-v5.0.1/scrcpy"
if [[ ! -x "$ADB" || ! -x "$SCRCPY" ]]; then
  print "调试工具不存在，请保留本聊天目录中的 work/android-tools。"
  read -r "?按回车退出。"
  exit 1
fi
if ! "$ADB" -s 834b3988 get-state >/dev/null 2>&1; then
  print "请连接小米平板 5 Pro，开启 USB 调试并允许这台 Mac。"
  read -r "?按回车退出。"
  exit 1
fi
if /usr/bin/pgrep -f '[/]scrcpy-macos-aarch64-v5[.]0[.]1/scrcpy -s 834b3988 ' >/dev/null; then
  print "平板控制窗口已经运行；请使用现有窗口。"
  exit 0
fi
exec /usr/bin/caffeinate -i "$SCRCPY" -s 834b3988 --no-audio --force-adb-forward --turn-screen-off --stay-awake --max-size=1280 --max-fps=20 --video-bit-rate=2M --window-title="小米平板 5 Pro · 熄屏控制" --window-width=650 --window-height=1000
