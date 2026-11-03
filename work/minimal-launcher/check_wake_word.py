#!/usr/bin/env python3
"""Offline host JNI/model smoke check using synthetic Mandarin files, never a mic.

Requires macOS arm64 and the installed Tingting Mandarin voice. This tests actual
model inference and keyword tokenization, not tablet microphone/accuracy/battery.
Audio files are written only to the ignored build directory and never played.
"""
from pathlib import Path
import json
import platform
import subprocess
import wave

from prepare_wake_dependencies import ROOT, CACHE, HOST_JAR, prepare

JAVA_SOURCE = r'''
import com.k2fsa.sherpa.onnx.*;
import java.nio.file.*;
import java.util.*;
public class WakeFixtureCheck {
  public static void main(String[] args) {
    Path dir = Paths.get(args[0]);
    KeywordSpotterConfig config = KeywordSpotterConfig.builder()
      .setFeatureConfig(FeatureConfig.builder().setSampleRate(16000).setFeatureDim(80).setDither(0).build())
      .setOnlineModelConfig(OnlineModelConfig.builder().setTransducer(OnlineTransducerModelConfig.builder()
        .setEncoder(dir.resolve("encoder.int8.onnx").toString())
        .setDecoder(dir.resolve("decoder.onnx").toString())
        .setJoiner(dir.resolve("joiner.int8.onnx").toString()).build())
        .setTokens(dir.resolve("tokens.txt").toString()).setNumThreads(1).setProvider("cpu").build())
      .setKeywordsFile(dir.resolve("keywords.txt").toString())
      .setKeywordsScore(1.5f).setKeywordsThreshold(0.35f).setNumTrailingBlanks(2).setMaxActivePaths(4).build();
    KeywordSpotter spotter = new KeywordSpotter(config);
    try {
      for (int index = 1; index < args.length; ++index) {
        WaveReader wav = new WaveReader(args[index]);
        if (wav.getSampleRate() != 16000) throw new AssertionError("Expected 16 kHz fixture");
        OnlineStream stream = spotter.createStream();
        // End-of-file silence lets the streaming recognizer finish a keyword.
        float[] samples = Arrays.copyOf(wav.getSamples(), wav.getSamples().length + 16000);
        List<String> matches = new ArrayList<>();
        try {
          for (int offset = 0; offset < samples.length; offset += 1600) {
            stream.acceptWaveform(Arrays.copyOfRange(samples, offset,
                    Math.min(offset + 1600, samples.length)), 16000);
            while (spotter.isReady(stream)) {
              spotter.decode(stream);
              String match = spotter.getResult(stream).getKeyword();
              if (!match.isEmpty()) { matches.add(match); spotter.reset(stream); }
            }
          }
        } finally { stream.release(); }
        System.out.println(Paths.get(args[index]).getFileName() + "\t" + String.join(",", matches));
      }
    } finally { spotter.release(); }
  }
}
'''


def main():
    if platform.system() != 'Darwin' or platform.machine() != 'arm64':
        raise SystemExit('This host-only fixture check requires macOS arm64.')
    deps = prepare(include_host=True)
    build = ROOT / 'build/wake-check'
    build.mkdir(parents=True, exist_ok=True)
    cases = [
        ('positive_paused', '你好，小爱', '你好小爱'),
        ('positive_unpaused', '你好小爱', '你好小爱'),
        ('negative_ai', '小爱', ''),
        ('negative_hello', '你好', ''),
        ('negative_name', '你好，小明', ''),
        ('negative_weather', '今天天气怎么样', ''),
        ('silence', None, ''),
    ]
    fixtures = []
    for name, phrase, expected in cases:
        audio = build / (name + '.wav')
        if phrase is None:
            with wave.open(str(audio), 'wb') as output:
                output.setparams((1, 2, 16000, 0, 'NONE', 'not compressed'))
                output.writeframes(bytes(16000 * 2 * 4))
        else:
            aiff = build / (name + '.aiff')
            subprocess.run(['say', '-v', 'Tingting (Chinese (China mainland))', '-o', aiff, phrase], check=True)
            subprocess.run(['afconvert', '-f', 'WAVE', '-d', 'LEI16@16000', aiff, audio], check=True)
        fixtures.append(audio)
    source = build / 'WakeFixtureCheck.java'
    source.write_text(JAVA_SOURCE, encoding='utf-8')
    java = next((ROOT / 'tools/jdk').glob('*/Contents/Home/bin'))
    classpath = ':'.join([deps['java_jar'], str(CACHE / HOST_JAR), str(build)])
    subprocess.run([java / 'javac', '--release', '8', '-encoding', 'UTF-8',
                    '-cp', classpath, source], check=True)
    inference = subprocess.run([java / 'java', '-cp', classpath, 'WakeFixtureCheck',
                                str(Path(deps['assets']) / 'wakeword'), *fixtures],
                               capture_output=True, text=True, check=True)
    (build / 'host-inference.log').write_text(inference.stdout + inference.stderr, encoding='utf-8')
    results = dict(line.split('\t', 1) for line in inference.stdout.splitlines() if '\t' in line)
    report = {'host_only': True, 'synthetic_audio': True, 'audio_played': False,
              'microphone_used': False, 'tablet_contacted': False,
              'tablet_wake_accuracy_verified': False, 'cases': []}
    for name, phrase, expected in cases:
        actual = results.get(name + '.wav')
        report['cases'].append({'phrase': phrase or '[silence]', 'expected': expected,
                                'actual': actual, 'passed': actual == expected})
    report['passed'] = all(case['passed'] for case in report['cases'])
    encoded = json.dumps(report, ensure_ascii=False, indent=2)
    (build / 'host-fixture-report.json').write_text(encoded + '\n', encoding='utf-8')
    print(encoded)
    if not report['passed']:
        raise SystemExit('Offline keyword fixture check failed.')


if __name__ == '__main__':
    main()
