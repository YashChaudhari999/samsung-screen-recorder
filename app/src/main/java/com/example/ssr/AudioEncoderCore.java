package com.example.ssr;

import android.annotation.SuppressLint;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Wraps MediaCodec for AAC audio encoding and AudioRecord for capturing mic/system audio.
 */
public class AudioEncoderCore {
    private static final String TAG = "AudioEncoderCore";

    private static final String MIME_TYPE = MediaFormat.MIMETYPE_AUDIO_AAC;
    private static final int SAMPLE_RATE = 44100;
    private static final int BIT_RATE = 64000;
    private static final int SAMPLES_PER_FRAME = 1024; // AAC
    private static final int FRAMES_PER_BUFFER = 24;
    private static final int TIMEOUT_USEC = 10000;

    private MediaCodec mEncoder;
    private MuxerWrapper mMuxer;
    private int mTrackIndex;
    private boolean mMuxerStarted;
    private MediaCodec.BufferInfo mBufferInfo;
    
    private AudioRecord mMicRecord;
    private AudioRecord mMediaRecord;
    
    private Thread mAudioThread;
    private volatile boolean mIsRecording;
    private volatile boolean mIsPaused;
    
    // Diagnostic counters
    private volatile long mAacInputFrames = 0;
    private volatile long mTotalPcmBytesInput = 0;
    private volatile long mAacOutputBuffers = 0;
    private volatile long mTotalMicBytesRead = 0;
    private volatile long mMicReadCount = 0;
    private volatile long mMicZeroReadCount = 0;
    private volatile long mMicErrorCount = 0;
    private volatile long mTotalMediaBytesRead = 0;
    private final String mAudioMode;

    @SuppressLint("MissingPermission") // Caller should handle permissions
    public AudioEncoderCore(String audioMode, MediaProjection projection, MuxerWrapper muxer) throws IOException {
        mBufferInfo = new MediaCodec.BufferInfo();
        mMuxer = muxer;
        mTrackIndex = -1;
        mMuxerStarted = false;
        mAudioMode = audioMode;

        Log.i(TAG, "AUDIO_MODE=" + audioMode);
        Log.i(TAG, "AudioRecord sample rate: " + SAMPLE_RATE);
        Log.i(TAG, "AudioRecord channel count: 1");

        MediaFormat format = MediaFormat.createAudioFormat(MIME_TYPE, SAMPLE_RATE, 1);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);

        mEncoder = MediaCodec.createEncoderByType(MIME_TYPE);
        mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        mEncoder.start();
        Log.i(TAG, "Audio encoder configured and started");

        int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufferSize = SAMPLES_PER_FRAME * FRAMES_PER_BUFFER;
        if (bufferSize < minBufferSize) {
            bufferSize = ((minBufferSize / SAMPLES_PER_FRAME) + 1) * SAMPLES_PER_FRAME * 2;
        }
        Log.i(TAG, "AudioRecord buffer size: " + bufferSize);

        boolean recordMic = "Media and mic".equals(audioMode) || "Mic".equals(audioMode);
        boolean recordMedia = "Media".equals(audioMode) || "Media and mic".equals(audioMode);

        if (recordMic) {
            mMicRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize);
            Log.i(TAG, "AudioRecord (MIC) initialization result: " + (mMicRecord.getState() == AudioRecord.STATE_INITIALIZED ? "SUCCESS" : "FAILED"));
            Log.i(TAG, "AudioRecord (MIC) state: " + mMicRecord.getState());
            Log.i(TAG, "AudioRecord (MIC) recording state: " + mMicRecord.getRecordingState());
        }

        if (recordMedia && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && projection != null) {
            AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build();

            mMediaRecord = new AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(config)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build())
                    .setBufferSizeInBytes(bufferSize)
                    .build();
            Log.i(TAG, "AudioRecord (MEDIA) initialization result: " + (mMediaRecord.getState() == AudioRecord.STATE_INITIALIZED ? "SUCCESS" : "FAILED"));
            Log.i(TAG, "AudioRecord (MEDIA) state: " + mMediaRecord.getState());
            Log.i(TAG, "AudioRecord (MEDIA) recording state: " + mMediaRecord.getRecordingState());
        } else if (recordMedia) {
            // Fallback for Android 9 and below
            if (mMicRecord == null) {
                mMicRecord = new AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize);
                Log.i(TAG, "AudioRecord (MIC Fallback) initialization result: " + (mMicRecord.getState() == AudioRecord.STATE_INITIALIZED ? "SUCCESS" : "FAILED"));
            }
        }

        mIsRecording = true;
        mAudioThread = new Thread(new AudioRecordRunnable(bufferSize), "AudioRecordThread");
        mAudioThread.start();
    }

    private class AudioRecordRunnable implements Runnable {
        private final int bufferSize;

        AudioRecordRunnable(int bufferSize) {
            this.bufferSize = bufferSize;
        }

        @Override
        public void run() {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO);
            
            if (mMicRecord != null) {
                try {
                    mMicRecord.startRecording();
                    Log.i(TAG, "AudioRecord startRecording (MIC). State: " + mMicRecord.getState() + ", RecordingState: " + mMicRecord.getRecordingState());
                } catch (Exception e) {
                    Log.e(TAG, "MIC_THREAD_EXCEPTION on startRecording", e);
                }
            }
            if (mMediaRecord != null) {
                try {
                    mMediaRecord.startRecording();
                    Log.i(TAG, "AudioRecord startRecording (MEDIA). State: " + mMediaRecord.getState() + ", RecordingState: " + mMediaRecord.getRecordingState());
                } catch (Exception e) {
                    Log.e(TAG, "MEDIA_THREAD_EXCEPTION on startRecording", e);
                }
            }
            Log.i(TAG, "AUDIO_THREAD_STARTED for mode: " + mAudioMode);
            
            final int frameByteCount = SAMPLES_PER_FRAME * 2; // 2048 bytes (1024 16-bit mono samples)
            byte[] micBuf = new byte[frameByteCount];
            byte[] mediaBuf = new byte[frameByteCount];
            byte[] outBuf = new byte[frameByteCount];
            
            final long frameDurationUs = (1000000L * SAMPLES_PER_FRAME) / SAMPLE_RATE; // ~23219 us
            long ptsUs = System.nanoTime() / 1000;
            long nextFrameTimeUs = ptsUs;
            long lastLogTime = System.currentTimeMillis();
            
            int mediaAccumulatedBytes = 0;

            Log.i(TAG, "AUDIO_CAPTURE_LOOP started");

            while (mIsRecording) {
                if (mIsPaused) {
                    try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                    ptsUs = System.nanoTime() / 1000;
                    nextFrameTimeUs = ptsUs;
                    continue;
                }

                long now = System.currentTimeMillis();
                if (now - lastLogTime >= 3000) {
                    Log.i(TAG, String.format(java.util.Locale.US, "AUDIO STATUS | mode: %s | mic pcm: %d B | media pcm: %d B | total input: %d B | AAC in: %d | AAC out: %d",
                            mAudioMode, mTotalMicBytesRead, mTotalMediaBytesRead, mTotalPcmBytesInput, mAacInputFrames, mAacOutputBuffers));
                    if (mMicRecord != null) {
                        Log.i(TAG, "MIC STATUS | state: " + mMicRecord.getState() + ", recState: " + mMicRecord.getRecordingState());
                    }
                    if (mMediaRecord != null) {
                        Log.i(TAG, "MEDIA STATUS | state: " + mMediaRecord.getState() + ", recState: " + mMediaRecord.getRecordingState());
                    }
                    lastLogTime = now;
                }

                int micRead = 0;
                int mediaRead = 0;

                if (mMicRecord != null) {
                    // Hardware mic provides blocking clock (~23.2ms per 1024 samples)
                    micRead = mMicRecord.read(micBuf, 0, micBuf.length);
                    mMicReadCount++;
                    if (micRead > 0) {
                        mTotalMicBytesRead += micRead;
                    } else if (micRead < 0) {
                        mMicErrorCount++;
                        Log.w(TAG, "MIC_ERROR: AudioRecord.read returned " + micRead);
                    }
                }

                if (mMediaRecord != null) {
                    int spaceRemaining = frameByteCount - mediaAccumulatedBytes;
                    if (spaceRemaining > 0) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            mediaRead = mMediaRecord.read(mediaBuf, mediaAccumulatedBytes, spaceRemaining, AudioRecord.READ_NON_BLOCKING);
                        } else {
                            mediaRead = mMediaRecord.read(mediaBuf, mediaAccumulatedBytes, spaceRemaining);
                        }
                        if (mediaRead > 0) {
                            mediaAccumulatedBytes += mediaRead;
                            mTotalMediaBytesRead += mediaRead;
                        } else if (mediaRead < 0) {
                            Log.w(TAG, "MEDIA_ERROR: AudioRecord.read returned " + mediaRead);
                        }
                    }
                }

                if (mMicRecord != null) {
                    // Mode: Media + Mic
                    if (micRead > 0) {
                        if (mMediaRecord != null && mediaAccumulatedBytes > 0) {
                            int mixLen = Math.min(micRead, mediaAccumulatedBytes);
                            for (int i = 0; i < mixLen; i += 2) {
                                short micSample = (short) ((micBuf[i] & 0xFF) | (micBuf[i + 1] << 8));
                                short mediaSample = (short) ((mediaBuf[i] & 0xFF) | (mediaBuf[i + 1] << 8));
                                
                                int mixed = micSample + mediaSample;
                                if (mixed > Short.MAX_VALUE) mixed = Short.MAX_VALUE;
                                if (mixed < Short.MIN_VALUE) mixed = Short.MIN_VALUE;
                                
                                outBuf[i] = (byte) (mixed & 0xFF);
                                outBuf[i + 1] = (byte) ((mixed >> 8) & 0xFF);
                            }
                            if (micRead > mixLen) {
                                System.arraycopy(micBuf, mixLen, outBuf, mixLen, micRead - mixLen);
                            }
                            mediaAccumulatedBytes = 0;
                        } else {
                            System.arraycopy(micBuf, 0, outBuf, 0, micRead);
                        }

                        mTotalPcmBytesInput += micRead;
                        encode(outBuf, micRead, ptsUs);
                        ptsUs += (1000000L * (micRead / 2)) / SAMPLE_RATE;
                    }
                } else if (mMediaRecord != null) {
                    // Mode: Media only (clock-paced with silence insertion when inactive)
                    long nowUs = System.nanoTime() / 1000;
                    if (nowUs < nextFrameTimeUs && mediaAccumulatedBytes < frameByteCount) {
                        long waitMs = (nextFrameTimeUs - nowUs) / 1000;
                        if (waitMs > 1) {
                            try { Thread.sleep(Math.min(waitMs, 10)); } catch (InterruptedException ignored) {}
                        }
                        continue;
                    }

                    // Reached frame deadline: if media audio didn't fill buffer (or was silent), pad remainder with 0
                    if (mediaAccumulatedBytes < frameByteCount) {
                        java.util.Arrays.fill(mediaBuf, mediaAccumulatedBytes, frameByteCount, (byte) 0);
                    }
                    System.arraycopy(mediaBuf, 0, outBuf, 0, frameByteCount);
                    mediaAccumulatedBytes = 0;

                    mTotalPcmBytesInput += frameByteCount;
                    encode(outBuf, frameByteCount, ptsUs);
                    ptsUs += frameDurationUs;
                    nextFrameTimeUs += frameDurationUs;
                }
            }

            if (mMicRecord != null) {
                try {
                    mMicRecord.stop();
                    mMicRecord.release();
                } catch (Exception ignored) {}
                mMicRecord = null;
            }
            if (mMediaRecord != null) {
                try {
                    mMediaRecord.stop();
                    mMediaRecord.release();
                } catch (Exception ignored) {}
                mMediaRecord = null;
            }
            
            Log.i(TAG, "AUDIO_THREAD_EXITED");
            encode(null, 0, ptsUs);
        }
    }

    private void encode(byte[] buf, int length, long ptsUs) {
        if (!mIsRecording && buf != null) return;
        
        while (mIsRecording || buf == null) {
            int inputBufferIndex = mEncoder.dequeueInputBuffer(TIMEOUT_USEC);
            if (inputBufferIndex >= 0) {
                ByteBuffer inputBuffer = mEncoder.getInputBuffer(inputBufferIndex);
                if (inputBuffer != null) {
                    inputBuffer.clear();
                    if (buf != null && length > 0) {
                        inputBuffer.put(buf, 0, length);
                        mAacInputFrames++;
                        mEncoder.queueInputBuffer(inputBufferIndex, 0, length, ptsUs, 0);
                    } else {
                        mEncoder.queueInputBuffer(inputBufferIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    }
                }
                break;
            } else if (inputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (buf == null) {
                    // End of stream, retry until buffer is accepted
                    continue;
                }
                break;
            }
        }

        drainEncoder();
    }

    private void drainEncoder() {
        while (true) {
            int encoderStatus = mEncoder.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break;
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (mMuxerStarted) {
                    throw new RuntimeException("format changed twice");
                }
                MediaFormat newFormat = mEncoder.getOutputFormat();
                mTrackIndex = mMuxer.addTrack(newFormat);
                mMuxerStarted = true;
                Log.i(TAG, "muxer audio track creation: trackIndex=" + mTrackIndex);
            } else if (encoderStatus < 0) {
                Log.w(TAG, "unexpected result from encoder.dequeueOutputBuffer: " + encoderStatus);
            } else {
                ByteBuffer encodedData = mEncoder.getOutputBuffer(encoderStatus);
                if (encodedData != null) {
                    if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        mBufferInfo.size = 0;
                    }

                    if (mBufferInfo.size != 0) {
                        if (!mMuxerStarted) {
                            throw new RuntimeException("muxer hasn't started");
                        }
                        encodedData.position(mBufferInfo.offset);
                        encodedData.limit(mBufferInfo.offset + mBufferInfo.size);

                        mMuxer.writeSampleData(mTrackIndex, encodedData, mBufferInfo);
                        mAacOutputBuffers++;
                    }
                }

                mEncoder.releaseOutputBuffer(encoderStatus, false);

                if ((mBufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    break;
                }
            }
        }
    }

    public void release() {
        Log.i(TAG, "releasing audio encoder");
        mIsRecording = false;
        if (mAudioThread != null) {
            try {
                mAudioThread.join(2000);
            } catch (InterruptedException e) {
                Log.e(TAG, "Interrupted waiting for audio thread", e);
            }
            mAudioThread = null;
        }

        if (mEncoder != null) {
            Log.i(TAG, "FINAL AUDIO STATS | mic pcm: " + mTotalMicBytesRead + " | media pcm: " + mTotalMediaBytesRead + " | total input: " + mTotalPcmBytesInput + " | aac/muxed: " + mAacOutputBuffers);
            mEncoder.stop();
            mEncoder.release();
            mEncoder = null;
        }
    }

    public void setPaused(boolean paused) {
        mIsPaused = paused;
    }
}
