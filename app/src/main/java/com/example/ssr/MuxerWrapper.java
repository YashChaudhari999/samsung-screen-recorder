package com.example.ssr;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Wraps MediaMuxer to combine audio and video tracks into an MP4 file.
 * Handles the state machine of waiting for all tracks before starting the muxer.
 */
public class MuxerWrapper {
    private static final String TAG = "MuxerWrapper";

    private String mOutputPath;
    private MediaMuxer mMediaMuxer;
    private int mExpectedNumTracks;
    private int mNumTracksAdded;
    private boolean mIsStarted;
    private final long[] mLastTrackPtsUs;

    public MuxerWrapper(String outputPath, int expectedNumTracks) throws IOException {
        mOutputPath = outputPath;
        mExpectedNumTracks = expectedNumTracks;
        mMediaMuxer = new MediaMuxer(mOutputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        mNumTracksAdded = 0;
        mIsStarted = false;
        mLastTrackPtsUs = new long[Math.max(expectedNumTracks, 2)];
        java.util.Arrays.fill(mLastTrackPtsUs, -1);
    }

    public synchronized int addTrack(MediaFormat format) {
        if (mIsStarted) {
            throw new IllegalStateException("Muxer already started");
        }
        int trackIndex = mMediaMuxer.addTrack(format);
        mNumTracksAdded++;
        Log.i(TAG, "Added track index " + trackIndex + ". Total added: " + mNumTracksAdded + "/" + mExpectedNumTracks);

        if (mNumTracksAdded == mExpectedNumTracks) {
            Log.i(TAG, "All expected tracks added. Starting Muxer.");
            mMediaMuxer.start();
            mIsStarted = true;
            notifyAll();
        }
        return trackIndex;
    }

    public synchronized void writeSampleData(int trackIndex, ByteBuffer byteBuf, MediaCodec.BufferInfo bufferInfo) {
        if (!mIsStarted) {
            Log.w(TAG, "writeSampleData called before muxer started. Waiting...");
            try {
                wait(2500);
            } catch (InterruptedException e) {
                Log.e(TAG, "Interrupted while waiting for muxer to start", e);
                return;
            }
        }
        if (mIsStarted && bufferInfo.size > 0 && bufferInfo.presentationTimeUs >= 0) {
            if (trackIndex >= 0 && trackIndex < mLastTrackPtsUs.length) {
                long lastPts = mLastTrackPtsUs[trackIndex];
                if (bufferInfo.presentationTimeUs <= lastPts) {
                    bufferInfo.presentationTimeUs = lastPts + 1;
                }
                mLastTrackPtsUs[trackIndex] = bufferInfo.presentationTimeUs;
            }
            try {
                mMediaMuxer.writeSampleData(trackIndex, byteBuf, bufferInfo);
            } catch (Exception e) {
                Log.e(TAG, "Error writing sample data to muxer", e);
            }
        }
    }

    public synchronized void stop() {
        if (mMediaMuxer != null) {
            if (mIsStarted) {
                Log.i(TAG, "Stopping muxer");
                try {
                    mMediaMuxer.stop();
                } catch (Exception e) {
                    Log.e(TAG, "Error stopping muxer", e);
                }
            } else {
                Log.i(TAG, "Muxer never started, just releasing");
            }
            try {
                mMediaMuxer.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing muxer", e);
            }
            mMediaMuxer = null;
            mIsStarted = false;
        }
    }
}
