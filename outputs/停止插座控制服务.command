#!/bin/zsh
set -eu
TASK_ROOT="$(cd -- "$(dirname -- "$0")/.." && pwd)"
source "$TASK_ROOT/work/homeassistant/env.sh"
HOME_PAD_COLIMA="${HOME_PAD_COLIMA:-$(command -v colima || true)}"
HOME_PAD_DOCKER="${HOME_PAD_DOCKER:-$(command -v docker || true)}"
if [[ -z "$HOME_PAD_COLIMA" || -z "$HOME_PAD_DOCKER" ]]; then
  print "请将 Colima 与 Docker 加入 PATH，或通过 HOME_PAD_COLIMA、HOME_PAD_DOCKER 指定工具位置。"
  exit 1
fi
if "$HOME_PAD_COLIMA" status ha >/dev/null 2>&1; then
  "$HOME_PAD_DOCKER" stop tablet-homeassistant
  "$HOME_PAD_COLIMA" stop ha
fi
print "旧电脑端 Home Assistant 已停止，备份配置仍保存在本任务目录。"
print "平板上的本地服务不受影响；没有发送任何插座开关指令。"
