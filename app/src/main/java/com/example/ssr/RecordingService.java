package com.example.ssr;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class RecordingService extends Service {
    private static final String TAG = "RecordingService";
    public static final String ACTION_START = "com.example.ssr.ACTION_START";
    public static final String ACTION_STOP = "com.example.ssr.ACTION_STOP";
    public static final String ACTION_PAUSE = "com.example.ssr.ACTION_PAUSE";
    public static final String ACTION_RESUME = "com.example.ssr.ACTION_RESUME";
    public static final String EXTRA_RESULT_CODE = "result_code";
    public static final String EXTRA_RESULT_DATA = "result_data";
    public static final String EXTRA_AUDIO_MODE = "audio_mode"; // "None", "Media", "Media and mic"
    public static final String EXTRA_AREA_MODE = "area_mode"; // "Full screen", "Partial screen"


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
    private Thread mVideoThread;
    private PartialScreenOverlay mPartialScreenOverlay;

    private final MediaProjection.Callback mMediaProjectionCallback = new MediaProjection.Callback() {
        @Override
        public void onStop() {
            Log.d(TAG, "MediaProjection stopped");

            if (mIsRecording) {
                stopRecording();
            }
        }
    };

    private int mScreenWidth;
    private int mScreenHeight;
    private int mScreenDensity;
    private String mOutputFile;

    @Override
    public void onCreate() {
        super.onCreate();
        mProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        
        WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics metrics = new DisplayMetrics();
        wm.getDefaultDisplay().getMetrics(metrics);
        mScreenDensity = metrics.densityDpi;
        mScreenWidth = metrics.widthPixels;
        mScreenHeight = metrics.heightPixels;
        
        // Ensure even dimensions for video codec
        if (mScreenWidth % 2 != 0) mScreenWidth--;
        if (mScreenHeight % 2 != 0) mScreenHeight--;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;

        String action = intent.getAction();
        if (ACTION_START.equals(action)) {
            if (!mIsRecording) {
                startRecording(intent);
            }
        } else if (ACTION_STOP.equals(action)) {
            if (mIsRecording || mPartialScreenOverlay != null) {
                stopRecording();
            }
        } else if (ACTION_PAUSE.equals(action)) {
            if (mIsRecording && !mIsPaused) {
                pauseRecording();
            }
        } else if (ACTION_RESUME.equals(action)) {
            if (mIsRecording && mIsPaused) {
                resumeRecording();
            }
        }

        return START_NOT_STICKY;
    }

    private void startRecording(Intent intent) {
        int resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0); // Default to 0, since RESULT_OK is -1
        Intent resultData = intent.getParcelableExtra(EXTRA_RESULT_DATA);
        String audioMode = intent.getStringExtra(EXTRA_AUDIO_MODE);
        
        boolean needsMic = "Media and mic".equals(audioMode) || "Mic".equals(audioMode);
        if (needsMic && androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission is missing! Cannot start microphone recording.");
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
            
            android.graphics.Rect cropRect = intent.getParcelableExtra("crop_rect");
            int areaMode = intent.getIntExtra(EXTRA_AREA_MODE, 0);

            if (areaMode == 1 && cropRect != null) {
                // Partial screen mode: show overlay and wait for countdown
                mPartialScreenOverlay = new PartialScreenOverlay(this, cropRect, new PartialScreenOverlay.Callback() {
                    @Override
                    public void onCountdownFinished() {
                        startRecordingInternal(intent);
                        if (mPartialScreenOverlay != null) {
                            mPartialScreenOverlay.startRecordingTimer();
                        }
                    }
                });
                mPartialScreenOverlay.showCountdown();
            } else {
                // Full screen mode: countdown already done, start immediately
                startRecordingInternal(intent);
            }
        } else {
            Log.e(TAG, "Result code or data missing");
            stopSelf();
        }
    }

    private void startRecordingInternal(Intent intent) {
        try {
            android.graphics.Rect cropRect = intent.getParcelableExtra("crop_rect");
            int areaMode = intent.getIntExtra(EXTRA_AREA_MODE, 0);

            mIsRecording = true;
            SSRStateManager.getInstance().setRecording(true);
            setupEncoders(intent);
            showFloatingStopButton(areaMode == 1 ? cropRect : null);
            broadcastRecordingState(true);
            Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "FAILED TO START RECORDING", e);
            Log.e(TAG, "Exception type: " + e.getClass().getName());
            Log.e(TAG, "Exception message: " + e.getMessage());
            for (StackTraceElement element : e.getStackTrace()) {
                Log.e(TAG, "    at " + element.toString());
            }
            stopRecording();
        }
    }


    private void setupEncoders(Intent intent) throws IOException {
        Log.d(TAG, "START_SETUP_ENCODERS");
        String audioMode = intent.getStringExtra(EXTRA_AUDIO_MODE);
        android.graphics.Rect cropRect = intent.getParcelableExtra("crop_rect");
        
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Screen Recordings");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        mOutputFile = new File(dir, "Screen_Recording_" + timeStamp + ".mp4").getAbsolutePath();

        boolean hasAudio = !"None".equals(audioMode);
        int expectedTracks = hasAudio ? 2 : 1;
        
        mMuxerWrapper = new MuxerWrapper(mOutputFile, expectedTracks);
        
        int encWidth = mScreenWidth;
        int encHeight = mScreenHeight;
        if (cropRect != null) {
            encWidth = cropRect.width();
            encHeight = cropRect.height();
            // Hardware encoders (like MTK/Exynos) strictly require dimensions to be multiples of 16
            encWidth = encWidth - (encWidth % 16);
            encHeight = encHeight - (encHeight % 16);
            // Ensure a reasonable minimum size
            if (encWidth < 128) encWidth = 128;
            if (encHeight < 128) encHeight = 128;
            
            // Adjust cropRect to match the final encoder dimensions to avoid distortion
            cropRect.right = cropRect.left + encWidth;
            cropRect.bottom = cropRect.top + encHeight;
        }

        // Video
        int bitRate = 6000000; // 6 Mbps
        mVideoEncoder = new VideoEncoderCore(encWidth, encHeight, bitRate, mMuxerWrapper);
        Log.d(TAG, "VIDEO_ENCODER_CREATED");
        Log.d(TAG, "VIDEO_ENCODER_STARTED"); // Internal to VideoEncoderCore but logged here
        
        android.view.Surface encoderSurface = mVideoEncoder.getInputSurface();
        Log.d(TAG, "VIDEO_SURFACE_CREATED");
        android.view.Surface virtualDisplaySurface;
        
        if (cropRect != null) {
            mGLWrapper = new GLRenderWrapper(encoderSurface, encWidth, encHeight, mScreenWidth, mScreenHeight, cropRect);
            mGLWrapper.start();
            Log.d(TAG, "GL_WRAPPER_STARTED");
            virtualDisplaySurface = mGLWrapper.getInputSurface();
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
                    mVideoEncoder.drainEncoder(false);
                    Thread.sleep(10);
                }
                mVideoEncoder.drainEncoder(true);
            } catch (Exception e) {
                Log.e(TAG, "Video encoder thread error", e);
            }
        }, "VideoDrainThread");
        mVideoThread.start();
        Log.d(TAG, "VIDEO_THREAD_STARTED");
        Log.d(TAG, "SETUP_ENCODERS_COMPLETE");
    }

    private void showFloatingStopButton(android.graphics.Rect cropRect) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                mFloatingRecorderControl = new FloatingRecorderControl(this, cropRect);
                mFloatingRecorderControl.show();
            } catch (Exception e) {
                Log.e(TAG, "Failed to show floating stop button", e);
            }
        });
    }

    private void pauseRecording() {
        mIsPaused = true;
        if (mVideoEncoder != null) {
            mVideoEncoder.setPaused(true);
        }
        if (mAudioEncoder != null) {
            mAudioEncoder.setPaused(true);
        }
        if (mPartialScreenOverlay != null) {
            mPartialScreenOverlay.pauseTimer();
        }
        Toast.makeText(this, "Recording paused", Toast.LENGTH_SHORT).show();
    }

    private void resumeRecording() {
        mIsPaused = false;
        if (mVideoEncoder != null) {
            mVideoEncoder.setPaused(false);
        }
        if (mAudioEncoder != null) {
            mAudioEncoder.setPaused(false);
        }
        if (mPartialScreenOverlay != null) {
            mPartialScreenOverlay.resumeTimer();
        }
        Toast.makeText(this, "Recording resumed", Toast.LENGTH_SHORT).show();
    }

    private boolean startForegroundService(String audioMode) {
        createNotificationChannel();
        
        boolean notificationsEnabled = androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled();
        boolean postNotificationsGranted = Build.VERSION.SDK_INT >= 33 
                ? androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED 
                : true;

        Log.d(TAG, "NOTIFICATION_ENABLED=" + notificationsEnabled);
        Log.d(TAG, "POST_NOTIFICATIONS_GRANTED=" + postNotificationsGranted);

        if (!notificationsEnabled) {
            Log.w(TAG, "NOTIFICATION_BLOCKED_BY_USER=true");
        }

        Intent stopIntent = new Intent(this, RecordingService.class);
        stopIntent.setAction(ACTION_STOP);
        PendingIntent pStopIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE);

        android.widget.RemoteViews customView = new android.widget.RemoteViews(getPackageName(), R.layout.notification_recording);
        customView.setChronometer(R.id.chronometer_timer, android.os.SystemClock.elapsedRealtime(), null, true);

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
            
            Log.d(TAG, "RECORDING_AUDIO_MODE=" + audioMode);
            Log.d(TAG, "RECORD_AUDIO_PERMISSION=" + hasRecordAudio);
            Log.d(TAG, "MICROPHONE_FGS_ENABLED=" + needsMicrophone);
            Log.d(TAG, "FGS_TYPE=" + foregroundServiceType);
            Log.d(TAG, "START_FOREGROUND_NOTIFICATION");
            startForeground(NOTIFICATION_ID, notification, foregroundServiceType);
            Log.d(TAG, "START_FOREGROUND_NOTIFICATION_COMPLETE");
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Log.d(TAG, "START_FOREGROUND_NOTIFICATION");
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            Log.d(TAG, "START_FOREGROUND_NOTIFICATION_COMPLETE");
        } else {
            Log.d(TAG, "START_FOREGROUND_NOTIFICATION");
            startForeground(NOTIFICATION_ID, notification);
            Log.d(TAG, "START_FOREGROUND_NOTIFICATION_COMPLETE");
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
                    existingChannel = channel;
                }
                
                Log.d(TAG, "NOTIFICATION_CHANNEL_ID=" + existingChannel.getId());
                Log.d(TAG, "NOTIFICATION_CHANNEL_EXISTS=true");
                Log.d(TAG, "NOTIFICATION_CHANNEL_IMPORTANCE=" + existingChannel.getImportance());
            }
        }
    }

    private void stopRecording() {
        mIsRecording = false;
        SSRStateManager.getInstance().setRecording(false);
        // Notify QS tile so it flips back to INACTIVE (Samsung pattern)
        broadcastRecordingState(false);



        if (mFloatingRecorderControl != null) {
            new Handler(Looper.getMainLooper()).post(() -> mFloatingRecorderControl.hide());
            mFloatingRecorderControl = null;
        }

        if (mPartialScreenOverlay != null) {
            new Handler(Looper.getMainLooper()).post(() -> mPartialScreenOverlay.hide());
            mPartialScreenOverlay = null;
        }

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
            } catch (Exception e) {
                Log.e(TAG, "Failed to unregister MediaProjection callback", e);
            }
            mMediaProjection.stop();
            mMediaProjection = null;
        }

        if (mGLWrapper != null) {
            mGLWrapper.stop();
            mGLWrapper = null;
        }

        if (mVideoEncoder != null) {
            // video encoder drained via its thread
            mVideoEncoder.release();
            mVideoEncoder = null;
        }

        if (mMuxerWrapper != null) {
            mMuxerWrapper.stop();
            mMuxerWrapper = null;
        }

        stopForeground(true);
        stopSelf();

        Toast.makeText(this, "Recording saved.", Toast.LENGTH_LONG).show();
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
        stopRecording();
    }
}
