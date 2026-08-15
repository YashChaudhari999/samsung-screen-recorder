package com.example.ssr;

import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

public class CountdownOverlay {
    
    private final Context mContext;
    private final Intent mServiceIntent;
    private final WindowManager mWindowManager;
    private final Runnable mOnComplete;
    private TextView mTvCountdown;
    private int mCount = 3;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    
    public CountdownOverlay(Context context, Intent serviceIntent, Runnable onComplete) {
        mContext = context.getApplicationContext();
        mServiceIntent = serviceIntent;
        mOnComplete = onComplete;
        mWindowManager = (WindowManager) mContext.getSystemService(Context.WINDOW_SERVICE);
    }
    
    public void show() {
        if (!android.provider.Settings.canDrawOverlays(mContext)) {
            startService();
            return;
        }

        mTvCountdown = new TextView(mContext);
        mTvCountdown.setTextColor(Color.WHITE);
        mTvCountdown.setTextSize(60);
        mTvCountdown.setTypeface(null, Typeface.BOLD);
        mTvCountdown.setGravity(Gravity.CENTER);
        
        // Circular dark background (Samsung style)
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        gd.setColor(Color.parseColor("#80000000")); // 50% black
        mTvCountdown.setBackground(gd);

        int layoutFlag;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutFlag = WindowManager.LayoutParams.TYPE_PHONE;
        }

        int size = 200; // dp or px? let's use px for now, maybe 300px
        float density = mContext.getResources().getDisplayMetrics().density;
        int sizePx = (int) (100 * density);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                sizePx, sizePx,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.CENTER;

        mWindowManager.addView(mTvCountdown, params);
        
        startCountdown();
    }
    
    private void startCountdown() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mCount > 0) {
                    mTvCountdown.setText(String.valueOf(mCount));
                    
                    // Pop animation
                    mTvCountdown.setScaleX(0.5f);
                    mTvCountdown.setScaleY(0.5f);
                    mTvCountdown.setAlpha(0f);
                    mTvCountdown.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .alpha(1f)
                            .setDuration(400)
                            .setInterpolator(new android.view.animation.OvershootInterpolator())
                            .start();

                    mCount--;
                    mHandler.postDelayed(this, 1000);
                } else {
                    if (mTvCountdown != null && mTvCountdown.getParent() != null) {
                        mWindowManager.removeView(mTvCountdown);
                    }
                    startService();
                    if (mOnComplete != null) {
                        mOnComplete.run();
                    }
                }
            }
        });
    }
    
    private void startService() {
        if (mServiceIntent != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                mContext.startForegroundService(mServiceIntent);
            } else {
                mContext.startService(mServiceIntent);
            }
        }
    }
}
