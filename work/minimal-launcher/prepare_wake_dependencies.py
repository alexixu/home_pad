#!/usr/bin/env python3
"""Fetch pinned official offline wake-word dependencies; never contacts the tablet.

The Java API is usable without Kotlin/Gradle. Only the Pad's arm64-v8a JNI files
and selected Chinese mobile-model files are prepared for APK packaging. Downloads
and generated binary assets stay under ignored tools/wake-deps/.
"""
from pathlib import Path
import argparse
import hashlib
import json
import shutil
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parent
CACHE = ROOT / 'tools/wake-deps'
PREPARED = CACHE / 'prepared'
RELEASE = 'https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/'
MODEL = 'sherpa-onnx-kws-zipformer-wenetspeech-3.3M-2024-01-01-mobile'
MODEL_ARCHIVE = MODEL + '.tar.bz2'
ANDROID_ARCHIVE = 'sherpa-onnx-v2.0.1-android.tar.bz2'
JAVA_JAR = 'sherpa-onnx-jvm-2.0.1.jar'
HOST_JAR = 'sherpa-onnx-native-lib-osx-aarch64-2.0.1.jar'
DOWNLOADS = {
    ANDROID_ARCHIVE: (RELEASE + ANDROID_ARCHIVE,
                     '39003753ec293425ed56723ea9b972b98841a490af3311670c3060548d654330'),
    MODEL_ARCHIVE: ('https://github.com/k2-fsa/sherpa-onnx/releases/download/kws-models/' + MODEL_ARCHIVE,
                    'b812a043aef628a6915f89cb9a94e55f8e87e89ff904b516f822d7e0a3e6de2b'),
    JAVA_JAR: (RELEASE + JAVA_JAR,
               '8320e2457b6e38dd624fb619d3552e11ef78ffeb394622056126b16f2fd99d12'),
    'sherpa-LICENSE': ('https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/v2.0.1/LICENSE',
                       'cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30'),
    'onnxruntime-LICENSE': ('https://raw.githubusercontent.com/microsoft/onnxruntime/v1.28.2/LICENSE',
                            '2f07c72751aed99790b8a4869cf2311df85a860b22ded05fa22803587a48922c'),
    'onnxruntime-ThirdPartyNotices.txt': (
        'https://raw.githubusercontent.com/microsoft/onnxruntime/v1.28.2/ThirdPartyNotices.txt',
        '0e07b95f3a8d6230037707c5c4a2b554d12c4cb67369669ac255635528ffcee2'),
}
MODEL_FILES = {
    'encoder.int8.onnx': ('encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx',
                          '4ded45b30961270f2a9a64bede4569b324c36d4f3a772c1b35bb70a244150675'),
    'decoder.onnx': ('decoder-epoch-12-avg-2-chunk-16-left-64.onnx',
                     'fb581d6734511676e246e0dff2fea01b31b0913176cb3ca64576dbab0a177774'),
    'joiner.int8.onnx': ('joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx',
                        'f79760052b87239e325f0567c752ad3130b30d92effb847d4307743c20c59a24'),
    'tokens.txt': ('tokens.txt',
                   '72316508d9119696145abc6f1f8cdc46287535c34e5ce7e595f845cb1499cf2e'),
    'MODEL_README.md': ('README.md',
                        'dd0ec68b5d9ee736f016fc982078bd3cdbccc69057e9910bdd45580992d13367'),
}
NATIVE_FILES = {
    'libsherpa-onnx-jni.so': '5db8f89f12b2c900a98c9e01e00c475c59db35e9437a62e36e27839ec48b5575',
    'libonnxruntime.so': '33847ad43bffe204699fd4a27f7f3603452a8cdaf2f9a44983a0bc31ffcf2da1',
}
KEYWORDS = 'n ǐ h ǎo x iǎo ài @你好小爱\n'


def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            result.update(block)
    return result.hexdigest()


def fetch(name, url, expected):
    path = CACHE / name
    if path.is_file() and digest(path) == expected:
        return path
    part = path.with_suffix(path.suffix + '.part')
    try:
        request = urllib.request.Request(url, headers={'User-Agent': 'home-pad-build/1'})
        with urllib.request.urlopen(request, timeout=60) as response, part.open('wb') as target:
            shutil.copyfileobj(response, target)
        if digest(part) != expected:
            raise RuntimeError('Dependency checksum mismatch: ' + name)
        part.replace(path)
    finally:
        part.unlink(missing_ok=True)
    return path


def extract_exact(archive, member_name, output, expected):
    if output.is_file() and digest(output) == expected:
        return
    # Never extract archive paths/symlinks: only stream a pinned member to our own path.
    member = archive.getmember(member_name)
    if not member.isfile():
        raise RuntimeError('Expected regular archive member: ' + member_name)
    output.parent.mkdir(parents=True, exist_ok=True)
    part = output.with_suffix(output.suffix + '.part')
    try:
        with archive.extractfile(member) as source, part.open('wb') as target:
            shutil.copyfileobj(source, target)
        if digest(part) != expected:
            raise RuntimeError('Extracted dependency checksum mismatch: ' + output.name)
        part.replace(output)
    finally:
        part.unlink(missing_ok=True)


def prepare(include_host=False):
    CACHE.mkdir(parents=True, exist_ok=True)
    for name, (url, expected) in DOWNLOADS.items():
        fetch(name, url, expected)
    if include_host:
        fetch(HOST_JAR, RELEASE + HOST_JAR,
              '718a4ea4799341146ef9a0cc15daa70e6622ae4a16911952604035f396703499')
    asset_dir = PREPARED / 'assets/wakeword'
    native_dir = PREPARED / 'jniLibs/arm64-v8a'
    with tarfile.open(CACHE / MODEL_ARCHIVE) as archive:
        for name, (member, expected) in MODEL_FILES.items():
            extract_exact(archive, MODEL + '/' + member, asset_dir / name, expected)
    with tarfile.open(CACHE / ANDROID_ARCHIVE) as archive:
        for name, expected in NATIVE_FILES.items():
            extract_exact(archive, './jniLibs/arm64-v8a/' + name, native_dir / name, expected)
    (asset_dir / 'keywords.txt').write_text(KEYWORDS, encoding='utf-8')
    licenses = PREPARED / 'assets/licenses'
    licenses.mkdir(parents=True, exist_ok=True)
    for name in ('sherpa-LICENSE', 'onnxruntime-LICENSE', 'onnxruntime-ThirdPartyNotices.txt'):
        shutil.copyfile(CACHE / name, licenses / name)
    report = {
        'engine': 'sherpa-onnx 2.0.1', 'model': MODEL, 'abi': 'arm64-v8a',
        'keyword': '你好小爱', 'keyword_tokens': KEYWORDS.strip(),
        'java_jar': str(CACHE / JAVA_JAR), 'assets': str(PREPARED / 'assets'),
        'jni_libs': str(PREPARED / 'jniLibs'),
        'sources': {name: {'url': url, 'sha256': expected}
                    for name, (url, expected) in DOWNLOADS.items()},
        'prepared': {str(path.relative_to(PREPARED)): {
            'bytes': path.stat().st_size, 'sha256': digest(path)}
            for path in sorted(PREPARED.rglob('*')) if path.is_file() and path.name != 'manifest.json'},
    }
    (PREPARED / 'manifest.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host-check-deps', action='store_true',
                        help='also prepare the macOS arm64 JNI jar for offline audio fixture checks')
    arguments = parser.parse_args()
    result = prepare(arguments.host_check_deps)
    print(json.dumps({key: result[key] for key in ('engine', 'model', 'abi', 'java_jar', 'assets', 'jni_libs')},
                     ensure_ascii=False, indent=2))
