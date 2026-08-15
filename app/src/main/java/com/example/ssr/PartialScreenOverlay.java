package com.example.ssr;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

public class PartialScreenOverlay {

    public interface Callback {
        void onCountdownFinished();
    }

    private final Context mContext;
    private final WindowManager mWindowManager;
    private final Rect mCropRect;
    private final Callback mCallback;

    private View mDimView;
    private WindowManager.LayoutParams mDimParams;

    private LinearLayout mUIContainer;
    private TextView mTvTimerOrCountdown;
    private WindowManager.LayoutParams mUIParams;

    private int mCount = 3;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mIsRecording = false;
    private boolean mIsPaused = false;
    private long mActiveDurationMs = 0;
    private long mCurrentPeriodStartTime = 0;

    public PartialScreenOverlay(Context context, Rect cropRect, Callback callback) {
        mContext = context;
        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        mCropRect = cropRect;
        mCallback = callback;
    }

    public void showCountdown() {
        int layoutFlag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        // 1. The Dim Window (Full screen, pass-through)
        mDimParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
                
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            mDimParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        mDimView = new View(mContext) {
            private final Paint mDimPaint = new Paint();
            private final Paint mClearPaint = new Paint();
            private final Paint mBorderPaint = new Paint();

            {
                mDimPaint.setColor(Color.parseColor("#99000000"));
                mClearPaint.setColor(Color.TRANSPARENT);
                mClearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
                mBorderPaint.setColor(Color.WHITE);
                mBorderPaint.setStyle(Paint.Style.STROKE);
                mBorderPaint.setStrokeWidth(4);
            }

            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                // Draw dim
                canvas.drawRect(0, 0, getWidth(), getHeight(), mDimPaint);
                // Clear center
                canvas.drawRect(mCropRect, mClearPaint);
                // Border slightly outside
                Rect borderRect = new Rect(mCropRect);
                borderRect.inset(-2, -2);
                canvas.drawRect(borderRect, mBorderPaint);
            }
        };
        mDimView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        mDimView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        mWindowManager.addView(mDimView, mDimParams);

        // 2. The UI Window (Small, inside the rect)
        mUIContainer = new LinearLayout(mContext);
        mUIContainer.setOrientation(LinearLayout.VERTICAL);
        mUIContainer.setGravity(Gravity.CENTER);

        mTvTimerOrCountdown = new TextView(mContext);
        mTvTimerOrCountdown.setTextColor(Color.WHITE);
        mTvTimerOrCountdown.setTextSize(40);
        mTvTimerOrCountdown.setTypeface(null, Typeface.BOLD);
        mTvTimerOrCountdown.setGravity(Gravity.CENTER);
        
        mUIContainer.addView(mTvTimerOrCountdown);

        mUIParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);
        
        mUIParams.gravity = Gravity.TOP | Gravity.START;
        
        // Center UI inside the crop rect
        mUIContainer.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int uiWidth = mUIContainer.getMeasuredWidth();
        int uiHeight = mUIContainer.getMeasuredHeight();
        
        mUIParams.x = mCropRect.centerX() - (uiWidth / 2);
        mUIParams.y = mCropRect.centerY() - (uiHeight / 2);

        mWindowManager.addView(mUIContainer, mUIParams);

        runCountdownTick();
    }

    private void runCountdownTick() {
        if (mIsRecording) return; // Already skipped

        if (mCount > 0) {
            mTvTimerOrCountdown.setText(String.valueOf(mCount));
            mTvTimerOrCountdown.setScaleX(0.5f);
            mTvTimerOrCountdown.setScaleY(0.5f);
            mTvTimerOrCountdown.setAlpha(0f);
            mTvTimerOrCountdown.animate()
                    .scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(400)
                    .setInterpolator(new android.view.animation.OvershootInterpolator())
                    .start();
            mCount--;
            mHandler.postDelayed(this::runCountdownTick, 1000);
        } else {
            finishCountdown();
        }
    }

    private void finishCountdown() {
        if (mIsRecording) return;
        mIsRecording = true;
        
        // Hide the entire UI container (countdown text and skip button)
        mUIContainer.setVisibility(View.GONE);

        if (mCallback != null) {
            mCallback.onCountdownFinished();
        }
    }

    public void startRecordingTimer() {
        mCurrentPeriodStartTime = SystemClock.elapsedRealtime();
        mActiveDurationMs = 0;
        mIsPaused = false;
        mHandler.post(mTimerRunnable);
    }

    public void pauseTimer() {
        mIsPaused = true;
        long now = SystemClock.elapsedRealtime();
        mActiveDurationMs += (now - mCurrentPeriodStartTime);
        mHandler.removeCallbacks(mTimerRunnable);
        updateTimerText(mActiveDurationMs);
    }

    public void resumeTimer() {
        mIsPaused = false;
        mCurrentPeriodStartTime = SystemClock.elapsedRealtime();
        mHandler.post(mTimerRunnable);
    }

    private final Runnable mTimerRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mIsPaused) {
                long now = SystemClock.elapsedRealtime();
                long total = mActiveDurationMs + (now - mCurrentPeriodStartTime);
                updateTimerText(total);
                mHandler.postDelayed(this, 1000);
            }
        }
    };

    private void updateTimerText(long totalMs) {
        int seconds = (int) (totalMs / 1000);
        int minutes = seconds / 60;
        seconds = seconds % 60;
        mTvTimerOrCountdown.setText(String.format("● %02d:%02d", minutes, seconds));
        // Use a slight red tint for recording, white for paused? 
        if (mIsPaused) {
            mTvTimerOrCountdown.setTextColor(Color.WHITE);
        } else {
            // Blink the dot
            if (seconds % 2 == 0) {
                mTvTimerOrCountdown.setText(String.format("● %02d:%02d", minutes, seconds));
            } else {
                mTvTimerOrCountdown.setText(String.format("○ %02d:%02d", minutes, seconds));
            }
        }
    }

    public void hide() {
        mHandler.removeCallbacksAndMessages(null);
        if (mDimView != null) {
            try { mWindowManager.removeView(mDimView); } catch (Exception ignored) {}
            mDimView = null;
        }
        if (mUIContainer != null) {
            try { mWindowManager.removeView(mUIContainer); } catch (Exception ignored) {}
            mUIContainer = null;
        }
    }
}
