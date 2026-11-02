package com.example.ssr;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.util.Log;

/**
 * PermissionProxyActivity
 *
 * An invisible, translucent Activity whose sole purpose is to fire the
 * system "Allow screen capture?" dialog.  Once the user grants (or denies)
 * permission, the result is forwarded to MainActivity via a local broadcast
 * Intent so the recording can actually begin.
 *
 * This pattern mirrors Samsung's MediaProjectionActivity exactly:
 * — No layout inflated
 * — Finishes immediately after receiving the result
 */
public class PermissionProxyActivity extends Activity {

    private static final String TAG = "PermissionProxyActivity";
    private static final int REQ_MEDIA_PROJECTION = 1001;
    private static final int REQ_WRITE_SETTINGS = 1002;
    private static final int REQ_CROP_OVERLAY = 1003;

    private String mAudioMode;
    private int mAreaMode;

    private int mMediaResultCode;
    private Intent mMediaResultData;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        mAudioMode = getIntent().getStringExtra(RecordingService.EXTRA_AUDIO_MODE);
        mAreaMode = getIntent().getIntExtra(RecordingService.EXTRA_AREA_MODE, 0);
        requestMediaProjection();
    }

    private void requestMediaProjection() {
        MediaProjectionManager mgr = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        startActivityForResult(mgr.createScreenCaptureIntent(), REQ_MEDIA_PROJECTION);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        
        if (requestCode == REQ_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                mMediaResultCode = resultCode;
                mMediaResultData = data;
                if (mAreaMode == 1 /* AREA_PARTIAL_SCREEN */) {
                    new CropSelectionOverlay(getApplicationContext(), new CropSelectionOverlay.Callback() {
                        @Override
                        public void onCropConfirmed(android.graphics.Rect cropRect) {
                            startRecordingFlow(mMediaResultCode, mMediaResultData, cropRect);
                        }

                        @Override
                        public void onCropCancelled() {
                            Log.i(TAG, "Partial-screen crop cancelled by user");
                            finish();
                        }
                    }).show();
                } else {
                    startRecordingFlow(resultCode, data, null);
                }
            } else {
                Log.w(TAG, "MediaProjection denied or cancelled by user");
                finish();
            }
        }
    }
    
    private void startRecordingFlow(int resultCode, Intent data, android.graphics.Rect cropRect) {
        Intent serviceIntent = new Intent(this, RecordingService.class);
        serviceIntent.setAction(RecordingService.ACTION_START);
        serviceIntent.putExtra(RecordingService.EXTRA_RESULT_CODE, resultCode);
        serviceIntent.putExtra(RecordingService.EXTRA_RESULT_DATA, data);
        serviceIntent.putExtra(RecordingService.EXTRA_AUDIO_MODE, mAudioMode != null ? mAudioMode : "None");
        serviceIntent.putExtra(RecordingService.EXTRA_AREA_MODE, mAreaMode);

        if (cropRect != null) {
            serviceIntent.putExtra("crop_rect", cropRect);
        }

        // Run countdown (3, 2, 1) cleanly before starting RecordingService
        new CountdownOverlay(this, serviceIntent, this::finish).show();
    }
}
