package com.example.ssr;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

public class CropOverlayActivity extends Activity {
    
    private CropOverlayView mCropView;
    private View mControlsLayout;
    private View mBtnRecord;
    private View mBtnClose;
    private FrameLayout mCountdownContainer;
    private TextView mTvCountdown;
    
    private int mCount = 3;
    private Handler mHandler = new Handler(Looper.getMainLooper());
    private boolean mIsCountingDown = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_crop);
        
        mCropView = findViewById(R.id.crop_view);
        mControlsLayout = findViewById(R.id.ll_controls);
        mBtnRecord = findViewById(R.id.btn_record_crop);
        mBtnClose = findViewById(R.id.btn_close_crop);
        mCountdownContainer = findViewById(R.id.fl_countdown_container);
        mTvCountdown = findViewById(R.id.tv_local_countdown);
        
        mCropView.setOnCropRectChangeListener(rect -> updateControlsPosition(rect));
        
        mBtnRecord.setOnClickListener(v -> startCountdown());
        mBtnClose.setOnClickListener(v -> onBackPressed());
        
        // Initial position will be set when mCropView calls onSizeChanged
    }
    
    private void updateControlsPosition(Rect rect) {
        if (mIsCountingDown || mControlsLayout.getWidth() == 0) {
            if (mControlsLayout.getWidth() == 0) {
                // Post to run after layout pass
                mControlsLayout.post(() -> updateControlsPosition(mCropView.getCropRect()));
            }
            return;
        }
        
        mControlsLayout.setVisibility(View.VISIBLE);
        
        // Position below the rectangle, centered horizontally
        int x = rect.centerX() - (mControlsLayout.getWidth() / 2);
        int y = rect.bottom + 20; // 20px margin
        
        // Clamp to screen bounds
        if (x < 0) x = 20;
        if (x + mControlsLayout.getWidth() > mCropView.getWidth()) {
            x = mCropView.getWidth() - mControlsLayout.getWidth() - 20;
        }
        if (y + mControlsLayout.getHeight() > mCropView.getHeight()) {
            // If it goes off the bottom, put it inside the rectangle at the bottom
            y = rect.bottom - mControlsLayout.getHeight() - 20;
        }
        
        mControlsLayout.setX(x);
        mControlsLayout.setY(y);
    }
    
    private void startCountdown() {
        if (mIsCountingDown) return;
        mIsCountingDown = true;
        
        mControlsLayout.setVisibility(View.GONE);
        mCountdownContainer.setVisibility(View.VISIBLE);
        
        finishWithResult();
    }
    
    private void finishWithResult() {
        Rect uiRect = mCropView.getCropRect();
        
        // Convert to physical display pixels
        DisplayMetrics realMetrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(realMetrics);
        
        int[] location = new int[2];
        mCropView.getLocationOnScreen(location);
        
        int physicalLeft = location[0] + uiRect.left;
        int physicalTop = location[1] + uiRect.top;
        int physicalRight = location[0] + uiRect.right;
        int physicalBottom = location[1] + uiRect.bottom;
        
        Intent result = new Intent();
        result.putExtra("crop_left", physicalLeft);
        result.putExtra("crop_top", physicalTop);
        result.putExtra("crop_right", physicalRight);
        result.putExtra("crop_bottom", physicalBottom);
        
        setResult(RESULT_OK, result);
        finish();
    }
    
    @Override
    public void onBackPressed() {
        if (!mIsCountingDown) {
            setResult(RESULT_CANCELED);
            super.onBackPressed();
        }
    }
}
