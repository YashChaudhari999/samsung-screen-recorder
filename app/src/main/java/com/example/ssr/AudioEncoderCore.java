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
    private volatile long mTotalPcmBytesInput = 0;
    private volatile long mAacOutputBuffers = 0;
    private volatile long mTotalMicBytesRead = 0;
    private volatile long mMicReadCount = 0;
    private volatile long mMicZeroReadCount = 0;
    private volatile long mMicErrorCount = 0;
    private volatile long mTotalMediaBytesRead = 0;

    @SuppressLint("MissingPermission") // Caller should handle permissions
    public AudioEncoderCore(String audioMode, MediaProjection projection, MuxerWrapper muxer) throws IOException {
        mBufferInfo = new MediaCodec.BufferInfo();
        mMuxer = muxer;
        mTrackIndex = -1;
        mMuxerStarted = false;

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

        boolean recordMic = "Media and mic".equals(audioMode) || "Mic".equals(audioMode);
        boolean recordMedia = "Media".equals(audioMode) || "Media and mic".equals(audioMode);

        if (recordMic) {
            mMicRecord = new AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize);
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
        } else if (recordMedia) {
            // Fallback for Android 9 and below
            if (mMicRecord == null) {
                mMicRecord = new AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize);
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
                Log.i(TAG, "AudioRecord startRecording (MIC). State: " + mMicRecord.getState() + ", RecordingState: " + mMicRecord.getRecordingState());
                try {
                    mMicRecord.startRecording();
                } catch (Exception e) {
                    Log.e(TAG, "MIC_THREAD_EXCEPTION on startRecording", e);
                }
            }
            if (mMediaRecord != null) mMediaRecord.startRecording();
            Log.i(TAG, "MIC_THREAD_STARTED");
            
            byte[] micBuf = new byte[SAMPLES_PER_FRAME * 2];
            byte[] mediaBuf = new byte[SAMPLES_PER_FRAME * 2];
            byte[] outBuf = new byte[SAMPLES_PER_FRAME * 2];
            
            long ptsUs = System.nanoTime() / 1000;
            long startTimeMs = System.currentTimeMillis();
            long lastLogTime = startTimeMs;
            
            Log.i(TAG, "MIC_CAPTURE_LOOP started");

            while (mIsRecording) {
                int micRead = 0;
                int mediaRead = 0;
                
                long now = System.currentTimeMillis();
                if (now - lastLogTime >= 1000) {
                    Log.i(TAG, String.format("MIC STATUS | elapsed: %d ms | mic pcm: %d bytes (reads: %d, zero: %d, err: %d) | media pcm: %d bytes | total input: %d | aac/muxed: %d",
                            (now - startTimeMs), mTotalMicBytesRead, mMicReadCount, mMicZeroReadCount, mMicErrorCount, mTotalMediaBytesRead, mTotalPcmBytesInput, mAacOutputBuffers));
                    if (mMicRecord != null) {
                        Log.i(TAG, "MIC STATUS | state: " + mMicRecord.getState() + ", recState: " + mMicRecord.getRecordingState());
                    }
                    lastLogTime = now;
                }

                if (mMicRecord != null) {
                    micRead = mMicRecord.read(micBuf, 0, micBuf.length);
                    mMicReadCount++;
                    if (micRead > 0) {
                        mTotalMicBytesRead += micRead;
                    } else if (micRead == 0) {
                        mMicZeroReadCount++;
                    } else {
                        mMicErrorCount++;
                        Log.e(TAG, "MIC_ERROR: AudioRecord.read returned " + micRead);
                    }
                }
                if (mMediaRecord != null) {
                    mediaRead = mMediaRecord.read(mediaBuf, 0, mediaBuf.length);
                    if (mediaRead > 0) mTotalMediaBytesRead += mediaRead;
                }

                int maxRead = Math.max(Math.max(micRead, mediaRead), 0);
                if (maxRead > 0) {
                    mTotalPcmBytesInput += maxRead;
                    // Mix the audio if both are present
                    if (mMicRecord != null && mMediaRecord != null && micRead > 0 && mediaRead > 0) {
                        for (int i = 0; i < maxRead; i += 2) {
                            short micSample = (short) ((micBuf[i] & 0xFF) | (micBuf[i + 1] << 8));
                            short mediaSample = (short) ((mediaBuf[i] & 0xFF) | (mediaBuf[i + 1] << 8));
                            
                            // Mix samples with clipping
                            int mixed = micSample + mediaSample;
                            if (mixed > Short.MAX_VALUE) mixed = Short.MAX_VALUE;
                            if (mixed < Short.MIN_VALUE) mixed = Short.MIN_VALUE;
                            
                            outBuf[i] = (byte) (mixed & 0xFF);
                            outBuf[i + 1] = (byte) ((mixed >> 8) & 0xFF);
                        }
                    } else if (micRead > 0) {
                        System.arraycopy(micBuf, 0, outBuf, 0, micRead);
                    } else if (mediaRead > 0) {
                        System.arraycopy(mediaBuf, 0, outBuf, 0, mediaRead);
                    }

                    if (!mIsPaused) {
                        encode(outBuf, maxRead, ptsUs);
                    }
                    ptsUs += (1000000L * maxRead / 2) / SAMPLE_RATE;
                }
            }

            if (mMicRecord != null) {
                mMicRecord.stop();
                mMicRecord.release();
                mMicRecord = null;
            }
            if (mMediaRecord != null) {
                mMediaRecord.stop();
                mMediaRecord.release();
                mMediaRecord = null;
            }
            
            Log.i(TAG, "MIC_THREAD_EXITED");
            // signal EOF
            encode(null, 0, ptsUs);
        }
    }

    private void encode(byte[] buf, int length, long ptsUs) {
        if (!mIsRecording && buf != null) return;
        
        ByteBuffer[] inputBuffers = mEncoder.getInputBuffers();
        while (true) {
            int inputBufferIndex = mEncoder.dequeueInputBuffer(TIMEOUT_USEC);
            if (inputBufferIndex >= 0) {
                ByteBuffer inputBuffer = inputBuffers[inputBufferIndex];
                inputBuffer.clear();
                if (buf != null && length > 0) {
                    inputBuffer.put(buf, 0, length);
                    mEncoder.queueInputBuffer(inputBufferIndex, 0, length, ptsUs, 0);
                } else {
                    mEncoder.queueInputBuffer(inputBufferIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                }
                break;
            } else if (inputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // wait
            }
        }

        drainEncoder();
    }

    private void drainEncoder() {
        ByteBuffer[] outputBuffers = mEncoder.getOutputBuffers();
        while (true) {
            int encoderStatus = mEncoder.dequeueOutputBuffer(mBufferInfo, TIMEOUT_USEC);
            if (encoderStatus == MediaCodec.INFO_TRY_AGAIN_LATER) {
                break;
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                outputBuffers = mEncoder.getOutputBuffers();
            } else if (encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (mMuxerStarted) {
                    throw new RuntimeException("format changed twice");
                }
                MediaFormat newFormat = mEncoder.getOutputFormat();
                mTrackIndex = mMuxer.addTrack(newFormat);
                mMuxerStarted = true;
            } else if (encoderStatus < 0) {
                Log.w(TAG, "unexpected result from encoder.dequeueOutputBuffer: " + encoderStatus);
            } else {
                ByteBuffer encodedData = outputBuffers[encoderStatus];
                if (encodedData == null) {
                    throw new RuntimeException("encoderOutputBuffer " + encoderStatus + " was null");
                }

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
