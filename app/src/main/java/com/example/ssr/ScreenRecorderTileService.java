package com.example.ssr;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;

/**
 * ScreenRecorderTileService
 *
 * Registers the SSR tile in the Android Quick Settings / Control Center panel.
 * Modelled directly after Samsung's RecordScreenTile.java from the extracted APK.
 *
 * ┌─────────────────────────────────────────────────────────────────┐
 * │  INACTIVE tap → collapse panel → show TileConfigActivity       │
 * │                  (transparent trampoline → bottom-sheet)        │
 * │  ACTIVE tap   → send ACTION_STOP to RecordingService            │
 * └─────────────────────────────────────────────────────────────────┘
 *
 * Android API compat:
 *  • API < 34  : startActivityAndCollapse(Intent)
 *  • API ≥ 34  : startActivityAndCollapse(PendingIntent)   ← Samsung pattern
 *
 * Manifest registration (AndroidManifest.xml):
 *   <service android:name=".ScreenRecorderTileService"
 *            android:label="@string/qs_tile_label"
 *            android:icon="@drawable/quick_panel_icon_record_screen"
 *            android:permission="android.permission.BIND_QUICK_SETTINGS_TILE"
 *            android:exported="true">
 *       <intent-filter>
 *           <action android:name="android.service.quicksettings.action.QS_TILE"/>
 *       </intent-filter>
 *   </service>
 *
 * To add the tile:
 *   Pull down the notification shade → long-press a tile → Edit → drag "Screen Recorder" in.
 */
public class ScreenRecorderTileService extends TileService {

    private static final String TAG = "SSR_TileService";

    /**
     * Broadcast sent by RecordingService when recording starts/stops.
     * We listen to this to keep the tile state in sync.
     */
    public static final String ACTION_RECORDING_STATE_CHANGED =
            "com.example.ssr.ACTION_RECORDING_STATE_CHANGED";
    public static final String EXTRA_IS_RECORDING = "is_recording";

    /** Receives state-change broadcasts to keep tile UI in sync. */
    private final BroadcastReceiver mStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_RECORDING_STATE_CHANGED.equals(intent.getAction())) {
                boolean recording = intent.getBooleanExtra(EXTRA_IS_RECORDING, false);
                Log.i(TAG, "State broadcast received — isRecording=" + recording);
                refreshTileState(recording);
            }
        }
    };

    // ── TileService lifecycle ─────────────────────────────────────────────────

    @Override
    public void onStartListening() {
        super.onStartListening();
        // Register broadcast receiver so the tile icon updates live
        IntentFilter filter = new IntentFilter(ACTION_RECORDING_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(mStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(mStateReceiver, filter);
        }
        // Sync with current state from state manager
        refreshTileState(SSRStateManager.getInstance().isRecording());
    }

    @Override
    public void onStopListening() {
        super.onStopListening();
        try {
            unregisterReceiver(mStateReceiver);
        } catch (IllegalArgumentException ignored) {
            // Receiver was not registered (panel opened and immediately closed)
        }
    }

    @Override
    public void onTileAdded() {
        super.onTileAdded();
        Log.i(TAG, "Screen Recorder quick button is added");
        refreshTileState(SSRStateManager.getInstance().isRecording());
    }

    @Override
    public void onTileRemoved() {
        super.onTileRemoved();
        Log.i(TAG, "Screen Recorder quick button is removed");
    }

    // ── Tap handler ───────────────────────────────────────────────────────────

    /**
     * Called when the user taps the QS tile.
     *
     * Samsung pattern (RecordScreenTile.onClick):
     *  • Not recording → collapse panel and launch LauncherActivity
     *  • Recording     → stop the RecordingService
     */
    @Override
    public void onClick() {
        Tile tile = getQsTile();
        if (tile == null) return;

        if (tile.getState() == Tile.STATE_ACTIVE) {
            // ── Recording in progress → stop it ──────────────────────────────
            Log.i(TAG, "Tile tapped while ACTIVE — stopping recording");
            stopRecordingFromTile();
        } else {
            // ── Not recording → collapse panel & open config sheet ────────────
            Log.i(TAG, "Tile tapped while INACTIVE — launching TileConfigActivity");
            launchConfigActivity();
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Collapses the Quick Settings panel and opens {@link TileConfigActivity},
     * the transparent trampoline that shows the recording bottom sheet.
     *
     * Matches Samsung's RecordScreenTile.onClick() exactly, including
     * the Android 14 (API 34) PendingIntent path.
     */
    private void launchConfigActivity() {
        Intent intent = new Intent(getApplicationContext(), MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ requires PendingIntent overload — startActivityAndCollapse(Intent) removed
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    getApplicationContext(),
                    0,
                    intent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
            );
            startActivityAndCollapse(pendingIntent);
        } else {
            //noinspection deprecation
            startActivityAndCollapse(intent);
        }

        // Optimistically set tile to "unavailable" (turning on...) until
        // the recording actually starts and broadcasts STATE_CHANGED
        setTileState(Tile.STATE_UNAVAILABLE);
    }

    /**
     * Sends the ACTION_STOP Intent to {@link RecordingService} to halt recording.
     * This is the real stop — mirrors Samsung's RecordingThreadService.ACTION_STOP.
     */
    private void stopRecordingFromTile() {
        Intent stopIntent = new Intent(getApplicationContext(), RecordingService.class);
        stopIntent.setAction(RecordingService.ACTION_STOP);
        getApplicationContext().startService(stopIntent);

        // Update state manager and tile immediately (broadcast will also arrive)
        SSRStateManager.getInstance().setRecording(false);
        setTileState(Tile.STATE_INACTIVE);
    }

    /**
     * Updates the tile icon, label, and state, then pushes the update to the panel.
     * Mirrors Samsung's setRecordingState() pattern in RecordScreenTile.
     *
     * State 1 = STATE_INACTIVE (not recording)
     * State 2 = STATE_ACTIVE   (recording)
     */
    private void setTileState(int state) {
        Tile tile = getQsTile();
        if (tile == null) return;
        tile.setState(state);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.setStateDescription(state == Tile.STATE_ACTIVE ? "Recording" : "Tap to record");
        }
        tile.updateTile();
    }

    /**
     * Syncs the tile visual state with the actual recording state.
     * Called in onStartListening (panel opens) and via broadcast receiver.
     *
     * Uses Samsung's actual quick_panel_icon_record_screen icon, matching
     * the extracted APK's tile appearance exactly.
     */
    private void refreshTileState(boolean isRecording) {
        Tile tile = getQsTile();
        if (tile == null) return;

        // Use Samsung's authentic QS panel icon
        tile.setIcon(Icon.createWithResource(
                getApplicationContext(), R.drawable.quick_panel_icon_record_screen));
        tile.setLabel(getString(R.string.qs_tile_label));
        tile.setState(isRecording ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.setStateDescription(isRecording ? "Recording" : "Tap to record");
        }
        tile.updateTile();
    }
}
