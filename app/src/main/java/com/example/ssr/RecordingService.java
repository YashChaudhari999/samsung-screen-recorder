package com.example.ssr;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaScannerConnection;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class RecordingService extends Service {
    private static final String TAG = "RecordingService";

    public static final String ACTION_START = "com.example.ssr.ACTION_START";
    public static final String ACTION_STOP = "com.example.ssr.ACTION_STOP";
    public static final String ACTION_PAUSE = "com.example.ssr.ACTION_PAUSE";
    public static final String ACTION_RESUME = "com.example.ssr.ACTION_RESUME";
    public static final String ACTION_RESUME_WITH_TOKEN = "com.example.ssr.ACTION_RESUME_WITH_TOKEN";

    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    public static final String EXTRA_AUDIO_MODE = "audio_mode"; // "None", "Media", "Media and mic"
    public static final String EXTRA_AREA_MODE = "area_mode"; // 0: Full screen, 1: Partial screen

    private static final int NOTIFICATION_ID = 101;
    private static final String CHANNEL_ID = "recording_channel";

    private MediaProjectionManager mProjectionManager;
    private MediaProjection mMediaProjection;
    private VirtualDisplay mVirtualDisplay;
    
    private MuxerWrapper mMuxerWrapper;
    private VideoEncoderCore mVideoEncoder;
    private AudioEncoderCore mAudioEncoder;
    private GLRenderWrapper mGLWrapper;
    
    private FloatingRecorderControl mFloatingRecorderControl;
    private boolean mIsRecording = false;
    private boolean mIsPaused = false;
    private boolean mWaitingForReauthorization = false;
    private boolean mUserRequestedStop = false;

    private Thread mVideoThread;
    private PartialScreenOverlay mPartialScreenOverlay;
    private PowerManager.WakeLock mWakeLock;
    private boolean mAutoPausedByScreenOff = false;
    private boolean mScreenReceiverRegistered = false;

    private int mScreenWidth;
    private int mScreenHeight;
    private int mScreenDensity;

    private String mFinalOutputFile;
    private File mCurrentSegmentFile;
    private final List<File> mSegmentFiles = new ArrayList<>();
    private String mCurrentAudioMode;
    private int mCurrentAreaMode;
    private android.graphics.Rect mCurrentCropRect;
    private long mActiveRecordingDurationMs = 0;
    private long mCurrentSegmentStartTimeMs = 0;
    private static volatile boolean sReauthorizationProxyActive = false;

    public static void resetReauthorizationProxyActive() {
        sReauthorizationProxyActive = false;
    }

    private final BroadcastReceiver mScreenStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                Log.d(TAG, "Screen off detected — auto-pausing recording");
                if (mIsRecording && !mIsPaused) {
                    mAutoPausedByScreenOff = true;
                    pauseRecording();
                }
            } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                Log.d(TAG, "User present (unlocked) detected. mWaitingForReauthorization=" + mWaitingForReauthorization);
                if (mWaitingForReauthorization) {
                    launchReauthorizationProxy();
                } else if (mIsRecording && mIsPaused && mAutoPausedByScreenOff) {
                    mAutoPausedByScreenOff = false;
                    resumeRecording();
                }
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
                boolean isLocked = km != null && km.isKeyguardLocked();
                Log.d(TAG, "Screen on detected, isKeyguardLocked=" + isLocked);
                if (!isLocked && !mWaitingForReauthorization && mIsRecording && mIsPaused && mAutoPausedByScreenOff) {
                    mAutoPausedByScreenOff = false;
                    resumeRecording();
                } else if (!isLocked && mWaitingForReauthorization) {
                    launchReauthorizationProxy();
                }
            }
        }
    };

    private final MediaProjection.Callback mMediaProjectionCallback = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            Log.d(TAG, "MediaProjection.Callback.onStop() fired");
            if (mUserRequestedStop) {
                Log.d(TAG, "Stop was requested by user; ignoring onStop callback");
                return;
            }
            if (mIsRecording) {
                Log.d(TAG, "MediaProjection stopped by system (e.g. screen lock). Transitioning to waiting-for-reauthorization state.");
                handleSystemStoppedMediaProjection();
            }
        }

        @Override
        public void onCapturedContentVisibilityChanged(boolean isVisible) {
            super.onCapturedContentVisibilityChanged(isVisible);
            Log.d(TAG, "onCapturedContentVisibilityChanged: isVisible=" + isVisible);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        if (wm != null) {
            wm.getDefaultDisplay().getRealMetrics(metrics);
            mScreenDensity = metrics.densityDpi;
            mScreenWidth = metrics.widthPixels;
            mScreenHeight = metrics.heightPixels;
        }
        
        // Ensure even dimensions for video codec
        if (mScreenWidth % 2 != 0) mScreenWidth--;
        if (mScreenHeight % 2 != 0) mScreenHeight--;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        String action = intent.getAction();
        Log.d(TAG, "onStartCommand: action=" + action);

        if (ACTION_START.equals(action)) {
            if (!mIsRecording && !mWaitingForReauthorization) {
                startRecording(intent);
            }
        } else if (ACTION_STOP.equals(action)) {
            stopRecordingSession();
        } else if (ACTION_PAUSE.equals(action)) {
            if (mIsRecording && !mIsPaused) {
                pauseRecording();
            }
        } else if (ACTION_RESUME.equals(action)) {
            if (mWaitingForReauthorization) {
                sReauthorizationProxyActive = false;
                launchReauthorizationProxy();
            } else if (mIsRecording && mIsPaused) {
                resumeRecording();
            }
        } else if (ACTION_RESUME_WITH_TOKEN.equals(action)) {
            if (mWaitingForReauthorization) {
                resumeRecordingWithToken(intent);
            }
        }

        return START_NOT_STICKY;
    }

    private void startRecording(Intent intent) {
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        String audioMode = intent.getStringExtra(EXTRA_AUDIO_MODE);
        int areaMode = intent.getIntExtra(EXTRA_AREA_MODE, 0);
        android.graphics.Rect cropRect = intent.getParcelableExtra("crop_rect");
        
        boolean hasAudio = !"None".equals(audioMode);
        if (hasAudio && androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission is missing! Cannot start audio recording for mode: " + audioMode);
            stopSelf();
            return;
        }
        
        if (resultCode != 0 && resultData != null) {
            if (!startForegroundService(audioMode)) {
                stopSelf();
                return;
            }

            Log.d(TAG, "GET_MEDIAPROJECTION_START");
            mMediaProjection = mProjectionManager.getMediaProjection(resultCode, resultData);
            if (mMediaProjection == null) {
                Log.e(TAG, "MediaProjection is null");
                stopSelf();
                return;
            }
            Log.d(TAG, "GET_MEDIAPROJECTION_SUCCESS");
            
            mMediaProjection.registerCallback(mMediaProjectionCallback, new Handler(Looper.getMainLooper()));
            
            mCurrentAudioMode = audioMode != null ? audioMode : "None";
            mCurrentAreaMode = areaMode;
            mCurrentCropRect = cropRect != null ? new android.graphics.Rect(cropRect) : null;
            mActiveRecordingDurationMs = 0;
            mCurrentSegmentStartTimeMs = SystemClock.elapsedRealtime();
            mSegmentFiles.clear();
            mUserRequestedStop = false;
            mWaitingForReauthorization = false;

            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Screen Recordings");
            if (!dir.exists()) {
                dir.mkdirs();
            }
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            mFinalOutputFile = new File(dir, "Screen_Recording_" + timeStamp + ".mp4").getAbsolutePath();
            mCurrentSegmentFile = new File(dir, ".tmp_recording_seg_" + timeStamp + "_0.mp4");

            try {
                mIsRecording = true;
                SSRStateManager.getInstance().setRecording(true);

                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SSR:RecordingWakeLock");
                    mWakeLock.setReferenceCounted(false);
                    mWakeLock.acquire();
                    Log.d(TAG, "Acquired PARTIAL_WAKE_LOCK for recording session");
                }

                setupEncodersForFile(mCurrentSegmentFile.getAbsolutePath(), mCurrentAudioMode, mCurrentCropRect);
                showFloatingStopButton(areaMode == 1 ? mCurrentCropRect : null);
                broadcastRecordingState(true);

                if (!mScreenReceiverRegistered) {
                    IntentFilter screenFilter = new IntentFilter();
                    screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
                    screenFilter.addAction(Intent.ACTION_SCREEN_ON);
                    screenFilter.addAction(Intent.ACTION_USER_PRESENT);
                    registerReceiver(mScreenStateReceiver, screenFilter);
                    mScreenReceiverRegistered = true;
                }

                Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.e(TAG, "FAILED TO START RECORDING", e);
                stopRecordingSession();
            }
        } else {
            Log.e(TAG, "Result code or data missing");
            stopSelf();
        }
    }

    private void setupEncodersForFile(String outputPath, String audioMode, android.graphics.Rect cropRect) throws IOException {
        Log.d(TAG, "START_SETUP_ENCODERS -> output=" + outputPath);
        boolean hasAudio = !"None".equals(audioMode);
        int expectedTracks = hasAudio ? 2 : 1;
        
        mMuxerWrapper = new MuxerWrapper(outputPath, expectedTracks);
        
        int encWidth = mScreenWidth;
        int encHeight = mScreenHeight;
        if (cropRect != null) {
            encWidth = cropRect.width();
            encHeight = cropRect.height();
            // Hardware encoders (like MTK/Exynos) strictly require dimensions to be multiples of 16
            encWidth = encWidth - (encWidth % 16);
            encHeight = encHeight - (encHeight % 16);
            if (encWidth < 128) encWidth = 128;
            if (encHeight < 128) encHeight = 128;

            if (encWidth > mScreenWidth) encWidth = mScreenWidth - (mScreenWidth % 16);
            if (encHeight > mScreenHeight) encHeight = mScreenHeight - (mScreenHeight % 16);
            
            if (cropRect.left + encWidth > mScreenWidth) {
                cropRect.left = Math.max(0, mScreenWidth - encWidth);
            }
            cropRect.right = cropRect.left + encWidth;
            if (cropRect.top + encHeight > mScreenHeight) {
                cropRect.top = Math.max(0, mScreenHeight - encHeight);
            }
            cropRect.bottom = cropRect.top + encHeight;
        }

        // Video
        int bitRate = 6000000; // 6 Mbps
        mVideoEncoder = new VideoEncoderCore(encWidth, encHeight, bitRate, mMuxerWrapper);
        Log.d(TAG, "VIDEO_ENCODER_CREATED");
        
        android.view.Surface encoderSurface = mVideoEncoder.getInputSurface();
        android.view.Surface virtualDisplaySurface;
        
        if (cropRect != null) {
            final android.graphics.Rect immutableCropRect = new android.graphics.Rect(cropRect);
            mGLWrapper = new GLRenderWrapper(encoderSurface, encWidth, encHeight, mScreenWidth, mScreenHeight, immutableCropRect);
            mGLWrapper.start();
            Log.d(TAG, "GL_WRAPPER_STARTED");
            virtualDisplaySurface = mGLWrapper.getInputSurface();

            if (mPartialScreenOverlay == null) {
                final PartialScreenOverlay overlay = new PartialScreenOverlay(this, immutableCropRect);
                mPartialScreenOverlay = overlay;
                new Handler(Looper.getMainLooper()).post(overlay::showDimOverlay);
            }
        } else {
            virtualDisplaySurface = encoderSurface;
        }

        Log.d(TAG, "CREATE_VIRTUAL_DISPLAY_START");
        mVirtualDisplay = mMediaProjection.createVirtualDisplay(
                "ScreenRecorder",
                mScreenWidth, mScreenHeight, mScreenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                virtualDisplaySurface, null, null);
        Log.d(TAG, "CREATE_VIRTUAL_DISPLAY_SUCCESS");

        // Audio
        if (hasAudio) {
            mAudioEncoder = new AudioEncoderCore(audioMode, mMediaProjection, mMuxerWrapper);
        }

        // Thread to drain video encoder
        mVideoThread = new Thread(() -> {
            try {
                while (mIsRecording) {
                    if (mIsPaused) {
                        Thread.sleep(100);
                        continue;
                    }
                    mVideoEncoder.drainEncoder(false);
                    Thread.sleep(20);
                }
                mVideoEncoder.drainEncoder(true);
            } catch (InterruptedException ignored) {
            } catch (Exception e) {
                Log.e(TAG, "Video encoder thread error", e);
                if (mIsRecording) {
                    new Handler(Looper.getMainLooper()).post(RecordingService.this::stopRecordingSession);
                }
            }
        }, "VideoDrainThread");
        mVideoThread.start();
        Log.d(TAG, "VIDEO_THREAD_STARTED");
        Log.d(TAG, "SETUP_ENCODERS_COMPLETE");
    }

    private void showFloatingStopButton(android.graphics.Rect cropRect) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                if (mFloatingRecorderControl == null) {
                    mFloatingRecorderControl = new FloatingRecorderControl(this, cropRect);
                    mFloatingRecorderControl.show();
                }
            } catch (Exception e) {
                Log.e(TAG, "Failed to show floating stop button", e);
            }
        });
    }

    private void pauseRecording() {
        mIsPaused = true;
        if (mCurrentSegmentStartTimeMs > 0) {
            mActiveRecordingDurationMs += (SystemClock.elapsedRealtime() - mCurrentSegmentStartTimeMs);
            mCurrentSegmentStartTimeMs = 0;
        }
        if (mVideoEncoder != null) {
            mVideoEncoder.setPaused(true);
        }
        if (mAudioEncoder != null) {
            mAudioEncoder.setPaused(true);
        }
        if (mGLWrapper != null) {
            mGLWrapper.setPaused(true);
        }
        if (mFloatingRecorderControl != null) {
            new Handler(Looper.getMainLooper()).post(() -> {
                if (mFloatingRecorderControl != null) {
                    mFloatingRecorderControl.setPaused(true);
                }
            });
        }
        if (mPartialScreenOverlay != null) {
            mPartialScreenOverlay.pauseTimer();
        }
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Toast.makeText(getApplicationContext(), "Recording paused", Toast.LENGTH_SHORT).show();
            } catch (Exception ignored) {}
        });
    }

    private void resumeRecording() {
        mIsPaused = false;
        mCurrentSegmentStartTimeMs = SystemClock.elapsedRealtime();
        if (mVideoEncoder != null) {
            mVideoEncoder.setPaused(false);
        }
        if (mAudioEncoder != null) {
            mAudioEncoder.setPaused(false);
        }
        if (mGLWrapper != null) {
            mGLWrapper.setPaused(false);
        }
        if (mFloatingRecorderControl != null) {
            new Handler(Looper.getMainLooper()).post(() -> {
                if (mFloatingRecorderControl != null) {
                    mFloatingRecorderControl.setPaused(false);
                }
            });
        }
        if (mPartialScreenOverlay != null) {
            mPartialScreenOverlay.resumeTimer();
        }
        startForegroundService(mCurrentAudioMode);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Toast.makeText(getApplicationContext(), "Recording resumed", Toast.LENGTH_SHORT).show();
            } catch (Exception ignored) {}
        });
    }

    private synchronized void handleSystemStoppedMediaProjection() {
        if (!mIsRecording) return;
        mIsRecording = false;
        mIsPaused = true;
        mWaitingForReauthorization = true;
        if (mCurrentSegmentStartTimeMs > 0) {
            mActiveRecordingDurationMs += (SystemClock.elapsedRealtime() - mCurrentSegmentStartTimeMs);
            mCurrentSegmentStartTimeMs = 0;
        }
        Log.d(TAG, "handleSystemStoppedMediaProjection: finalizing current segment and entering waiting-for-reauthorization state");

        finalizeActiveSegment();

        if (mFloatingRecorderControl != null) {
            new Handler(Looper.getMainLooper()).post(() -> {
                if (mFloatingRecorderControl != null) {
                    mFloatingRecorderControl.setPaused(true);
                }
            });
        }
        if (mPartialScreenOverlay != null) {
            mPartialScreenOverlay.pauseTimer();
        }

        updateNotificationForPaused();

        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                Toast.makeText(getApplicationContext(), "Recording paused (Screen locked). Tap Resume to continue.", Toast.LENGTH_SHORT).show();
            } catch (Exception ignored) {}
        });
    }

    private synchronized void launchReauthorizationProxy() {
        if (sReauthorizationProxyActive) {
            Log.d(TAG, "launchReauthorizationProxy: proxy already active, skipping duplicate launch");
            return;
        }
        sReauthorizationProxyActive = true;
        Log.d(TAG, "launchReauthorizationProxy: starting PermissionProxyActivity");
        Intent proxyIntent = new Intent(this, PermissionProxyActivity.class);
        proxyIntent.putExtra(PermissionProxyActivity.EXTRA_IS_RESUME, true);
        proxyIntent.putExtra(EXTRA_AUDIO_MODE, mCurrentAudioMode);
        proxyIntent.putExtra(EXTRA_AREA_MODE, mCurrentAreaMode);
        proxyIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                PendingIntent pi = PendingIntent.getActivity(
                        this,
                        201,
                        proxyIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                );
                android.app.ActivityOptions options = android.app.ActivityOptions.makeBasic();
                options.setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
                pi.send(this, 0, null, null, null, null, options.toBundle());
            } else {
                startActivity(proxyIntent);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to launch PermissionProxyActivity via PendingIntent, falling back to startActivity", e);
            try {
                startActivity(proxyIntent);
            } catch (Exception ignored) {}
        }
    }

    private synchronized void resumeRecordingWithToken(Intent intent) {
        sReauthorizationProxyActive = false;
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0);
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        if (resultCode == 0 || resultData == null) {
            Log.e(TAG, "resumeRecordingWithToken failed: invalid resultCode or resultData");
            return;
        }

        Log.d(TAG, "resumeRecordingWithToken: getting new MediaProjection");
        mMediaProjection = mProjectionManager.getMediaProjection(resultCode, resultData);
        if (mMediaProjection == null) {
            Log.e(TAG, "resumeRecordingWithToken failed: MediaProjection is null");
            return;
        }
        mMediaProjection.registerCallback(mMediaProjectionCallback, new Handler(Looper.getMainLooper()));

        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Screen Recordings");
            String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            mCurrentSegmentFile = new File(dir, ".tmp_recording_seg_" + timeStamp + "_" + mSegmentFiles.size() + ".mp4");

            mWaitingForReauthorization = false;
            mIsRecording = true;
            mIsPaused = false;
            mCurrentSegmentStartTimeMs = SystemClock.elapsedRealtime();

            setupEncodersForFile(mCurrentSegmentFile.getAbsolutePath(), mCurrentAudioMode, mCurrentCropRect);

            if (mFloatingRecorderControl != null) {
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (mFloatingRecorderControl != null) {
                        mFloatingRecorderControl.setPaused(false);
                    }
                });
            } else {
                showFloatingStopButton(mCurrentAreaMode == 1 ? mCurrentCropRect : null);
            }
            if (mPartialScreenOverlay != null) {
                mPartialScreenOverlay.resumeTimer();
            }

            // Restore normal active recording notification
            startForegroundService(mCurrentAudioMode);

            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    Toast.makeText(getApplicationContext(), "Recording resumed", Toast.LENGTH_SHORT).show();
                } catch (Exception ignored) {}
            });
        } catch (Exception e) {
            Log.e(TAG, "Failed to start next recording segment", e);
            stopRecordingSession();
        }
    }

    private void finalizeActiveSegment() {
        if (mAudioEncoder != null) {
            mAudioEncoder.release();
            mAudioEncoder = null;
        }

        if (mVideoThread != null) {
            try {
                mVideoThread.join(2000);
                if (mVideoThread.isAlive()) {
                    Log.e(TAG, "Video drain thread did not finish within 2 seconds");
                } else {
                    Log.d(TAG, "Video drain thread finished successfully");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.e(TAG, "Interrupted while waiting for video drain thread", e);
            } finally {
                mVideoThread = null;
            }
        }

        if (mVirtualDisplay != null) {
            mVirtualDisplay.release();
            mVirtualDisplay = null;
        }

        if (mMediaProjection != null) {
            try {
                mMediaProjection.unregisterCallback(mMediaProjectionCallback);
            } catch (Exception ignored) {}
            mMediaProjection.stop();
            mMediaProjection = null;
        }

        if (mGLWrapper != null) {
            mGLWrapper.stop();
            mGLWrapper = null;
        }

        if (mVideoEncoder != null) {
            mVideoEncoder.release();
            mVideoEncoder = null;
        }

        if (mMuxerWrapper != null) {
            mMuxerWrapper.stop();
            mMuxerWrapper = null;
        }

        if (mCurrentSegmentFile != null && mCurrentSegmentFile.exists() && mCurrentSegmentFile.length() > 0) {
            mSegmentFiles.add(mCurrentSegmentFile);
            Log.d(TAG, "Segment " + mSegmentFiles.size() + " finalized: " + mCurrentSegmentFile.getAbsolutePath() + " (" + mCurrentSegmentFile.length() + " bytes)");
            mCurrentSegmentFile = null;
        }
    }

    private void updateNotificationForPaused() {
        Intent resumeIntent = new Intent(this, PermissionProxyActivity.class);
        resumeIntent.putExtra(PermissionProxyActivity.EXTRA_IS_RESUME, true);
        resumeIntent.putExtra(EXTRA_AUDIO_MODE, mCurrentAudioMode);
        resumeIntent.putExtra(EXTRA_AREA_MODE, mCurrentAreaMode);
        resumeIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        PendingIntent pResume = PendingIntent.getActivity(
                this,
                101,
                resumeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent stopIntent = new Intent(this, RecordingService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent pStop = PendingIntent.getService(
                this,
                102,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_recording_notification_scaled)
                .setColor(0xFFE53935)
                .setContentTitle("Screen recording paused")
                .setContentText("Screen was locked. Tap to resume recording.")
                .setOngoing(true)
                .setContentIntent(pResume)
                .addAction(R.drawable.ic_resume, "Resume", pResume)
                .addAction(R.drawable.ic_stop_recording, "Stop & Save", pStop)
                .build();

        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, notification);
        }
    }

    private boolean startForegroundService(String audioMode) {
        createNotificationChannel();
        
        Intent stopIntent = new Intent(this, RecordingService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent pStopIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE);

        android.widget.RemoteViews customView = new android.widget.RemoteViews(getPackageName(), R.layout.notification_recording);
        long chronometerBase = SystemClock.elapsedRealtime() - mActiveRecordingDurationMs;
        customView.setChronometer(R.id.chronometer_timer, chronometerBase, null, true);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_recording_notification_scaled)
                .setColor(0xFFE53935)
                .setStyle(new NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(customView)
                .setOngoing(true)
                .setShowWhen(true)
                .setContentIntent(pStopIntent)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            boolean needsMicrophone = "Media and mic".equals(audioMode) || "Mic".equals(audioMode);
            int foregroundServiceType = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            
            boolean hasRecordAudio = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED;
            
            if (needsMicrophone) {
                if (!hasRecordAudio) {
                    Log.e(TAG, "MICROPHONE_RECORDING_REQUESTED_BUT_RECORD_AUDIO_NOT_GRANTED");
                    return false;
                }
                foregroundServiceType |= android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            }
            
            startForeground(NOTIFICATION_ID, notification, foregroundServiceType);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        
        return true;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                NotificationChannel existingChannel = manager.getNotificationChannel(CHANNEL_ID);
                if (existingChannel == null) {
                    NotificationChannel channel = new NotificationChannel(
                            CHANNEL_ID,
                            "Screen recorder",
                            NotificationManager.IMPORTANCE_LOW
                    );
                    manager.createNotificationChannel(channel);
                }
            }
        }
    }

    private synchronized void stopRecordingSession() {
        if (mUserRequestedStop) return;
        mUserRequestedStop = true;
        mWaitingForReauthorization = false;
        sReauthorizationProxyActive = false;
        mActiveRecordingDurationMs = 0;
        mCurrentSegmentStartTimeMs = 0;

        Log.d(TAG, "Stopping recording session. Segments recorded so far: " + mSegmentFiles.size() + ", isRecording=" + mIsRecording);

        if (mIsRecording) {
            mIsRecording = false;
            finalizeActiveSegment();
        } else {
            finalizeActiveSegment();
        }

        SSRStateManager.getInstance().setRecording(false);
        broadcastRecordingState(false);

        if (mFloatingRecorderControl != null) {
            final FloatingRecorderControl controlToHide = mFloatingRecorderControl;
            mFloatingRecorderControl = null;
            new Handler(Looper.getMainLooper()).post(controlToHide::hide);
        }

        if (mPartialScreenOverlay != null) {
            final PartialScreenOverlay overlayToHide = mPartialScreenOverlay;
            mPartialScreenOverlay = null;
            new Handler(Looper.getMainLooper()).post(overlayToHide::hide);
        }

        if (mWakeLock != null) {
            try {
                if (mWakeLock.isHeld()) {
                    mWakeLock.release();
                    Log.d(TAG, "Released PARTIAL_WAKE_LOCK");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error releasing wake lock", e);
            }
            mWakeLock = null;
        }

        if (mScreenReceiverRegistered) {
            try {
                unregisterReceiver(mScreenStateReceiver);
            } catch (Exception ignored) {}
            mScreenReceiverRegistered = false;
        }
        mAutoPausedByScreenOff = false;

        stopForeground(true);

        final List<File> segmentsToMerge = new ArrayList<>(mSegmentFiles);
        final String finalPath = mFinalOutputFile;
        mSegmentFiles.clear();

        new Thread(() -> {
            try {
                if (!segmentsToMerge.isEmpty() && finalPath != null) {
                    Log.d(TAG, "Merging " + segmentsToMerge.size() + " segments into: " + finalPath);
                    boolean success = Mp4Merger.merge(segmentsToMerge, finalPath);
                    Log.d(TAG, "Mp4Merger success=" + success);
                    if (success) {
                        MediaScannerConnection.scanFile(
                                getApplicationContext(),
                                new String[]{finalPath},
                                new String[]{"video/mp4"},
                                (path, uri) -> Log.d(TAG, "MediaScanner scanned: " + path + " -> " + uri)
                        );
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error merging segments", e);
            } finally {
                new Handler(Looper.getMainLooper()).post(() -> {
                    try {
                        Toast.makeText(getApplicationContext(), "Recording saved.", Toast.LENGTH_LONG).show();
                    } catch (Exception ignored) {}
                    stopSelf();
                });
            }
        }, "SSR-MergeThread").start();
    }

    /**
     * Fires a local broadcast so the QS tile BroadcastReceiver can
     * update the tile icon/state in real time.
     * Mirrors Samsung's RecordingThreadService notification pattern.
     */
    private void broadcastRecordingState(boolean isRecording) {
        Intent intent = new Intent(ScreenRecorderTileService.ACTION_RECORDING_STATE_CHANGED);
        intent.putExtra(ScreenRecorderTileService.EXTRA_IS_RECORDING, isRecording);
        sendBroadcast(intent);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopRecordingSession();
    }
}
