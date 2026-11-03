#!/bin/zsh
set -eu
TASK_ROOT="$(cd -- "$(dirname -- "$0")/.." && pwd)"
TABLET_ADB="$TASK_ROOT/work/android-tools/platform-tools/adb"
"$TABLET_ADB" -s 834b3988 shell cmd package set-home-activity --user 0 com.miui.home/.launcher.Launcher
"$TABLET_ADB" -s 834b3988 shell input keyevent KEYCODE_HOME
"$TABLET_ADB" -s 834b3988 shell settings put global force_fsg_nav_bar 1
print "已切回小米桌面并恢复原来的手势导航，原桌面布局仍保留。"
