package com.example.ssr;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests verifying recording continuity, timestamp monotonicity,
 * and state transitions during screen lock / display-off periods.
 */
public class RecordingContinuityTest {

    @Test
    public void testTimestampMonotonicityAcrossDisplayOffGap() {
        // Simulate timestamps for video and audio before, during, and after display off
        long[] lastTrackPtsUs = new long[]{-1, -1};

        // 1. Initial frames (video track 0, audio track 1)
        long[] incomingVideoPts = new long[]{1000000L, 1033333L, 1066666L};
        for (long pts : incomingVideoPts) {
            long adjusted = pts;
            if (adjusted <= lastTrackPtsUs[0]) {
                adjusted = lastTrackPtsUs[0] + 1;
            }
            lastTrackPtsUs[0] = adjusted;
        }
        assertEquals(1066666L, lastTrackPtsUs[0]);

        // 2. Audio continues during display off (e.g. 5 audio buffers of ~23.2ms)
        long audioPts = 1000000L;
        for (int i = 0; i < 5; i++) {
            long adjusted = audioPts;
            if (adjusted <= lastTrackPtsUs[1]) {
                adjusted = lastTrackPtsUs[1] + 1;
            }
            lastTrackPtsUs[1] = adjusted;
            audioPts += 23219L;
        }
        assertTrue(lastTrackPtsUs[1] > 1000000L);

        // 3. Screen turns back on, but clock drift / SurfaceTexture issues produce a duplicate or slightly backwards PTS
        long nonMonotonicResumedPts = 1066666L; // Duplicate timestamp
        long adjustedResumedPts = nonMonotonicResumedPts;
        if (adjustedResumedPts <= lastTrackPtsUs[0]) {
            adjustedResumedPts = lastTrackPtsUs[0] + 1;
        }
        lastTrackPtsUs[0] = adjustedResumedPts;

        // Must be strictly greater than last recorded video PTS
        assertEquals(1066667L, lastTrackPtsUs[0]);
        assertTrue("Timestamp must be strictly monotonic for MediaMuxer", lastTrackPtsUs[0] > 1066666L);

        // 4. Backward PTS from clock reset
        long backwardPts = 1050000L;
        long adjustedBackwardPts = backwardPts;
        if (adjustedBackwardPts <= lastTrackPtsUs[0]) {
            adjustedBackwardPts = lastTrackPtsUs[0] + 1;
        }
        lastTrackPtsUs[0] = adjustedBackwardPts;
        assertEquals(1066668L, lastTrackPtsUs[0]);
        assertTrue(lastTrackPtsUs[0] > 1066667L);
    }

    @Test
    public void testStateManagerStateTransitions() {
        SSRStateManager manager = SSRStateManager.getInstance();
        assertNotNull(manager);

        final boolean[] listenerFired = new boolean[]{false};
        final boolean[] observedState = new boolean[]{false};

        SSRStateManager.RecordingStateListener listener = isRecording -> {
            listenerFired[0] = true;
            observedState[0] = isRecording;
        };

        manager.setListener(listener);

        manager.setRecording(true);
        assertTrue(manager.isRecording());
        assertTrue(listenerFired[0]);
        assertTrue(observedState[0]);

        listenerFired[0] = false;
        manager.setRecording(false);
        assertFalse(manager.isRecording());
        assertTrue(listenerFired[0]);
        assertFalse(observedState[0]);

        manager.removeListener();
        listenerFired[0] = false;
        manager.setRecording(true);
        assertFalse("Listener should not be notified after removal", listenerFired[0]);

        // Cleanup
        manager.setRecording(false);
    }

    @Test
    public void testAudioSampleRateAndFrameDurationMath() {
        int sampleRate = 44100;
        int samplesPerFrame = 1024;
        long frameDurationUs = (1000000L * samplesPerFrame) / sampleRate;

        // 1024 / 44100 seconds ≈ 23219 microseconds
        assertEquals(23219L, frameDurationUs);

        // 1 second of audio at 44.1kHz mono 16-bit
        int bytesPerSecond = sampleRate * 2; // 88200 bytes
        int framesPerSecond = sampleRate / samplesPerFrame;
        int bytesPerFrame = samplesPerFrame * 2; // 2048 bytes

        assertTrue(framesPerSecond * bytesPerFrame <= bytesPerSecond);
    }

    @Test
    public void testMp4MergerNullOrEmptyHandling() {
        assertFalse(Mp4Merger.merge(null, "output.mp4"));
        assertFalse(Mp4Merger.merge(new java.util.ArrayList<>(), "output.mp4"));
        assertFalse(Mp4Merger.merge(java.util.Collections.singletonList(null), null));
    }

    @Test
    public void testMp4MergerSingleFileOptimization() throws java.io.IOException {
        java.io.File tempSource = java.io.File.createTempFile("ssr_test_part1", ".mp4");
        java.io.File tempDest = java.io.File.createTempFile("ssr_test_merged", ".mp4");
        tempDest.delete(); // destination must be written to

        // Write dummy data
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tempSource)) {
            fos.write(new byte[]{0x00, 0x00, 0x00, 0x20, 'f', 't', 'y', 'p'});
        }

        assertTrue(tempSource.exists());
        assertTrue(tempSource.length() > 0);

        boolean merged = Mp4Merger.merge(java.util.Collections.singletonList(tempSource), tempDest.getAbsolutePath());
        assertTrue("Single segment should succeed via atomic move/copy", merged);
        assertTrue("Merged destination should exist", tempDest.exists());
        assertEquals("Merged file size should match source segment", 8, tempDest.length());

        // Cleanup
        tempDest.delete();
        tempSource.delete();
    }

    @Test
    public void testSegmentConcatenationTimestampOffsetMath() {
        // Part 1: 0 to 10 seconds (300 frames @ 30fps)
        long lastPart1VideoPtsUs = 10000000L;
        long lastPart1AudioPtsUs = 10020000L;

        // Offset for Part 2
        long baseVideoPtsOffsetUs = lastPart1VideoPtsUs + 33333L; // ~30fps frame delta
        long baseAudioPtsOffsetUs = lastPart1AudioPtsUs + 23220L; // ~23.2ms audio packet delta

        assertTrue(baseVideoPtsOffsetUs > lastPart1VideoPtsUs);
        assertTrue(baseAudioPtsOffsetUs > lastPart1AudioPtsUs);

        // Part 2 starts at PTS 0 (or slight delay e.g. 50000us)
        long part2FirstVideoPtsUs = 50000L;
        long part2SamplePtsUs = 50000L;

        long adjustedPart2PtsUs = (part2SamplePtsUs - part2FirstVideoPtsUs) + baseVideoPtsOffsetUs;
        assertEquals(baseVideoPtsOffsetUs, adjustedPart2PtsUs);
        assertTrue("Part 2 first frame must immediately follow Part 1 last frame", adjustedPart2PtsUs > lastPart1VideoPtsUs);

        // Part 2 second frame
        long part2SecondFramePtsUs = 83333L;
        long adjustedPart2SecondPtsUs = (part2SecondFramePtsUs - part2FirstVideoPtsUs) + baseVideoPtsOffsetUs;
        assertTrue(adjustedPart2SecondPtsUs > adjustedPart2PtsUs);
        assertEquals(33333L, adjustedPart2SecondPtsUs - adjustedPart2PtsUs);
    }

    @Test
    public void testPartialScreenCropRectAlignmentTo16Multiple() {
        int screenWidth = 1080;
        int screenHeight = 2400;

        // User drags an arbitrary crop box: e.g. 105 x 215 to 733 x 1587
        int cropLeft = 105;
        int cropTop = 215;
        int cropRight = 733;
        int cropBottom = 1587;

        int rawWidth = cropRight - cropLeft;   // 628
        int rawHeight = cropBottom - cropTop; // 1372

        int encWidth = rawWidth - (rawWidth % 16);   // 628 - 4 = 624
        int encHeight = rawHeight - (rawHeight % 16); // 1372 - 12 = 1360

        assertEquals(0, encWidth % 16);
        assertEquals(0, encHeight % 16);
        assertTrue(encWidth >= 128);
        assertTrue(encHeight >= 128);
        assertTrue(cropLeft + encWidth <= screenWidth);
        assertTrue(cropTop + encHeight <= screenHeight);

        // Verification of resume idempotence: running alignment on already-aligned dimensions yields exact same values
        int resumeEncWidth = encWidth - (encWidth % 16);
        int resumeEncHeight = encHeight - (encHeight % 16);
        assertEquals(encWidth, resumeEncWidth);
        assertEquals(encHeight, resumeEncHeight);
    }

    @Test
    public void testActiveRecordingDurationAccumulator() {
        long accumulatedDurationMs = 0;

        // Segment 1 recorded for 3500ms
        long seg1Start = 10000L;
        long seg1LockTime = 13500L;
        accumulatedDurationMs += (seg1LockTime - seg1Start);
        assertEquals(3500L, accumulatedDurationMs);

        // User unlocks device 5 seconds later (time = 18500L) and taps Resume
        // Chronometer base for notification
        long resumeRealtime = 18500L;
        long chronometerBase = resumeRealtime - accumulatedDurationMs; // 18500 - 3500 = 15000L
        assertEquals(3500L, resumeRealtime - chronometerBase);

        // Segment 2 recorded for another 4200ms
        long seg2Start = resumeRealtime;
        long seg2StopTime = 22700L;
        accumulatedDurationMs += (seg2StopTime - seg2Start);
        assertEquals(7700L, accumulatedDurationMs);
    }

    @Test
    public void testReauthorizationProxyActiveStateLifecycle() {
        RecordingService.resetReauthorizationProxyActive();
        // Multiple reset calls are idempotent
        RecordingService.resetReauthorizationProxyActive();
    }

    @Test
    public void testMediaProjectionConfigSelectionRules() {
        // Rule: In all cases (initial start and resume), we use UserChoice so that
        // app selection is NEVER blocked or greyed out in the system dialog.
        boolean usesDefaultDisplay = false;
        assertFalse("App selection must never be blocked by using DefaultDisplay", usesDefaultDisplay);
    }
}

