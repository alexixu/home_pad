#!/usr/bin/env python3
"""Build this small native launcher with the local, verified Android tools."""
from pathlib import Path
import argparse
import hashlib
import os
import tempfile
import json
import secrets
import shutil
import subprocess
import zipfile

import prepare_wake_dependencies as wake
from toolchain import resolve_tools

ROOT = Path(__file__).resolve().parent
WORKSPACE = ROOT.parents[1]
JAVA, SDK, BUILD_TOOLS = resolve_tools()
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--check-only', action='store_true',
                    help='build an isolated unsigned APK without changing signing keys or outputs')
arguments = parser.parse_args()
BUILD_ROOT = ROOT / 'build'
BUILD_ROOT.mkdir(exist_ok=True)
BUILD = Path(tempfile.mkdtemp(prefix='voice-wake-check-', dir=BUILD_ROOT)) if arguments.check_only else BUILD_ROOT
WAKE_JAR = wake.CACHE / wake.JAVA_JAR
WAKE_ASSETS = wake.PREPARED / 'assets'
WAKE_NATIVE = wake.PREPARED / 'jniLibs'
required = {WAKE_JAR: wake.DOWNLOADS[wake.JAVA_JAR][1]}
required.update({WAKE_ASSETS / 'wakeword' / name: expected
                 for name, (_, expected) in wake.MODEL_FILES.items()})
required[WAKE_ASSETS / 'wakeword/keywords.txt'] = hashlib.sha256(wake.KEYWORDS.encode()).hexdigest()
required.update({WAKE_NATIVE / 'arm64-v8a' / name: expected
                 for name, expected in wake.NATIVE_FILES.items()})
required.update({WAKE_ASSETS / 'licenses' / name: wake.DOWNLOADS[name][1]
                 for name in ('sherpa-LICENSE', 'onnxruntime-LICENSE', 'onnxruntime-ThirdPartyNotices.txt')})
for dependency, expected in required.items():
    if not dependency.is_file() or wake.digest(dependency) != expected:
        raise SystemExit('Wake-word dependency missing or changed. Run python3 work/minimal-launcher/prepare_wake_dependencies.py first.')
CLASSES = BUILD / 'classes'
if CLASSES.exists():
    shutil.rmtree(CLASSES)
CLASSES.mkdir()
DEX = BUILD / 'dex'
if DEX.exists():
    shutil.rmtree(DEX)
DEX.mkdir()
PACKAGED_ASSETS = BUILD / 'wake-assets'
if PACKAGED_ASSETS.exists():
    shutil.rmtree(PACKAGED_ASSETS)
for dependency in required:
    if dependency.is_relative_to(WAKE_ASSETS):
        destination = PACKAGED_ASSETS / dependency.relative_to(WAKE_ASSETS)
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(dependency, destination)
app_assets = sorted((ROOT / 'app/assets').rglob('*')) if (ROOT / 'app/assets').exists() else []
app_assets = [p for p in app_assets if p.is_file()]
asset_hashes = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in app_assets}
for source in app_assets:
    relative = source.relative_to(ROOT / 'app/assets')
    voice_asset = relative.parts[0] == 'voice' and source.suffix in ('.html', '.js', '.css')
    dashboard_asset = str(relative) in ('dashboard/courtyard.png', 'dashboard/tabler-license.txt',
                                      'dashboard/BodoniModa.ttf', 'dashboard/BodoniModa-OFL.txt')
    if source.is_symlink() or not (voice_asset or dashboard_asset):
        raise SystemExit('Unexpected application asset; review before packaging.')
    destination = PACKAGED_ASSETS / relative
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, destination)
for name, packaged_name in [('LICENSE', 'home_pad-LICENSE'),
                            ('THIRD_PARTY_NOTICES.md', 'home_pad-THIRD_PARTY_NOTICES.md')]:
    destination = PACKAGED_ASSETS / 'licenses' / packaged_name
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(WORKSPACE / name, destination)
OUTPUT = BUILD / 'verified-unsigned.apk' if arguments.check_only else WORKSPACE / 'outputs/极简搜索桌面.apk'

def run(*args):
    subprocess.run([str(a) for a in args], check=True, cwd=ROOT)

sources = sorted((ROOT / 'app/src').rglob('*.java'))
source_hashes = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}
resource_files = sorted(p for p in (ROOT / 'app/res').rglob('*') if p.is_file())
resource_hashes = {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest() for p in resource_files}
manifest_hash = hashlib.sha256((ROOT / 'app/AndroidManifest.xml').read_bytes()).hexdigest()
if not sources:
    raise SystemExit('No application source')
run(JAVA / 'bin/javac', '-encoding', 'UTF-8', '--release', '8',
    '-classpath', os.pathsep.join((str(SDK), str(WAKE_JAR))), '-d', CLASSES, *sources)
class_jar = BUILD / 'app-classes.jar'
with zipfile.ZipFile(class_jar, 'w', zipfile.ZIP_DEFLATED) as archive:
    for p in sorted(CLASSES.rglob('*.class')):
        archive.write(p, p.relative_to(CLASSES))
run(JAVA / 'bin/java', '-cp', BUILD_TOOLS / 'lib/d8.jar',
    'com.android.tools.r8.D8', '--release', '--min-api', '26',
    '--lib', SDK, '--output', DEX, class_jar, WAKE_JAR)
unsigned = BUILD / 'unsigned.apk'
resource_args = []
if (ROOT / 'app/res').exists():
    resources = BUILD / 'resources.zip'
    run(BUILD_TOOLS / 'aapt2', 'compile', '--dir', ROOT / 'app/res', '-o', resources)
    resource_args = [resources]
run(BUILD_TOOLS / 'aapt2', 'link', '-o', unsigned, '-I', SDK,
    '--manifest', ROOT / 'app/AndroidManifest.xml', '-A', PACKAGED_ASSETS, '-0', 'onnx', *resource_args)
with zipfile.ZipFile(unsigned, 'a', zipfile.ZIP_DEFLATED) as archive:
    for p in sorted(DEX.glob('*.dex')):
        archive.write(p, p.name)
    for name in sorted(wake.NATIVE_FILES):
        p = WAKE_NATIVE / 'arm64-v8a' / name
        archive.write(p, 'lib/' + str(p.relative_to(WAKE_NATIVE)))
aligned = BUILD / 'aligned.apk'
run(BUILD_TOOLS / 'zipalign', '-f', '4', unsigned, aligned)

if arguments.check_only:
    shutil.copyfile(aligned, OUTPUT)
else:
    signing = ROOT / 'signing'
    signing.mkdir(mode=0o700, exist_ok=True)
    password = signing / 'password.txt'
    keystore = signing / 'launcher.p12'
    if password.exists() != keystore.exists():
        raise SystemExit('Incomplete signing identity; restore the matching key and password before building.')
    if not password.exists():
        password.write_text(secrets.token_urlsafe(36))
        password.chmod(0o600)
    if not keystore.exists():
        run(JAVA / 'bin/keytool', '-genkeypair', '-keystore', keystore,
            '-storetype', 'PKCS12', '-storepass:file', password,
            '-alias', 'minimalhome', '-keyalg', 'RSA', '-keysize', '3072',
            '-validity', '10000', '-dname', 'CN=Alex Personal Launcher')
        keystore.chmod(0o600)
    run(JAVA / 'bin/java', '-jar', BUILD_TOOLS / 'lib/apksigner.jar', 'sign',
        '--ks', keystore, '--ks-pass', 'file:' + str(password),
        '--ks-key-alias', 'minimalhome', '--out', OUTPUT, aligned)
    run(JAVA / 'bin/java', '-jar', BUILD_TOOLS / 'lib/apksigner.jar',
        'verify', '--verbose', '--print-certs', OUTPUT)
manifest = subprocess.check_output([str(BUILD_TOOLS / 'aapt2'), 'dump',
                                    'badging', str(OUTPUT)], text=True)
(BUILD / 'apk-metadata.txt').write_text(manifest)
report = {'apk': str(OUTPUT), 'bytes': OUTPUT.stat().st_size,
          'sha256': hashlib.sha256(OUTPUT.read_bytes()).hexdigest(),
          'sources': source_hashes, 'assets': asset_hashes, 'resources': resource_hashes, 'manifest_sha256': manifest_hash,
          'source_unchanged': all(hashlib.sha256(p.read_bytes()).hexdigest() == source_hashes[str(p.relative_to(ROOT))] for p in sources)
              and all(hashlib.sha256(p.read_bytes()).hexdigest() == asset_hashes[str(p.relative_to(ROOT))] for p in app_assets)
              and all(hashlib.sha256(p.read_bytes()).hexdigest() == resource_hashes[str(p.relative_to(ROOT))] for p in resource_files)
              and hashlib.sha256((ROOT / 'app/AndroidManifest.xml').read_bytes()).hexdigest() == manifest_hash,
          'signature_verified': not arguments.check_only, 'installed': False,
          'check_only': arguments.check_only,
          'wake_dependencies': {str(p.relative_to(ROOT)): expected for p, expected in required.items()},
          'permissions': [line for line in manifest.splitlines() if line.startswith('uses-permission:')]}
(BUILD / 'build-report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2))
if not report['source_unchanged']:
    raise SystemExit('Source changed during build; repeat validation before publication.')
print(json.dumps(report, ensure_ascii=False, indent=2))
