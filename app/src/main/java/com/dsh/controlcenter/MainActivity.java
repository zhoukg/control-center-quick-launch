package com.dsh.controlcenter;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.RadioGroup;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 主界面：状态展示、立即唤出、无障碍授权入口、唤出方式选择、悬浮球开关。
 */
public class MainActivity extends Activity {

    /** 小组件点击时传入：进入后延迟自动唤出。 */
    public static final String EXTRA_AUTO = "extra_auto";

    private static final int REQUEST_POST_NOTIFICATIONS = 101;
    private static final long AUTO_SUMMON_DELAY_MS = 350L;
    /** 自动唤出最多重试次数：覆盖「刚覆盖安装 / 刚开机，无障碍服务还没连上」的情况。 */
    private static final int AUTO_MAX_ATTEMPTS = 12;
    private static final long AUTO_RETRY_DELAY_MS = 300L;

    private TextView tvStatus;
    private Button btnAccessibility;
    private Button btnSummon;
    private Switch switchAuto;
    private Switch switchBubble;
    private RadioGroup radioGroupMode;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    /** 代码刷新控件状态时临时屏蔽监听器，避免把 UI 刷新当成用户操作。 */
    private boolean ignoreListener;

    /** 冷启动时无障碍服务可能还在连接，允许重试 AUTO_MAX_ATTEMPTS 次。 */
    private int autoAttempts;

    private final Runnable autoSummonRunnable = new Runnable() {
        @Override
        public void run() {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            if (CCAccessibilityService.summonDefault(MainActivity.this)) {
                refreshStatus();
                return;
            }
            refreshStatus();
            // 无障碍没开：只提示一次，不反复弹 Toast，也不自动跳设置页。
            if (!isAccessibilityEnabled()) {
                if (autoAttempts == 0) {
                    Toast.makeText(MainActivity.this,
                            R.string.toast_need_accessibility, Toast.LENGTH_LONG).show();
                }
                return;
            }
            // 已开启但服务尚未连接（例如刚覆盖安装 / 刚开机）：短暂重试。
            if (autoAttempts < AUTO_MAX_ATTEMPTS) {
                autoAttempts++;
                mainHandler.postDelayed(this, AUTO_RETRY_DELAY_MS);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tv_status);
        btnAccessibility = findViewById(R.id.btn_accessibility);
        btnSummon = findViewById(R.id.btn_summon);
        switchAuto = findViewById(R.id.switch_auto);
        switchBubble = findViewById(R.id.switch_bubble);
        radioGroupMode = findViewById(R.id.radio_group_mode);

        btnAccessibility.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAccessibilitySettings();
            }
        });

        btnSummon.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                summonNow(true);
            }
        });

        switchAuto.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (ignoreListener) {
                    return;
                }
                Prefs.setAutoLaunch(MainActivity.this, isChecked);
            }
        });

        radioGroupMode.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                if (ignoreListener) {
                    return;
                }
                Prefs.setMode(MainActivity.this, modeForRadioId(checkedId));
            }
        });

        switchBubble.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (ignoreListener) {
                    return;
                }
                if (isChecked) {
                    enableBubble();
                } else {
                    disableBubble();
                }
            }
        });

        refreshUi();

        Intent intent = getIntent();
        boolean fromWidget = intent != null && intent.getBooleanExtra(EXTRA_AUTO, false);
        boolean autoOnLaunch = savedInstanceState == null && Prefs.isAutoLaunch(this);
        if (fromWidget || autoOnLaunch) {
            autoAttempts = 0;
            mainHandler.postDelayed(autoSummonRunnable, AUTO_SUMMON_DELAY_MS);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // 从桌面图标/小组件再次进入（singleTop）也要触发自动唤出，否则「打开即唤出」只在冷启动生效。
        boolean fromWidget = intent != null && intent.getBooleanExtra(EXTRA_AUTO, false);
        if (fromWidget || Prefs.isAutoLaunch(this)) {
            mainHandler.removeCallbacks(autoSummonRunnable);
            autoAttempts = 0;
            mainHandler.postDelayed(autoSummonRunnable, AUTO_SUMMON_DELAY_MS);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUi();
        // 覆盖安装 / 进程被杀后悬浮球服务会停止，但开关状态仍为开：这里自动拉起。
        ensureBubbleRunning();
    }

    private void ensureBubbleRunning() {
        if (!Prefs.isBubbleEnabled(this)) {
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            return;
        }
        if (FloatingBubbleService.isRunning()) {
            return;
        }
        try {
            startForegroundService(new Intent(this, FloatingBubbleService.class));
        } catch (Throwable ignored) {
            // 后台启动限制等异常时忽略，用户可手动重新开关一次。
        }
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ---------------------------------------------------------------------
    // 界面状态
    // ---------------------------------------------------------------------

    private void refreshUi() {
        refreshStatus();
        ignoreListener = true;
        try {
            switchAuto.setChecked(Prefs.isAutoLaunch(this));
            int mode = Prefs.getMode(this);
            radioGroupMode.check(radioIdForMode(mode));
            switchBubble.setChecked(Prefs.isBubbleEnabled(this));
        } finally {
            ignoreListener = false;
        }
    }

    private void refreshStatus() {
        if (isAccessibilityEnabled()) {
            tvStatus.setText(R.string.status_on);
            tvStatus.setTextColor(getColor(R.color.green));
        } else {
            tvStatus.setText(R.string.status_off);
            tvStatus.setTextColor(getColor(R.color.red));
        }
    }

    private boolean isAccessibilityEnabled() {
        if (CCAccessibilityService.isConnected()) {
            return true;
        }
        try {
            String enabledServices = Settings.Secure.getString(
                    getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabledServices == null) {
                return false;
            }
            String fullName = getPackageName() + "/" + CCAccessibilityService.class.getName();
            String shortName = getPackageName() + "/." + CCAccessibilityService.class.getSimpleName();
            return enabledServices.contains(fullName) || enabledServices.contains(shortName);
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // 动作
    // ---------------------------------------------------------------------

    private void summonNow(boolean guideWhenFailed) {
        if (CCAccessibilityService.summonDefault(this)) {
            Toast.makeText(this, R.string.toast_opening, Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, R.string.toast_need_accessibility, Toast.LENGTH_SHORT).show();
        refreshStatus();
        if (guideWhenFailed) {
            openAccessibilitySettings();
        }
    }

    private void openAccessibilitySettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        } catch (Throwable t) {
            Toast.makeText(this, R.string.toast_open_settings_failed, Toast.LENGTH_LONG).show();
        }
    }

    private void enableBubble() {
        if (!Settings.canDrawOverlays(this)) {
            Prefs.setBubbleEnabled(this, false);
            setSwitchBubbleChecked(false);
            Toast.makeText(this, R.string.toast_need_overlay_permission, Toast.LENGTH_LONG).show();
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Throwable ignored) {
                // 部分 ROM 没有该设置页，忽略即可。
            }
            return;
        }

        Prefs.setBubbleEnabled(this, true);
        boolean started = false;
        try {
            startForegroundService(new Intent(this, FloatingBubbleService.class));
            started = true;
        } catch (Throwable t) {
            Prefs.setBubbleEnabled(this, false);
            setSwitchBubbleChecked(false);
            Toast.makeText(this, R.string.toast_bubble_failed, Toast.LENGTH_SHORT).show();
        }
        if (started) {
            requestNotificationPermissionIfNeeded();
        }
    }

    private void disableBubble() {
        Prefs.setBubbleEnabled(this, false);
        try {
            stopService(new Intent(this, FloatingBubbleService.class));
        } catch (Throwable ignored) {
            // 服务本来就没启动时忽略。
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return;
        }
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_POST_NOTIFICATIONS);
        }
    }

    private void setSwitchBubbleChecked(boolean checked) {
        ignoreListener = true;
        try {
            switchBubble.setChecked(checked);
        } finally {
            ignoreListener = false;
        }
    }

    // ---------------------------------------------------------------------
    // 模式与控件 id 映射
    // ---------------------------------------------------------------------

    private static int modeForRadioId(int radioId) {
        if (radioId == R.id.radio_gesture) {
            return Prefs.MODE_GESTURE;
        }
        if (radioId == R.id.radio_global) {
            return Prefs.MODE_GLOBAL;
        }
        return Prefs.MODE_AUTO;
    }

    private static int radioIdForMode(int mode) {
        if (mode == Prefs.MODE_GESTURE) {
            return R.id.radio_gesture;
        }
        if (mode == Prefs.MODE_GLOBAL) {
            return R.id.radio_global;
        }
        return R.id.radio_auto;
    }
}
