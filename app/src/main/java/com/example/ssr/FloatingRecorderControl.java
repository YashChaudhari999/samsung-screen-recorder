package com.example.ssr;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

public class FloatingRecorderControl {

    private enum UIState {
        EXPANDED, COLLAPSING, EDGE_COLLAPSED, EXPANDING
    }

    private final Context mContext;
    private final WindowManager mWindowManager;
    private View mFloatingView;
    private WindowManager.LayoutParams mParams;

    private View mControlPill;
    private ImageView mBtnCollapseLeft;
    private ImageView mBtnCollapseRight;
    private LinearLayout mExpandedContainer;
    private ImageView mBtnPauseResume;
    private ImageView mBtnStop;
    private TextView mTvTimer;

    private UIState mState = UIState.EXPANDED;
    private boolean mIsRecordingPaused = false;
    private boolean mIsAttachedToRightEdge = true; 

    private long mActiveRecordingDurationMs = 0;
    private long mCurrentPeriodStartTime = 0;
    private final Handler mTimerHandler = new Handler(Looper.getMainLooper());
    
    private int mScreenWidth;
    private int mScreenHeight;
    private int mFullExpandedContainerWidth = 0;
    private ValueAnimator mCurrentAnimator;

    private android.graphics.Rect mCropRect;

    public FloatingRecorderControl(Context context, android.graphics.Rect cropRect) {
        mContext = context;
        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        mCropRect = cropRect;
    }

    private void updateScreenDimensions() {
        DisplayMetrics metrics = new DisplayMetrics();
        mWindowManager.getDefaultDisplay().getRealMetrics(metrics);
        mScreenWidth = metrics.widthPixels;
        mScreenHeight = metrics.heightPixels;
    }

    public void show() {
        if (mFloatingView != null) return;

        mFloatingView = LayoutInflater.from(mContext).inflate(R.layout.layout_floating_recorder_control, null);
        
        mControlPill = mFloatingView.findViewById(R.id.control_pill);
        mBtnCollapseLeft = mFloatingView.findViewById(R.id.btn_collapse_left);
        mBtnCollapseRight = mFloatingView.findViewById(R.id.btn_collapse_right);
        mExpandedContainer = mFloatingView.findViewById(R.id.expanded_container);
        mBtnPauseResume = mFloatingView.findViewById(R.id.btn_pause_resume);
        mBtnStop = mFloatingView.findViewById(R.id.btn_stop);
        mTvTimer = mFloatingView.findViewById(R.id.tv_timer);

        int layoutFlag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O 
            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY 
            : WindowManager.LayoutParams.TYPE_PHONE;

        mParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);

        mParams.gravity = Gravity.TOP | Gravity.START;
        updateScreenDimensions();
        
        // Measure to get the full width
        mFloatingView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        mFullExpandedContainerWidth = mExpandedContainer.getMeasuredWidth();
        int initialWindowWidth = mFloatingView.getMeasuredWidth();
        
        if (mCropRect != null) {
            mParams.x = mCropRect.centerX() - (initialWindowWidth / 2);
            mParams.y = mCropRect.bottom + 20;
            
            // Adjust if out of bounds
            if (mParams.y + mFloatingView.getMeasuredHeight() > mScreenHeight) {
                mParams.y = mCropRect.top - mFloatingView.getMeasuredHeight() - 20;
            }
            if (mParams.x < 0) mParams.x = 16;
            if (mParams.x + initialWindowWidth > mScreenWidth) {
                mParams.x = mScreenWidth - initialWindowWidth - 16;
            }
            int centerScreenX = mScreenWidth / 2;
            mIsAttachedToRightEdge = mParams.x > centerScreenX;
        } else {
            mParams.x = mScreenWidth - initialWindowWidth - 16; 
            mParams.y = mScreenHeight / 2 - 100;
        }

        // Initialize UI based on position
        if (mIsAttachedToRightEdge) {
            mBtnCollapseRight.setVisibility(View.GONE);
            mBtnCollapseLeft.setVisibility(View.VISIBLE);
            mBtnCollapseLeft.setImageResource(R.drawable.ic_collapse_right);
        } else {
            mBtnCollapseLeft.setVisibility(View.GONE);
            mBtnCollapseRight.setVisibility(View.VISIBLE);
            mBtnCollapseRight.setImageResource(R.drawable.ic_collapse_left);
        }

        setupInteractions();
        mWindowManager.addView(mFloatingView, mParams);
        
        startTimer();
    }

    private void setupInteractions() {
        mBtnStop.setOnClickListener(v -> sendActionToService(RecordingService.ACTION_STOP));

        mBtnPauseResume.setOnClickListener(v -> {
            if (mState != UIState.EXPANDED) return;
            if (mIsRecordingPaused) {
                sendActionToService(RecordingService.ACTION_RESUME);
                mIsRecordingPaused = false;
                mBtnPauseResume.setImageResource(R.drawable.ic_pause);
                mCurrentPeriodStartTime = SystemClock.elapsedRealtime();
                mTimerHandler.post(mTimerRunnable);
            } else {
                sendActionToService(RecordingService.ACTION_PAUSE);
                mIsRecordingPaused = true;
                mBtnPauseResume.setImageResource(R.drawable.ic_resume);
                long now = SystemClock.elapsedRealtime();
                mActiveRecordingDurationMs += (now - mCurrentPeriodStartTime);
                mTimerHandler.removeCallbacks(mTimerRunnable);
                updateTimerUI(mActiveRecordingDurationMs);
            }
        });

        View.OnClickListener collapseListener = v -> {
            if (mState == UIState.EXPANDED) {
                collapseToEdge();
            } else if (mState == UIState.EDGE_COLLAPSED) {
                expandFromEdge();
            }
        };
        mBtnCollapseLeft.setOnClickListener(collapseListener);
        mBtnCollapseRight.setOnClickListener(collapseListener);

        GestureDetector gestureDetector = new GestureDetector(mContext, new GestureDetector.SimpleOnGestureListener() {
            private int initialX, initialY;
            private boolean isDragging = false;

            @Override
            public boolean onDown(MotionEvent e) {
                initialX = mParams.x;
                initialY = mParams.y;
                isDragging = false;
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                if (mState == UIState.EXPANDED) {
                    updateScreenDimensions();
                    isDragging = true;
                    int dx = (int) (e2.getRawX() - e1.getRawX());
                    int dy = (int) (e2.getRawY() - e1.getRawY());
                    mParams.x = initialX + dx;
                    mParams.y = initialY + dy;
                    
                    int windowWidth = mFloatingView.getWidth();
                    int windowHeight = mFloatingView.getHeight();
                    if (mParams.x < 0) mParams.x = 0;
                    if (mParams.x > mScreenWidth - windowWidth) mParams.x = mScreenWidth - windowWidth;
                    if (mParams.y < 0) mParams.y = 0;
                    if (mParams.y > mScreenHeight - windowHeight) mParams.y = mScreenHeight - windowHeight;
                    
                    mWindowManager.updateViewLayout(mFloatingView, mParams);
                    return true;
                }
                return false;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                if (mState == UIState.EDGE_COLLAPSED) {
                    float dx = e2.getRawX() - e1.getRawX();
                    if (mIsAttachedToRightEdge && dx < -50) { 
                        expandFromEdge();
                        return true;
                    } else if (!mIsAttachedToRightEdge && dx > 50) { 
                        expandFromEdge();
                        return true;
                    }
                }
                return false;
            }
        });

        mFloatingView.setOnTouchListener((v, event) -> {
            boolean handled = gestureDetector.onTouchEvent(event);
            return handled;
        });
    }

    private void collapseToEdge() {
        if (mState != UIState.EXPANDED && mState != UIState.EXPANDING) return;
        if (mCurrentAnimator != null) mCurrentAnimator.cancel();
        
        mState = UIState.COLLAPSING;
        updateScreenDimensions();

        int currentX = mParams.x;
        int centerScreenX = mScreenWidth / 2;
        mIsAttachedToRightEdge = currentX > centerScreenX;
        
        mFloatingView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int currentWindowWidth = mFloatingView.getMeasuredWidth();
        
        int targetX;
        if (mIsAttachedToRightEdge) {
            // Setup for right collapse:
            // [expandedContainer] [btn_collapse_right]
            mBtnCollapseLeft.setVisibility(View.GONE);
            mBtnCollapseRight.setVisibility(View.VISIBLE);
            mBtnCollapseRight.setImageResource(R.drawable.ic_collapse_right); // Outward
            targetX = mScreenWidth - mBtnCollapseRight.getMeasuredWidth() - 16;
        } else {
            // Setup for left collapse:
            // [btn_collapse_left] [expandedContainer]
            mBtnCollapseRight.setVisibility(View.GONE);
            mBtnCollapseLeft.setVisibility(View.VISIBLE);
            mBtnCollapseLeft.setImageResource(R.drawable.ic_collapse_left); // Outward
            targetX = 0;
        }
        
        // Hide TV Timer to save vertical space
        mTvTimer.setVisibility(View.GONE);

        // Animate expandedContainer width to 0
        ViewGroup.LayoutParams lp = mExpandedContainer.getLayoutParams();
        int startWidth = lp.width >= 0 ? lp.width : mFullExpandedContainerWidth;
        
        mCurrentAnimator = ValueAnimator.ofFloat(0f, 1f);
        mCurrentAnimator.setDuration(300);
        mCurrentAnimator.setInterpolator(new DecelerateInterpolator());
        mCurrentAnimator.addUpdateListener(animation -> {
            float fraction = animation.getAnimatedFraction();
            
            // Shrink container
            lp.width = (int) (startWidth * (1 - fraction));
            mExpandedContainer.setLayoutParams(lp);
            
            // Slide window safely
            mParams.x = (int) (currentX + (targetX - currentX) * fraction);
            mWindowManager.updateViewLayout(mFloatingView, mParams);
        });
        mCurrentAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mState = UIState.EDGE_COLLAPSED;
                // Final bounds check
                mFloatingView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
                int finalWidth = mFloatingView.getMeasuredWidth();
                if (mParams.x < 0) mParams.x = 0;
                if (mParams.x + finalWidth > mScreenWidth) mParams.x = mScreenWidth - finalWidth;
                mWindowManager.updateViewLayout(mFloatingView, mParams);
            }
        });
        mCurrentAnimator.start();
    }

    private void expandFromEdge() {
        if (mState != UIState.EDGE_COLLAPSED && mState != UIState.COLLAPSING) return;
        if (mCurrentAnimator != null) mCurrentAnimator.cancel();
        
        mState = UIState.EXPANDING;
        updateScreenDimensions();
        
        mTvTimer.setVisibility(View.VISIBLE);

        int currentX = mParams.x;
        int targetX;

        if (mIsAttachedToRightEdge) {
            // Expand [expandedContainer] [btn_collapse_right] -> then switch to [btn_collapse_left] [expandedContainer]
            // Actually, keep [btn_collapse_left] [expandedContainer] and animate its width!
            mBtnCollapseRight.setVisibility(View.GONE);
            mBtnCollapseLeft.setVisibility(View.VISIBLE);
            mBtnCollapseLeft.setImageResource(R.drawable.ic_collapse_right); // Inward facing
            
            // Target X is screen width minus full window width
            mFloatingView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
            targetX = mScreenWidth - (mBtnCollapseLeft.getMeasuredWidth() + mFullExpandedContainerWidth + 32); 
        } else {
            mBtnCollapseRight.setVisibility(View.GONE);
            mBtnCollapseLeft.setVisibility(View.VISIBLE);
            mBtnCollapseLeft.setImageResource(R.drawable.ic_collapse_left); // Inward facing
            targetX = 16;
        }

        ViewGroup.LayoutParams lp = mExpandedContainer.getLayoutParams();
        int startWidth = lp.width >= 0 ? lp.width : 0;
        
        mCurrentAnimator = ValueAnimator.ofFloat(0f, 1f);
        mCurrentAnimator.setDuration(300);
        mCurrentAnimator.setInterpolator(new DecelerateInterpolator());
        mCurrentAnimator.addUpdateListener(animation -> {
            float fraction = animation.getAnimatedFraction();
            
            // Grow container
            lp.width = startWidth + (int) ((mFullExpandedContainerWidth - startWidth) * fraction);
            mExpandedContainer.setLayoutParams(lp);
            
            // Slide window
            mParams.x = (int) (currentX + (targetX - currentX) * fraction);
            mWindowManager.updateViewLayout(mFloatingView, mParams);
        });
        mCurrentAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                mState = UIState.EXPANDED;
                lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
                mExpandedContainer.setLayoutParams(lp);
                
                // Final bounds check
                mFloatingView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
                int finalWidth = mFloatingView.getMeasuredWidth();
                if (mParams.x < 0) mParams.x = 0;
                if (mParams.x + finalWidth > mScreenWidth) mParams.x = mScreenWidth - finalWidth;
                mWindowManager.updateViewLayout(mFloatingView, mParams);
            }
        });
        mCurrentAnimator.start();
    }

    private void sendActionToService(String action) {
        Intent intent = new Intent(mContext, RecordingService.class);
        intent.setAction(action);
        mContext.startService(intent);
    }

    private final Runnable mTimerRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mIsRecordingPaused) {
                long now = SystemClock.elapsedRealtime();
                long total = mActiveRecordingDurationMs + (now - mCurrentPeriodStartTime);
                updateTimerUI(total);
                mTimerHandler.postDelayed(this, 1000);
            }
        }
    };

    private void startTimer() {
        mCurrentPeriodStartTime = SystemClock.elapsedRealtime();
        mActiveRecordingDurationMs = 0;
        mTimerHandler.post(mTimerRunnable);
    }

    private void updateTimerUI(long totalMs) {
        int seconds = (int) (totalMs / 1000);
        int minutes = seconds / 60;
        seconds = seconds % 60;
        mTvTimer.setText(String.format("%02d:%02d", minutes, seconds));
    }

    public void hide() {
        if (mCurrentAnimator != null) mCurrentAnimator.cancel();
        mTimerHandler.removeCallbacks(mTimerRunnable);
        if (mFloatingView != null) {
            mWindowManager.removeView(mFloatingView);
            mFloatingView = null;
        }
    }
}
