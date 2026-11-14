package com.example.ssr;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Mp4Merger
 *
 * Losslessly concatenates multiple recorded MP4 segments into a single unified MP4 video.
 * Uses Android native MediaExtractor and MediaMuxer to read compressed video (AVC/HEVC)
 * and audio (AAC) samples from each segment and write them sequentially into the output file
 * with strictly monotonic timestamps, without requiring re-encoding.
 */
public class Mp4Merger {

    private static final String TAG = "Mp4Merger";

    /**
     * Merges a list of recorded MP4 segment files into a single destination MP4 file.
     *
     * @param inputFiles  List of segment files in chronological order (Part 1, Part 2, ...).
     * @param outputPath The absolute path of the output MP4 file.
     * @return true if successful, false otherwise.
     */
    public static boolean merge(List<File> inputFiles, String outputPath) {
        if (inputFiles == null || inputFiles.isEmpty() || outputPath == null) {
            Log.e(TAG, "Invalid inputs for merge");
            return false;
        }

        // Filter out nonexistent or empty files
        List<File> validSegments = new ArrayList<>();
        for (File f : inputFiles) {
            if (f != null && f.exists() && f.length() > 0) {
                validSegments.add(f);
            }
        }

        if (validSegments.isEmpty()) {
            Log.e(TAG, "No valid segments found to merge");
            return false;
        }

        File destFile = new File(outputPath);
        File parentDir = destFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs();
        }

        // Single segment optimization: direct rename or copy
        if (validSegments.size() == 1) {
            File single = validSegments.get(0);
            Log.d(TAG, "Single segment detected (" + single.getAbsolutePath() + "), performing atomic move/copy to " + outputPath);
            if (destFile.exists()) {
                destFile.delete();
            }
            if (single.renameTo(destFile)) {
                return true;
            }
            boolean copied = copyFile(single, destFile);
            if (copied) {
                single.delete();
            }
            return copied;
        }

        Log.d(TAG, "Merging " + validSegments.size() + " segments into: " + outputPath);

        MediaMuxer muxer = null;
        try {
            // Find track formats from the first healthy segment
            MediaFormat videoFormat = null;
            MediaFormat audioFormat = null;

            for (File seg : validSegments) {
                MediaExtractor extractor = new MediaExtractor();
                try {
                    extractor.setDataSource(seg.getAbsolutePath());
                    int trackCount = extractor.getTrackCount();
                    for (int i = 0; i < trackCount; i++) {
                        MediaFormat format = extractor.getTrackFormat(i);
                        String mime = format.getString(MediaFormat.KEY_MIME);
                        if (mime != null && mime.startsWith("video/") && videoFormat == null) {
                            videoFormat = format;
                        } else if (mime != null && mime.startsWith("audio/") && audioFormat == null) {
                            audioFormat = format;
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Error probing segment: " + seg.getName(), e);
                } finally {
                    try {
                        extractor.release();
                    } catch (Exception ignored) {}
                }
                if (videoFormat != null) {
                    break;
                }
            }

            if (videoFormat == null) {
                Log.e(TAG, "No video track found across any segment");
                return false;
            }

            if (destFile.exists()) {
                destFile.delete();
            }

            muxer = new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int muxerVideoTrack = muxer.addTrack(videoFormat);
            int muxerAudioTrack = (audioFormat != null) ? muxer.addTrack(audioFormat) : -1;

            muxer.start();

            long baseVideoPtsOffsetUs = 0;
            long baseAudioPtsOffsetUs = 0;
            long lastWrittenVideoPtsUs = -1;
            long lastWrittenAudioPtsUs = -1;

            int bufferSize = 4 * 1024 * 1024; // 4MB buffer for largest IDR frames
            if (videoFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                try {
                    int maxInput = videoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE);
                    if (maxInput > bufferSize) {
                        bufferSize = maxInput;
                    }
                } catch (Exception ignored) {}
            }
            ByteBuffer buffer = ByteBuffer.allocateDirect(bufferSize);
            MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

            for (int segIndex = 0; segIndex < validSegments.size(); segIndex++) {
                File segFile = validSegments.get(segIndex);
                Log.d(TAG, "Processing segment " + (segIndex + 1) + "/" + validSegments.size() + ": " + segFile.getName() + " (" + segFile.length() + " bytes)");

                MediaExtractor extractor = new MediaExtractor();
                try {
                    extractor.setDataSource(segFile.getAbsolutePath());

                    int segVideoTrack = -1;
                    int segAudioTrack = -1;

                    for (int i = 0; i < extractor.getTrackCount(); i++) {
                        MediaFormat format = extractor.getTrackFormat(i);
                        String mime = format.getString(MediaFormat.KEY_MIME);
                        if (mime != null && mime.startsWith("video/")) {
                            segVideoTrack = i;
                        } else if (mime != null && mime.startsWith("audio/")) {
                            segAudioTrack = i;
                        }
                    }

                    if (segVideoTrack >= 0) {
                        extractor.selectTrack(segVideoTrack);
                    }
                    if (segAudioTrack >= 0 && muxerAudioTrack >= 0) {
                        extractor.selectTrack(segAudioTrack);
                    }

                    long segFirstVideoPtsUs = -1;
                    long segFirstAudioPtsUs = -1;
                    long segLastVideoPtsUs = -1;
                    long segLastAudioPtsUs = -1;

                    while (true) {
                        int trackIndex = extractor.getSampleTrackIndex();
                        if (trackIndex < 0) {
                            break; // End of samples for this segment
                        }

                        boolean isVideo = (trackIndex == segVideoTrack);
                        boolean isAudio = (trackIndex == segAudioTrack && muxerAudioTrack >= 0);

                        if (!isVideo && !isAudio) {
                            extractor.advance();
                            continue;
                        }

                        buffer.clear();
                        int sampleSize = extractor.readSampleData(buffer, 0);
                        if (sampleSize < 0) {
                            break;
                        }

                        long sampleTimeUs = extractor.getSampleTime();
                        int sampleFlags = extractor.getSampleFlags();

                        int targetTrack;
                        long adjustedPtsUs;

                        if (isVideo) {
                            targetTrack = muxerVideoTrack;
                            if (segFirstVideoPtsUs < 0) {
                                segFirstVideoPtsUs = sampleTimeUs;
                            }
                            segLastVideoPtsUs = sampleTimeUs;

                            adjustedPtsUs = (sampleTimeUs - segFirstVideoPtsUs) + baseVideoPtsOffsetUs;
                            if (adjustedPtsUs <= lastWrittenVideoPtsUs) {
                                adjustedPtsUs = lastWrittenVideoPtsUs + 1000;
                            }
                            lastWrittenVideoPtsUs = adjustedPtsUs;
                        } else {
                            targetTrack = muxerAudioTrack;
                            if (segFirstAudioPtsUs < 0) {
                                segFirstAudioPtsUs = sampleTimeUs;
                            }
                            segLastAudioPtsUs = sampleTimeUs;

                            adjustedPtsUs = (sampleTimeUs - segFirstAudioPtsUs) + baseAudioPtsOffsetUs;
                            if (adjustedPtsUs <= lastWrittenAudioPtsUs) {
                                adjustedPtsUs = lastWrittenAudioPtsUs + 1000;
                            }
                            lastWrittenAudioPtsUs = adjustedPtsUs;
                        }

                        bufferInfo.offset = 0;
                        bufferInfo.size = sampleSize;
                        bufferInfo.presentationTimeUs = adjustedPtsUs;
                        bufferInfo.flags = sampleFlags;

                        muxer.writeSampleData(targetTrack, buffer, bufferInfo);
                        extractor.advance();
                    }

                    // Prepare offsets for the next segment
                    if (lastWrittenVideoPtsUs >= 0) {
                        baseVideoPtsOffsetUs = lastWrittenVideoPtsUs + 33333; // ~30 fps frame duration
                    }
                    if (lastWrittenAudioPtsUs >= 0) {
                        baseAudioPtsOffsetUs = lastWrittenAudioPtsUs + 23220; // ~23.2ms audio packet duration
                    }

                } catch (Exception e) {
                    Log.e(TAG, "Error processing segment file: " + segFile.getName(), e);
                } finally {
                    try {
                        extractor.release();
                    } catch (Exception ignored) {}
                }
            }

            try {
                muxer.stop();
            } catch (Exception e) {
                Log.e(TAG, "Error stopping muxer", e);
            }
            try {
                muxer.release();
            } catch (Exception e) {
                Log.e(TAG, "Error releasing muxer", e);
            }
            muxer = null;

            // Delete temporary segments on successful merge
            for (File segFile : validSegments) {
                try {
                    segFile.delete();
                } catch (Exception ignored) {}
            }

            Log.i(TAG, "Successfully merged segments into: " + outputPath + " (final size=" + destFile.length() + " bytes)");
            return true;

        } catch (Exception e) {
            Log.e(TAG, "Fatal error merging segments", e);
            if (muxer != null) {
                try {
                    muxer.release();
                } catch (Exception ignored) {}
            }
            // Fallback: If merge failed, copy the largest valid segment to output path so user does not lose recording
            File largest = null;
            for (File seg : validSegments) {
                if (largest == null || seg.length() > largest.length()) {
                    largest = seg;
                }
            }
            if (largest != null && !destFile.exists()) {
                Log.w(TAG, "Fallback: copying largest segment to destination: " + largest.getName());
                copyFile(largest, destFile);
            }
            return false;
        }
    }

    private static boolean copyFile(File src, File dst) {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Error copying file from " + src + " to " + dst, e);
            return false;
        }
    }
}

