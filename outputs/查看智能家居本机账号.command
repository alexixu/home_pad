#!/bin/zsh
set -eu
TASK_ROOT="$(cd -- "$(dirname -- "$0")/.." && pwd)"
python3 "$TASK_ROOT/work/homeassistant/show_credentials.py"
