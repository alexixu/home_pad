package com.alex.minimalhome;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Foreground-only local keyword spotting; audio is neither saved nor sent to a server. */
public final class WakeWordControls {
    private static final int SAMPLE_RATE = 16000;
    private static final String KEYWORD = "你好小爱";
    private static final String[][] MODEL_FILES = {
        {"encoder.int8.onnx", "4ded45b30961270f2a9a64bede4569b324c36d4f3a772c1b35bb70a244150675"},
        {"decoder.onnx", "fb581d6734511676e246e0dff2fea01b31b0913176cb3ca64576dbab0a177774"},
        {"joiner.int8.onnx", "f79760052b87239e325f0567c752ad3130b30d92effb847d4307743c20c59a24"},
        {"tokens.txt", "72316508d9119696145abc6f1f8cdc46287535c34e5ce7e595f845cb1499cf2e"},
        {"keywords.txt", "9293cb326cfd3b9359da1c8af96615d65e650be55270de1ddbd6004e49472e94"}
    };
    private final Activity activity;
    private final Runnable onWake;
    private final Consumer<String> onStatus;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "local-wake-word");
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    // UI state is main-thread-only; the native model belongs only to worker.
    private int generation;
    private boolean destroyed;
    private Run current;
    private KeywordSpotter spotter;
    private volatile boolean releaseFailed;

    public WakeWordControls(Activity activity, Runnable onWake, Consumer<String> onStatus) {
        this.activity = activity;
        this.onWake = onWake;
        this.onStatus = onStatus;
    }

    /** Caller owns homepage visibility and permission requests. Never starts in the background. */
    public void start() {
        requireMainThread();
        if (destroyed || current != null || activity.isFinishing()) return;
        if (releaseFailed) {
            onStatus.accept("麦克风未能释放，请重新打开桌面后重试");
            return;
        }
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            onStatus.accept("点按语音图标，允许麦克风后开启唤醒");
            return;
        }
        Run run = new Run(++generation);
        current = run;
        onStatus.accept("正在准备本地唤醒…");
        worker.execute(() -> listen(run));
    }

    public void stop() {
        requireMainThread();
        ++generation;
        Run old = current;
        current = null;
        if (old != null) old.cancel();
    }

    /** Serial worker barrier: ASR may acquire the microphone only after our release finishes. */
    public void stopThen(Consumer<Boolean> callback) {
        requireMainThread();
        stop();
        if (destroyed) return;
        int stoppedGeneration = generation;
        worker.execute(() -> main.post(() -> {
            if (!destroyed && generation == stoppedGeneration) callback.accept(!releaseFailed);
        }));
    }

    public void destroy() {
        requireMainThread();
        if (destroyed) return;
        destroyed = true;
        stop();
        worker.execute(() -> {
            if (spotter != null) {
                spotter.release();
                spotter = null;
            }
        });
        worker.shutdown();
    }

    private void listen(Run run) {
        OnlineStream stream = null;
        boolean detected = false;
        String failure = null;
        try {
            if (run.cancelled) return;
            if (spotter == null) {
                File directory = prepareModel();
                if (run.cancelled) return;
                spotter = new KeywordSpotter(KeywordSpotterConfig.builder()
                        .setFeatureConfig(FeatureConfig.builder().setSampleRate(SAMPLE_RATE)
                                .setFeatureDim(80).setDither(0f).build())
                        .setOnlineModelConfig(OnlineModelConfig.builder()
                                .setTransducer(OnlineTransducerModelConfig.builder()
                                        .setEncoder(new File(directory, "encoder.int8.onnx").getPath())
                                        .setDecoder(new File(directory, "decoder.onnx").getPath())
                                        .setJoiner(new File(directory, "joiner.int8.onnx").getPath()).build())
                                .setTokens(new File(directory, "tokens.txt").getPath())
                                .setNumThreads(1).setProvider("cpu").setDebug(false).build())
                        .setKeywordsFile(new File(directory, "keywords.txt").getPath())
                        .setKeywordsScore(1.5f).setKeywordsThreshold(0.35f)
                        .setNumTrailingBlanks(2).setMaxActivePaths(4).build());
            }
            if (run.cancelled) return;
            stream = spotter.createStream();
            int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new IllegalStateException("Unsupported microphone format");
            AudioRecord recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minimum * 2, SAMPLE_RATE / 2));
            if (!run.begin(recorder)) return;
            publish(run, "说「你好，小爱」唤醒");
            short[] pcm = new short[1600];
            while (!run.cancelled) {
                int count = recorder.read(pcm, 0, pcm.length, AudioRecord.READ_BLOCKING);
                if (run.cancelled) break;
                if (count <= 0) throw new IllegalStateException("Microphone read failed");
                float[] samples = new float[count];
                for (int i = 0; i < count; ++i) samples[i] = pcm[i] / 32768.0f;
                stream.acceptWaveform(samples, SAMPLE_RATE);
                while (!run.cancelled && spotter.isReady(stream)) {
                    spotter.decode(stream);
                    if (KEYWORD.equals(spotter.getResult(stream).getKeyword())) {
                        spotter.reset(stream);
                        detected = true;
                        break;
                    }
                }
                if (detected) break;
            }
        } catch (IOException e) {
            failure = "本地唤醒模型不可用，仍可点按语音";
        } catch (SecurityException e) {
            failure = "麦克风未授权，点按语音图标重试";
        } catch (RuntimeException | LinkageError e) {
            failure = "本地唤醒暂不可用，仍可点按语音";
        } finally {
            if (!run.releaseAudio()) releaseFailed = true;
            try {
                if (stream != null) stream.release();
            } catch (RuntimeException | LinkageError ignored) {
                releaseFailed = true;
            }
            final boolean wake = detected && !releaseFailed;
            final String error = releaseFailed ? "麦克风未能释放，请重新打开桌面后重试" : failure;
            main.post(() -> {
                if (!isCurrent(run)) return;
                current = null;
                if (wake) onWake.run();
                else if (error != null) onStatus.accept(error);
            });
        }
    }

    private void publish(Run run, String value) {
        main.post(() -> {
            if (isCurrent(run)) onStatus.accept(value);
        });
    }

    private boolean isCurrent(Run run) {
        return !destroyed && !run.cancelled && generation == run.generation && current == run;
    }

    private File prepareModel() throws IOException {
        File directory = new File(activity.getFilesDir(), "wake-model-v1");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Model directory");
        byte[] buffer = new byte[16384];
        for (String[] entry : MODEL_FILES) {
            File output = new File(directory, entry[0]);
            if (output.isFile() && entry[1].equals(sha256(output))) continue;
            File temporary = new File(directory, entry[0] + ".tmp");
            try {
                try (InputStream input = activity.getAssets().open("wakeword/" + entry[0]);
                        FileOutputStream target = new FileOutputStream(temporary)) {
                    int count;
                    while ((count = input.read(buffer)) != -1) target.write(buffer, 0, count);
                    target.getFD().sync();
                }
                if (!entry[1].equals(sha256(temporary))) throw new IOException("Model checksum");
                if (!temporary.renameTo(output)) throw new IOException("Model replace");
            } finally {
                if (temporary.exists()) temporary.delete();
            }
        }
        return directory;
    }

    private static String sha256(File file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new FileInputStream(file)) {
                byte[] buffer = new byte[16384];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest.digest()) hex.append(String.format(java.util.Locale.ROOT,
                    "%02x", value & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }

    private static void requireMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper())
            throw new IllegalStateException("WakeWordControls requires the main thread");
    }

    private static final class Run {
        final int generation;
        volatile boolean cancelled;
        private AudioRecord recorder;

        Run(int generation) { this.generation = generation; }

        synchronized boolean begin(AudioRecord value) {
            recorder = value;
            if (cancelled) return false;
            if (value.getState() != AudioRecord.STATE_INITIALIZED)
                throw new IllegalStateException("Microphone initialization failed");
            value.startRecording();
            if (value.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING)
                throw new IllegalStateException("Microphone start failed");
            return true;
        }

        synchronized void cancel() {
            cancelled = true;
            stopRecording();
        }

        synchronized boolean releaseAudio() {
            AudioRecord previous = recorder;
            recorder = null;
            if (previous == null) return true;
            try {
                if (previous.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING)
                    previous.stop();
            } catch (RuntimeException | LinkageError ignored) {
                // Still attempt release if stop failed.
            }
            try {
                previous.release();
                return true;
            } catch (RuntimeException | LinkageError ignored) {
                return false;
            }
        }

        private void stopRecording() {
            if (recorder != null) {
                try {
                    if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING)
                        recorder.stop();
                } catch (RuntimeException | LinkageError ignored) {
                    // A stopped/invalidated microphone is released by the worker's finally block.
                }
            }
        }
    }
}
