package com.example.ssr;

/**
 * SSRStateManager
 *
 * Lightweight in-process singleton that tracks whether a screen recording
 * session is currently active.  This lets the QS Tile, the MainActivity,
 * and the BottomSheet all read/write the same recording flag without needing
 * a running Service as intermediary.
 *
 * In a production build you would replace this with a check against your
 * actual ForegroundService (e.g. ActivityManager.getRunningServices()), but
 * for our current architecture this is clean and sufficient.
 */
public final class SSRStateManager {

    private static volatile SSRStateManager sInstance;

    /** True while a recording session is in progress */
    private volatile boolean mIsRecording = false;

    /** Listener notified on state changes (e.g. to refresh the QS Tile) */
    public interface RecordingStateListener {
        void onRecordingStateChanged(boolean isRecording);
    }

    private RecordingStateListener mListener;

    // ── Singleton ─────────────────────────────────────────────────────────────

    private SSRStateManager() {}

    public static SSRStateManager getInstance() {
        if (sInstance == null) {
            synchronized (SSRStateManager.class) {
                if (sInstance == null) {
                    sInstance = new SSRStateManager();
                }
            }
        }
        return sInstance;
    }

    // ── State accessors ───────────────────────────────────────────────────────

    public boolean isRecording() {
        return mIsRecording;
    }

    public void setRecording(boolean recording) {
        mIsRecording = recording;
        if (mListener != null) {
            mListener.onRecordingStateChanged(recording);
        }
    }

    // ── Listener ──────────────────────────────────────────────────────────────

    public void setListener(RecordingStateListener listener) {
        mListener = listener;
    }

    public void removeListener() {
        mListener = null;
    }
}
