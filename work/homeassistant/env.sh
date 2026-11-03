if [ -n "${BASH_VERSION:-}" ]; then
  HOME_PAD_ENV_FILE="${BASH_SOURCE[0]}"
elif [ -n "${ZSH_VERSION:-}" ]; then
  HOME_PAD_ENV_FILE="${(%):-%x}"
else
  printf '%s\n' 'Source this file from Bash or Zsh.' >&2
  return 1
fi

HOME_PAD_ENV_ROOT="$(cd -P -- "$(dirname -- "$HOME_PAD_ENV_FILE")" && cd -P ../.. && pwd -P)"
export COLIMA_HOME="$HOME_PAD_ENV_ROOT/work/ha-vm"
export COLIMA_CACHE_HOME="$HOME_PAD_ENV_ROOT/work/ha-cache"
export DOCKER_CONFIG="$HOME_PAD_ENV_ROOT/work/ha-client"
export DOCKER_HOST="unix://$COLIMA_HOME/ha/docker.sock"
unset HOME_PAD_ENV_FILE HOME_PAD_ENV_ROOT
