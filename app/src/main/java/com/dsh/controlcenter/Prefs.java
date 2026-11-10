package com.dsh.controlcenter;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 简单的偏好设置封装，全部使用纯 android.* API。
 */
public final class Prefs {

    private static final String FILE_NAME = "control_center_prefs";

    /** 自动：先系统动作，失败再手势。 */
    public static final int MODE_AUTO = 0;
    /** 手势：注入一次从屏幕左上角向下的滑动。 */
    public static final int MODE_GESTURE = 1;
    /** 系统动作：performGlobalAction(GLOBAL_ACTION_QUICK_SETTINGS)。 */
    public static final int MODE_GLOBAL = 2;

    private static final String KEY_MODE = "mode";
    private static final String KEY_AUTO_LAUNCH = "auto_launch";
    private static final String KEY_BUBBLE_ENABLED = "bubble_enabled";
    private static final String KEY_BUBBLE_X = "bubble_x";
    private static final String KEY_BUBBLE_Y = "bubble_y";

    private Prefs() {
        // 工具类，禁止实例化。
    }

    private static SharedPreferences sp(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE);
    }

    public static int getMode(Context context) {
        return sp(context).getInt(KEY_MODE, MODE_AUTO);
    }

    public static void setMode(Context context, int mode) {
        sp(context).edit().putInt(KEY_MODE, mode).apply();
    }

    public static boolean isAutoLaunch(Context context) {
        return sp(context).getBoolean(KEY_AUTO_LAUNCH, true);
    }

    public static void setAutoLaunch(Context context, boolean enabled) {
        sp(context).edit().putBoolean(KEY_AUTO_LAUNCH, enabled).apply();
    }

    public static boolean isBubbleEnabled(Context context) {
        return sp(context).getBoolean(KEY_BUBBLE_ENABLED, false);
    }

    public static void setBubbleEnabled(Context context, boolean enabled) {
        sp(context).edit().putBoolean(KEY_BUBBLE_ENABLED, enabled).apply();
    }

    public static int getBubbleX(Context context) {
        return sp(context).getInt(KEY_BUBBLE_X, -1);
    }

    public static int getBubbleY(Context context) {
        return sp(context).getInt(KEY_BUBBLE_Y, -1);
    }

    public static void setBubblePos(Context context, int x, int y) {
        sp(context).edit()
                .putInt(KEY_BUBBLE_X, x)
                .putInt(KEY_BUBBLE_Y, y)
                .apply();
    }
}
