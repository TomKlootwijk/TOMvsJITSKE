package nl.privaterecorder;

import android.annotation.SuppressLint;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioRecordingConfiguration;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.os.Process;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** One capture/encoder session; segment rotation never restarts the microphone. */
final class AudioCapture {
    static final class RetryableCaptureException extends IOException {
        RetryableCaptureException(String message) { super(message); }
    }
    interface Listener {
        void state(String text);
        void progress();
        void segmentSaved();
    }

    static final int SAMPLE_RATE = 48000;
    static final int BIT_RATE = 64000;
    static final long SEGMENT_US = 300_000_000L;
    private static final long FRAME_US = 1024_000_000L / SAMPLE_RATE;
    private final EvidenceStore store;
    private final Listener listener;
    private final AtomicBoolean stopping = new AtomicBoolean();
    private AudioRecord audio;
    private MediaCodec codec;
    private boolean codecStarted;
    private File partial;
    private FileOutputStream output;
    private final String sessionId = UUID.randomUUID().toString();
    private long epochOrigin;
    private long elapsedOrigin;
    private long segmentStartUs;
    private long segmentEndUs;
    private long lastOutputAt;
    private int segmentIndex;
    private long segmentFrames;

    AudioCapture(EvidenceStore store, Listener listener) {
        this.store = store;
        this.listener = listener;
    }

    void requestStop() { stopping.set(true); }

    @SuppressLint("MissingPermission") // Activity requests RECORD_AUDIO before starting the service.
    void run() throws Exception {
        boolean clean = false;
        Exception failure = null;
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            store.recoverInterruptedSegments();
            ensureStorage();
            if (stopping.get()) return;
            int min = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) throw new IOException("48 kHz microphone capture is unavailable");
            audio = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(Math.max(min * 4, SAMPLE_RATE * 2 * 10))
                    .build();
            if (audio.getState() != AudioRecord.STATE_INITIALIZED) {
                throw new IOException("Microphone could not be initialized");
            }
            MediaFormat format = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, 1);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096);
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            codecStarted = true;
            store.event("session_start", "session=" + sessionId + "; source=MIC; "
                    + "AAC-LC ADTS 48000 Hz mono 64000 bit/s; device="
                    + android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                    + "; Android=" + android.os.Build.VERSION.RELEASE
                    + "; timestamps are device-clock observations, not certified time");
            epochOrigin = System.currentTimeMillis();
            elapsedOrigin = SystemClock.elapsedRealtime();
            audio.startRecording();
            if (audio.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IOException("Microphone did not enter recording state");
            }
            listener.state("Recording");
            short[] pcm = new short[1024];
            long samples = 0;
            long lastRead = SystemClock.elapsedRealtime();
            lastOutputAt = lastRead;
            long checkedAt = 0;
            long flushedAt = lastRead;
            long clockOffset = epochOrigin - elapsedOrigin;
            Boolean previousSilenced = null;
            int previousDevice = -1;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int heldInput = -1;
            while (!stopping.get()) {
                long now = SystemClock.elapsedRealtime();
                if (heldInput < 0) heldInput = codec.dequeueInputBuffer(0);
                if (heldInput >= 0) {
                    int count = audio.read(pcm, 0, pcm.length, AudioRecord.READ_NON_BLOCKING);
                    if (count == AudioRecord.ERROR_DEAD_OBJECT) {
                        throw new RetryableCaptureException("Android audio device was disconnected");
                    }
                    if (count < 0) throw new IOException("Microphone read failed: " + count);
                    if (count > 0) {
                        if (now - lastRead > 2000) store.event("capture_delay",
                                "No PCM read for " + (now - lastRead)
                                        + " ms; possible missing audio; session=" + sessionId);
                        ByteBuffer input = codec.getInputBuffer(heldInput);
                        if (input == null || input.capacity() < count * 2) {
                            throw new IOException("Encoder input buffer is too small");
                        }
                        input.clear();
                        input.order(ByteOrder.nativeOrder());
                        for (int i = 0; i < count; i++) input.putShort(pcm[i]);
                        codec.queueInputBuffer(heldInput, 0, count * 2,
                                samples * 1_000_000L / SAMPLE_RATE, 0);
                        heldInput = -1;
                        samples += count;
                        lastRead = now;
                    } else {
                        Thread.sleep(5);
                    }
                } else {
                    Thread.sleep(2);
                }
                drain(info, false);
                if (now - lastRead > 10000 || now - lastOutputAt > 10000) {
                    throw new RetryableCaptureException("No microphone or encoded audio progress for 10 seconds");
                }
                if (now - checkedAt >= 1000) {
                    checkedAt = now;
                    ensureStorage();
                    AudioRecordingConfiguration config = audio.getActiveRecordingConfiguration();
                    if (config != null) {
                        boolean silenced = config.isClientSilenced();
                        if (previousSilenced == null || silenced != previousSilenced) {
                            store.event(silenced ? "microphone_silenced" : "microphone_available",
                                    "session=" + sessionId + "; Android capture-state observation");
                            listener.state(silenced ? "Recording — microphone silenced by Android"
                                    : "Recording");
                            previousSilenced = silenced;
                        }
                    }
                    AudioDeviceInfo device = audio.getRoutedDevice();
                    if (device != null && device.getId() != previousDevice) {
                        store.event("microphone_route", "session=" + sessionId
                                + "; deviceId=" + device.getId() + "; type=" + device.getType());
                        previousDevice = device.getId();
                    }
                    long newOffset = System.currentTimeMillis() - now;
                    if (Math.abs(newOffset - clockOffset) > 2000) {
                        store.event("clock_changed", "device clock offset changed by "
                                + (newOffset - clockOffset) + " ms; session=" + sessionId);
                        clockOffset = newOffset;
                    }
                    listener.progress();
                }
                if (output != null && now - flushedAt >= 5000) {
                    output.getFD().sync();
                    flushedAt = now;
                }
            }
            // Drain already waiting microphone samples before stopping. Bound both work and
            // time: stop must not become an endless attempt to catch an ongoing microphone.
            store.event("stop_requested", "session=" + sessionId + "; draining buffered audio");
            long tailDeadline = SystemClock.elapsedRealtime() + 500;
            int tailBudget = SAMPLE_RATE * 10;
            while (tailBudget > 0 && SystemClock.elapsedRealtime() < tailDeadline) {
                if (heldInput < 0) heldInput = codec.dequeueInputBuffer(1000);
                if (heldInput < 0) { drain(info, false); continue; }
                int count = audio.read(pcm, 0, Math.min(pcm.length, tailBudget), AudioRecord.READ_NON_BLOCKING);
                if (count < 0) throw new IOException("Microphone tail read failed: " + count);
                if (count == 0) break;
                ByteBuffer input = codec.getInputBuffer(heldInput);
                if (input == null || input.capacity() < count * 2) throw new IOException("Missing encoder tail buffer");
                input.clear();
                input.order(ByteOrder.nativeOrder());
                for (int i = 0; i < count; i++) input.putShort(pcm[i]);
                codec.queueInputBuffer(heldInput, 0, count * 2, samples * 1_000_000L / SAMPLE_RATE, 0);
                heldInput = -1;
                samples += count;
                tailBudget -= count;
                drain(info, false);
            }
            if (tailBudget == 0 || SystemClock.elapsedRealtime() >= tailDeadline) {
                store.event("stop_tail_limit", "session=" + sessionId + "; unread tail may remain");
            }
            audio.stop();
            // Flush all PCM already submitted to the encoder. Never label an undrained tail clean.
            long deadline = SystemClock.elapsedRealtime() + 5000;
            while (heldInput < 0 && SystemClock.elapsedRealtime() < deadline) {
                heldInput = codec.dequeueInputBuffer(10000);
                drain(info, false);
            }
            if (heldInput < 0) throw new IOException("Encoder did not accept end-of-stream");
            codec.queueInputBuffer(heldInput, 0, 0, samples * 1_000_000L / SAMPLE_RATE,
                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            boolean eos = false;
            while (!eos && SystemClock.elapsedRealtime() < deadline) eos = drain(info, true);
            if (!eos) throw new IOException("Encoder did not finalize within five seconds");
            clean = true;
        } catch (Exception e) {
            failure = e;
        } finally {
            if (audio != null) {
                try { audio.stop(); } catch (RuntimeException ignored) { }
                try { audio.release(); } catch (RuntimeException e) {
                    if (failure == null) failure = e; else failure.addSuppressed(e);
                }
            }
            if (codec != null) {
                if (codecStarted) try { codec.stop(); } catch (RuntimeException ignored) { }
                try { codec.release(); } catch (RuntimeException e) {
                    if (failure == null) failure = e; else failure.addSuppressed(e);
                }
            }
            try { closeSegment(clean && failure == null); } catch (Exception e) {
                if (failure == null) failure = e; else failure.addSuppressed(e);
            }
            try {
                store.event(failure == null ? "session_stop" : "session_error",
                        "session=" + sessionId + "; " + (failure == null ? "user requested stop"
                                : failure.getClass().getSimpleName() + ": " + failure.getMessage()));
            } catch (IOException e) {
                if (failure == null) failure = e; else failure.addSuppressed(e);
            }
        }
        if (failure != null) throw failure;
    }

    private void ensureStorage() throws IOException {
        if (store.availableBytes() < EvidenceStore.MIN_FREE_BYTES) {
            throw new IOException("Storage low: less than 256 MiB free. Existing files were kept.");
        }
    }

    private boolean drain(MediaCodec.BufferInfo info, boolean waiting) throws IOException {
        for (;;) {
            int index = codec.dequeueOutputBuffer(info, waiting ? 10000 : 0);
            if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return false;
            if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat actual = codec.getOutputFormat();
                if (actual.getInteger(MediaFormat.KEY_SAMPLE_RATE) != SAMPLE_RATE
                        || actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT) != 1) {
                    throw new IOException("Encoder changed the expected sample rate or channels");
                }
                continue;
            }
            if (index < 0) continue;
            try {
                if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_PARTIAL_FRAME) != 0) {
                        throw new IOException("Encoder produced an unsupported partial AAC frame");
                    }
                    long pts = Math.max(0, info.presentationTimeUs);
                    if (output != null && pts - segmentStartUs >= SEGMENT_US) closeSegment(true);
                    if (output == null) {
                        ensureStorage();
                        segmentStartUs = pts;
                        segmentFrames = 0;
                        partial = store.createSegment(sessionId, segmentIndex++,
                                epochOrigin + pts / 1000, elapsedOrigin + pts / 1000);
                        output = new FileOutputStream(partial, true);
                    }
                    ByteBuffer buffer = codec.getOutputBuffer(index);
                    if (buffer == null) throw new IOException("Missing encoded audio buffer");
                    buffer.position(info.offset);
                    buffer.limit(info.offset + info.size);
                    byte[] frame = new byte[info.size];
                    buffer.get(frame);
                    output.write(Adts.header(info.size));
                    output.write(frame);
                    segmentFrames++;
                    segmentEndUs = pts + FRAME_US;
                    lastOutputAt = SystemClock.elapsedRealtime();
                }
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return true;
            } finally {
                codec.releaseOutputBuffer(index, false);
            }
        }
    }

    private void closeSegment(boolean clean) throws IOException {
        if (output == null) return;
        FileOutputStream closing = output;
        output = null;
        try {
            closing.getFD().sync();
        } finally {
            closing.close();
        }
        store.finishSegment(partial, epochOrigin + segmentEndUs / 1000,
                elapsedOrigin + segmentEndUs / 1000,
                segmentFrames * 1024_000_000L / SAMPLE_RATE, clean);
        partial = null;
        listener.segmentSaved();
    }
}
