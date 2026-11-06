#!/usr/bin/env python3
"""Build shell runtime and run pure policy checks; no installation or device action."""
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
BASE = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT / 'work/minimal-launcher'))
from toolchain import resolve_tools


def main():
    java, sdk, tools = resolve_tools()
    output = BASE / 'build'
    classes, dex, test = output / 'classes', output / 'dex', output / 'test'
    for path in (classes, dex, test):
        path.mkdir(parents=True, exist_ok=True)
    environment = dict(os.environ, JAVA_HOME=str(java))
    commands = [
        [str(java/'bin/javac'), '-encoding', 'UTF-8', '-d', str(test), str(BASE/'BridgePolicy.java'), str(BASE/'BridgePolicyTest.java')],
        [str(java/'bin/java'), '-cp', str(test), 'BridgePolicyTest'],
        [str(java/'bin/javac'), '-encoding', 'UTF-8', '-source', '8', '-target', '8', '-cp', str(sdk), '-d', str(classes),
         *[str(BASE/name) for name in ('BridgePolicy.java','AndroidCurtainUi.java','TmallCurtainBridge.java')]],
        [str(java/'bin/jar'), 'cf', str(output/'bridge.jar'), '-C', str(classes), '.'],
        [str(tools/'d8'), '--lib', str(sdk), '--output', str(dex), str(output/'bridge.jar')],
    ]
    for command in commands:
        result = subprocess.run(command, env=environment, capture_output=True, text=True, timeout=55)
        if result.returncode:
            print(result.stderr.replace(str(ROOT), '[checkout]')[:6000])
            return 1
        if result.stdout:
            print(result.stdout.strip())
    print('tmall_bridge_built; no installation or device action')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
