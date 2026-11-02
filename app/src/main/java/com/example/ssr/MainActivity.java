package com.example.ssr;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;

import android.content.res.Configuration;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.switchmaterial.SwitchMaterial;

public class MainActivity extends AppCompatActivity {

    public static final int AREA_FULL_SCREEN    = 0;
    public static final int AREA_PARTIAL_SCREEN = 1;

    public static final int AUDIO_NONE         = 0;
    public static final int AUDIO_MEDIA        = 1;
    public static final int AUDIO_MEDIA_AND_MIC = 2;

    private static final String KEY_SELECTED_AREA = "selected_area";
    private static final String KEY_SELECTED_AUDIO = "selected_audio";

    private int selectedArea  = AREA_FULL_SCREEN;
    private int selectedAudio = AUDIO_NONE;

    private int clrBlue;
    private int clrTextPrimary;
    private int clrIconUnselected;

    private LinearLayout optionFullScreen, optionPartialScreen;
    private ImageView    ivFullScreen,     ivPartialScreen;
    private TextView     tvFullScreen,     tvPartialScreen;
    private RadioButton  rbFullScreen,     rbPartialScreen;

    private LinearLayout optionAudioNone,    optionAudioMedia,    optionAudioMediaMic;
    private ImageView    ivAudioNone,         ivAudioMedia,         ivAudioMediaMic1, ivAudioMediaMic2;
    private TextView     tvAudioNone,         tvAudioMedia,         tvAudioMediaMic;
    private RadioButton  rbAudioNone,         rbAudioMedia,         rbAudioMediaMic;


    private Button         btnCancel, btnStartRecording;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) {
            selectedArea = savedInstanceState.getInt(KEY_SELECTED_AREA, AREA_FULL_SCREEN);
            selectedAudio = savedInstanceState.getInt(KEY_SELECTED_AUDIO, AUDIO_NONE);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            WindowManager.LayoutParams wlp = getWindow().getAttributes();
            wlp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            getWindow().setAttributes(wlp);
        }
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
        getWindow().setNavigationBarColor(android.graphics.Color.TRANSPARENT);

        EdgeToEdge.enable(this);
        setContentView(R.layout.fragment_screen_recorder);

        clrBlue = ContextCompat.getColor(this, R.color.accent_blue);
        clrTextPrimary = ContextCompat.getColor(this, R.color.text_primary);
        clrIconUnselected = ContextCompat.getColor(this, R.color.icon_unselected);

        View root = findViewById(R.id.main_root);
        View dialogContainer = findViewById(R.id.dialog_container);
        View bottomActions = findViewById(R.id.bottom_actions_container);
        if (root != null) {
            ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
                Insets bars = insets.getInsets(
                        WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
                if (dialogContainer != null) {
                    FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) dialogContainer.getLayoutParams();
                    lp.gravity = Gravity.BOTTOM;
                    lp.leftMargin = 0;
                    lp.rightMargin = 0;
                    lp.topMargin = 0;
                    lp.bottomMargin = 0;
                    dialogContainer.setLayoutParams(lp);

                    if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
                        // Symmetrical side padding so content is centered and safely inside display cutout / nav bar
                        int sideCutoutOrBar = Math.max(bars.left, bars.right);
                        int sidePad = Math.max(sideCutoutOrBar, (int) (24 * getResources().getDisplayMetrics().density));
                        dialogContainer.setPadding(sidePad, 0, sidePad, 0);
                    } else {
                        dialogContainer.setPadding(bars.left, 0, bars.right, 0);
                    }
                }
                if (bottomActions != null) {
                    int bottomInset = bars.bottom;
                    int baseHeightDp = (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) ? 52 : 56;
                    int baseHeightPx = (int) (baseHeightDp * getResources().getDisplayMetrics().density);
                    android.view.ViewGroup.LayoutParams blp = bottomActions.getLayoutParams();
                    blp.height = baseHeightPx + bottomInset;
                    bottomActions.setLayoutParams(blp);
                    bottomActions.setPadding(0, 0, 0, bottomInset);
                }
                return insets;
            });
            // Dismiss activity when clicking outside the dialog container
            root.setOnClickListener(v -> finish());
        }

        bindViews();

        refreshAreaUI();
        refreshAudioUI();
        setupListeners();
    }

    private void bindViews() {
        optionFullScreen    = findViewById(R.id.option_full_screen);
        optionPartialScreen = findViewById(R.id.option_partial_screen);
        ivFullScreen        = findViewById(R.id.iv_full_screen);
        ivPartialScreen     = findViewById(R.id.iv_partial_screen);
        tvFullScreen        = findViewById(R.id.tv_full_screen);
        tvPartialScreen     = findViewById(R.id.tv_partial_screen);
        rbFullScreen        = findViewById(R.id.rb_full_screen);
        rbPartialScreen     = findViewById(R.id.rb_partial_screen);

        optionAudioNone     = findViewById(R.id.option_audio_none);
        optionAudioMedia    = findViewById(R.id.option_audio_media);
        optionAudioMediaMic = findViewById(R.id.option_audio_media_mic);
        ivAudioNone         = findViewById(R.id.iv_audio_none);
        ivAudioMedia        = findViewById(R.id.iv_audio_media);
        ivAudioMediaMic1    = findViewById(R.id.iv_audio_media_mic_1);
        ivAudioMediaMic2    = findViewById(R.id.iv_audio_media_mic_2);
        tvAudioNone         = findViewById(R.id.tv_audio_none);
        tvAudioMedia        = findViewById(R.id.tv_audio_media);
        tvAudioMediaMic     = findViewById(R.id.tv_audio_media_mic);
        rbAudioNone         = findViewById(R.id.rb_audio_none);
        rbAudioMedia        = findViewById(R.id.rb_audio_media);
        rbAudioMediaMic     = findViewById(R.id.rb_audio_media_mic);

        btnCancel         = findViewById(R.id.btn_cancel);
        btnStartRecording = findViewById(R.id.btn_start_recording);
    }

    private void setupListeners() {
        optionFullScreen.setOnClickListener(v -> {
            selectedArea = AREA_FULL_SCREEN;
            refreshAreaUI();
        });
        optionPartialScreen.setOnClickListener(v -> {
            selectedArea = AREA_PARTIAL_SCREEN;
            refreshAreaUI();
        });

        optionAudioNone.setOnClickListener(v -> {
            selectedAudio = AUDIO_NONE;
            refreshAudioUI();
        });
        optionAudioMedia.setOnClickListener(v -> {
            selectedAudio = AUDIO_MEDIA;
            refreshAudioUI();
        });
        optionAudioMediaMic.setOnClickListener(v -> {
            selectedAudio = AUDIO_MEDIA_AND_MIC;
            refreshAudioUI();
        });

        btnCancel.setOnClickListener(v -> finish());


        btnStartRecording.setOnClickListener(v -> {
            if (!android.provider.Settings.canDrawOverlays(this)) {
                Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:" + getPackageName()));
                startActivity(intent);
                android.widget.Toast.makeText(this, "Please allow 'Display over other apps' to show the countdown and floating controls.", android.widget.Toast.LENGTH_LONG).show();
                return;
            }

            boolean needsAudioPermission = (selectedAudio == AUDIO_MEDIA || selectedAudio == AUDIO_MEDIA_AND_MIC);
            if (needsAudioPermission && ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 100);
                android.widget.Toast.makeText(this, "Please grant audio recording permission to record audio.", android.widget.Toast.LENGTH_LONG).show();
                return;
            }

            if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 101);
                android.widget.Toast.makeText(this, "Please grant notification permission to show the recording timer.", android.widget.Toast.LENGTH_LONG).show();
                return;
            }

            String audioLabel;
            switch (selectedAudio) {
                case AUDIO_MEDIA:
                    audioLabel = "Media";
                    break;
                case AUDIO_MEDIA_AND_MIC:
                    audioLabel = "Media and mic";
                    break;
                default:
                    audioLabel = "None";
            }

            Intent proxyIntent = new Intent(this, PermissionProxyActivity.class);
            proxyIntent.putExtra(RecordingService.EXTRA_AUDIO_MODE, audioLabel);
            proxyIntent.putExtra(RecordingService.EXTRA_AREA_MODE, selectedArea);
            startActivity(proxyIntent);
            
            finish();
        });
    }

    private void refreshAreaUI() {
        boolean fullSel = (selectedArea == AREA_FULL_SCREEN);
        applyAreaOption(optionFullScreen,    ivFullScreen,    tvFullScreen,    rbFullScreen,    fullSel);
        applyAreaOption(optionPartialScreen, ivPartialScreen, tvPartialScreen, rbPartialScreen, !fullSel);
    }

    private void applyAreaOption(LinearLayout card, ImageView icon,
                                 TextView label, RadioButton radio,
                                 boolean selected) {
        card.setBackground(ContextCompat.getDrawable(this,
                selected ? R.drawable.bg_area_option_selected
                         : R.drawable.bg_area_option_unselected));

        label.setTextColor(selected ? clrBlue : clrTextPrimary);
        radio.setChecked(selected);
        
        // The icons now use XML state list selectors, so we just set them as selected
        icon.setSelected(selected);
        
        if (selected) {
            android.animation.ObjectAnimator scaleX = android.animation.ObjectAnimator.ofFloat(icon, "scaleX", 0.9f, 1.05f, 1f);
            android.animation.ObjectAnimator scaleY = android.animation.ObjectAnimator.ofFloat(icon, "scaleY", 0.9f, 1.05f, 1f);
            android.animation.AnimatorSet anim = new android.animation.AnimatorSet();
            anim.playTogether(scaleX, scaleY);
            anim.setDuration(300);
            anim.setInterpolator(new android.view.animation.OvershootInterpolator());
            anim.start();
        }
    }


    private void refreshAudioUI() {
        applyAudioOption(tvAudioNone,     rbAudioNone,     selectedAudio == AUDIO_NONE, ivAudioNone);
        applyAudioOption(tvAudioMedia,    rbAudioMedia,    selectedAudio == AUDIO_MEDIA, ivAudioMedia);
        applyAudioOption(tvAudioMediaMic, rbAudioMediaMic, selectedAudio == AUDIO_MEDIA_AND_MIC, ivAudioMediaMic1, ivAudioMediaMic2);
    }

    private void applyAudioOption(TextView label, RadioButton radio, boolean selected, ImageView... icons) {
        label.setTextColor(selected ? clrBlue : clrTextPrimary);
        radio.setChecked(selected);
        
        // Use tinting for audio icons since they don't have dual-color states like area icons
        for (ImageView icon : icons) {
            icon.setImageTintList(ColorStateList.valueOf(selected ? clrBlue : clrIconUnselected));
            if (selected) {
                android.animation.ObjectAnimator scaleX = android.animation.ObjectAnimator.ofFloat(icon, "scaleX", 0.8f, 1.1f, 1f);
                android.animation.ObjectAnimator scaleY = android.animation.ObjectAnimator.ofFloat(icon, "scaleY", 0.8f, 1.1f, 1f);
                android.animation.AnimatorSet anim = new android.animation.AnimatorSet();
                anim.playTogether(scaleX, scaleY);
                anim.setDuration(300);
                anim.setInterpolator(new android.view.animation.OvershootInterpolator());
                anim.start();
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(KEY_SELECTED_AREA, selectedArea);
        outState.putInt(KEY_SELECTED_AUDIO, selectedAudio);
    }
}