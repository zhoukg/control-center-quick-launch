package com.dsh.controlcenter;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Path;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.accessibility.AccessibilityEvent;

/**
 * 无障碍服务：负责真正把控制中心 / 通知面板拉下来。
 *
 * <p>系统动作 GLOBAL_ACTION_QUICK_SETTINGS 会打开完整控制中心（快捷设置网格）；
 * 在目标机器（Xiaomi 12，Android 16）上若系统动作不可用，则退化为注入一次
 * 从屏幕左上角向下滑动的手势（实测该手势打开控制中心，从右上角下滑只会打开通知栏）。</p>
 */
public class CCAccessibilityService extends AccessibilityService {

    private static volatile CCAccessibilityService instance;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static boolean isConnected() {
        return instance != null;
    }

    /**
     * 以指定模式唤起控制中心。
     *
     * @return 触发成功返回 true；服务未连接或动作失败返回 false。
     */
    public static boolean summon(Context context, int mode) {
        CCAccessibilityService service = instance;
        if (service == null) {
            return false;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return service.performSummon(mode);
        }
        final CCAccessibilityService target = service;
        final int safeMode = mode;
        target.mainHandler.post(new Runnable() {
            @Override
            public void run() {
                target.performSummon(safeMode);
            }
        });
        return true;
    }

    /** 使用偏好设置里保存的模式唤起控制中心。 */
    public static boolean summonDefault(Context context) {
        return summon(context, Prefs.getMode(context));
    }

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 本服务不需要读取任何屏幕内容。
    }

    @Override
    public void onInterrupt() {
        // 不需要处理。
    }

    private boolean performSummon(int mode) {
        if (mode == Prefs.MODE_GLOBAL) {
            return performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS);
        }
        if (mode == Prefs.MODE_GESTURE) {
            return performSwipeGesture();
        }
        // MODE_AUTO：先试系统动作，失败再试手势（两者只执行其中一个，避免面板来回开合）。
        if (performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)) {
            return true;
        }
        return performSwipeGesture();
    }

    /**
     * 从屏幕左上方向下滑动，打开完整控制中心。
     * 起点约 (w*0.10, 2px)，终点约 (w*0.10, h*0.60)，时长 320ms。
     */
    private boolean performSwipeGesture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false;
        }
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        if (metrics == null) {
            return false;
        }
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        if (width <= 0 || height <= 0) {
            return false;
        }

        float startX = width * 0.10f;
        float startY = 2f;
        float endX = width * 0.10f;
        float endY = height * 0.60f;

        Path path = new Path();
        path.moveTo(startX, startY);
        path.lineTo(endX, endY);

        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0L, 320L);
        GestureDescription gesture = new GestureDescription.Builder()
                .addStroke(stroke)
                .build();
        try {
            return dispatchGesture(gesture, null, null);
        } catch (Throwable t) {
            return false;
        }
    }
}
