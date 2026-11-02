package com.example.ssr;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.os.Build;
import android.view.View;
import android.view.WindowManager;

/**
 * PartialScreenOverlay
 *
 * System pass-through overlay (TYPE_APPLICATION_OVERLAY) displayed during partial-screen recording.
 * Dims the non-recorded area of the screen with a clean translucent grey mask and outlines
 * the recorded region with a subtle white boundary, while allowing all user touch gestures
 * to pass through freely to underlying applications.
 */
public class PartialScreenOverlay {

    public interface Callback {
        void onCountdownFinished();
    }

    private final Context mContext;
    private final WindowManager mWindowManager;
    private final Rect mCropRect;
    private View mDimView;

    public PartialScreenOverlay(Context context, Rect cropRect) {
        mContext = context;
        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        mCropRect = cropRect;
    }

    public PartialScreenOverlay(Context context, Rect cropRect, Callback callback) {
        this(context, cropRect);
    }

    public void showDimOverlay() {
        if (mDimView != null) return;

        int layoutFlag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams dimParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE |
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            dimParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        mDimView = new View(mContext) {
            private final Paint mDimPaint = new Paint();
            private final Paint mClearPaint = new Paint();
            private final Paint mBorderPaint = new Paint();

            {
                // Clean semi-transparent dark grey dimming for unrecorded screen area
                mDimPaint.setColor(Color.parseColor("#80000000"));
                mClearPaint.setColor(Color.TRANSPARENT);
                mClearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
                mBorderPaint.setColor(Color.WHITE);
                mBorderPaint.setStyle(Paint.Style.STROKE);
                mBorderPaint.setStrokeWidth(3);
                mBorderPaint.setAntiAlias(true);
            }

            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                if (mCropRect == null) return;
                // 1. Draw dim over entire screen
                canvas.drawRect(0, 0, getWidth(), getHeight(), mDimPaint);
                // 2. Clear the active recording crop area
                canvas.drawRect(mCropRect, mClearPaint);
                // 3. Draw a subtle boundary line along the recording border
                Rect borderRect = new Rect(mCropRect);
                borderRect.inset(-1, -1);
                canvas.drawRect(borderRect, mBorderPaint);
            }
        };

        mDimView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        mDimView.setLayerType(View.LAYER_TYPE_SOFTWARE, null);

        try {
            mWindowManager.addView(mDimView, dimParams);
        } catch (Exception ignored) {}
    }

    public void pauseTimer() {}
    public void resumeTimer() {}

    public void hide() {
        if (mDimView != null) {
            try {
                mWindowManager.removeView(mDimView);
            } catch (Exception ignored) {}
            mDimView = null;
        }
    }
}
