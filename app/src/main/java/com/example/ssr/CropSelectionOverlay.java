package com.example.ssr;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;

/**
 * CropSelectionOverlay
 *
 * System window overlay (TYPE_APPLICATION_OVERLAY) for selecting the partial screen area.
 * Because it is a system overlay and not an Activity:
 * 1. It does NOT disappear when the user navigates to Settings or switches apps.
 * 2. It stays smoothly on top of all third-party apps so the user can accurately crop what they want.
 * 3. When "Record" is clicked:
 *    - It is IMMEDIATELY removed from WindowManager (so neither grid nor border appears in recording).
 *    - The confirmed Rect is provided to the callback.
 * 4. When "Close" (X) is clicked, it removes itself and cancels.
 */
public class CropSelectionOverlay {
    private static final String TAG = "CropSelectionOverlay";

    public interface Callback {
        void onCropConfirmed(Rect cropRect);
        void onCropCancelled();
    }

    private final Context mContext;
    private final WindowManager mWindowManager;
    private final Callback mCallback;

    private View mRootView;
    private CropOverlayView mCropView;
    private View mControlsLayout;
    private View mBtnRecord;
    private View mBtnClose;
    private WindowManager.LayoutParams mParams;
    private boolean mDismissed = false;

    public CropSelectionOverlay(Context context, Callback callback) {
        mContext = context;
        mWindowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        mCallback = callback;
    }

    public void show() {
        if (mRootView != null) return;

        mRootView = LayoutInflater.from(mContext).inflate(R.layout.activity_crop, null);
        mCropView = mRootView.findViewById(R.id.crop_view);
        mControlsLayout = mRootView.findViewById(R.id.ll_controls);
        mBtnRecord = mRootView.findViewById(R.id.btn_record_crop);
        mBtnClose = mRootView.findViewById(R.id.btn_close_crop);

        View countdownContainer = mRootView.findViewById(R.id.fl_countdown_container);
        if (countdownContainer != null) {
            countdownContainer.setVisibility(View.GONE);
        }

        mCropView.setOnCropRectChangeListener(this::updateControlsPosition);

        mBtnRecord.setOnClickListener(v -> confirmCrop());
        mBtnClose.setOnClickListener(v -> cancelCrop());

        int layoutFlag = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        mParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            mParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        mWindowManager.addView(mRootView, mParams);
    }

    private void updateControlsPosition(Rect rect) {
        if (mControlsLayout == null || rect == null) return;

        if (mControlsLayout.getWidth() == 0) {
            mControlsLayout.post(() -> updateControlsPosition(mCropView.getCropRect()));
            return;
        }

        mControlsLayout.setVisibility(View.VISIBLE);

        int x = rect.centerX() - (mControlsLayout.getWidth() / 2);
        int y = rect.bottom + 20;

        if (mCropView != null) {
            int maxW = mCropView.getWidth();
            int maxH = mCropView.getHeight();
            if (maxW > 0 && maxH > 0) {
                if (x < 20) x = 20;
                if (x + mControlsLayout.getWidth() > maxW - 20) {
                    x = maxW - mControlsLayout.getWidth() - 20;
                }
                if (y + mControlsLayout.getHeight() > maxH - 20) {
                    y = rect.top - mControlsLayout.getHeight() - 20;
                    if (y < 20) {
                        y = rect.bottom - mControlsLayout.getHeight() - 20;
                    }
                }
            }
        }

        mControlsLayout.setX(x);
        mControlsLayout.setY(y);
    }

    private void confirmCrop() {
        if (mDismissed) return;
        Rect cropRect = mCropView != null ? mCropView.getCropRect() : null;
        if (cropRect == null) {
            cancelCrop();
            return;
        }

        Rect confirmedRect = new Rect(cropRect);
        dismiss();

        if (mCallback != null) {
            mCallback.onCropConfirmed(confirmedRect);
        }
    }

    private void cancelCrop() {
        if (mDismissed) return;
        dismiss();

        if (mCallback != null) {
            mCallback.onCropCancelled();
        }
    }

    public synchronized void dismiss() {
        if (mDismissed) return;
        mDismissed = true;
        if (mRootView != null) {
            try {
                mWindowManager.removeView(mRootView);
            } catch (Exception ignored) {}
            mRootView = null;
        }
    }
}
