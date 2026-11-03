"""Resolve the project's unpacked tools or an existing standard Android SDK."""
import os
from pathlib import Path
import subprocess


ROOT = Path(__file__).resolve().parent


def resolve_tools():
    java_candidates = []
    for variable in ('HOME_PAD_JAVA_HOME', 'JAVA_HOME'):
        if os.environ.get(variable):
            java_candidates.append(Path(os.environ[variable]).expanduser())
    java_candidates.extend(sorted((ROOT / 'tools/jdk').glob('*/Contents/Home')))
    java_candidates.extend(sorted((ROOT / 'tools/jdk').glob('*')))
    if Path('/usr/libexec/java_home').is_file():
        result = subprocess.run(['/usr/libexec/java_home', '-v', '17'], capture_output=True, text=True)
        if result.returncode == 0:
            java_candidates.append(Path(result.stdout.strip()))
    java = next((path for path in java_candidates
                 if (path / 'bin/javac').is_file() and (path / 'bin/java').is_file()), None)
    sdk_candidates = [Path(os.environ[key]).expanduser()
                      for key in ('ANDROID_HOME', 'ANDROID_SDK_ROOT') if os.environ.get(key)]
    sdk_candidates.extend([Path.home() / 'Library/Android/sdk', Path.home() / 'Android/Sdk'])
    platforms = sorted((ROOT / 'tools/platform').glob('*/android.jar'))
    build_tools = sorted((ROOT / 'tools/build-tools').glob('*/aapt2'))
    for sdk_root in sdk_candidates:
        platforms.append(sdk_root / 'platforms/android-33/android.jar')
        build_tools.append(sdk_root / 'build-tools/35.0.0/aapt2')
    platform = next((path for path in platforms if path.is_file()), None)
    tools = next((path.parent for path in build_tools if path.is_file()
                  and (path.parent / 'zipalign').is_file()
                  and (path.parent / 'lib/apksigner.jar').is_file()
                  and (path.parent / 'lib/d8.jar').is_file()), None)
    if java is None or platform is None or tools is None:
        raise RuntimeError('Prepare JDK 17, Android SDK platform 33 and Build Tools 35.0.0; '
                           'set JAVA_HOME and ANDROID_HOME or use the project-local tools.')
    return java, platform, tools
