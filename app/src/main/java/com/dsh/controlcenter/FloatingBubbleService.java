package com.dsh.controlcenter;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;

/**
 * 悬浮球前台服务。
 *
 * <p>屏幕上显示一个 56dp 的蓝色圆形悬浮球：点击唤出控制中心，拖动可移动位置（自动保存），
 * 长按打开 App 主界面。整个实现只使用 android.* API。</p>
 */
public class FloatingBubbleService extends Service {

    private static final String CHANNEL_ID = "bubble";
    private static final int NOTIFICATION_ID = 1001;
    private static final int BUBBLE_SIZE_DP = 56;
    private static final int ICON_SIZE_DP = 28;
    private static final int EDGE_MARGIN_DP = 8;
    private static final long LONG_PRESS_MS = 600L;

    /** 悬浮球服务是否正在运行（供 MainActivity 判断是否需要重新拉起）。 */
    private static volatile boolean running = false;

    public static boolean isRunning() {
        return running;
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private WindowManager windowManager;
    private WindowManager.LayoutParams params;
    private FrameLayout bubbleView;

    private int screenWidth;
    private int screenHeight;
    private int bubbleSizePx;
    private int touchSlop;

    private float downRawX;
    private float downRawY;
    private int dragStartX;
    private int dragStartY;
    private boolean dragging;
    private boolean longPressFired;

    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            longPressFired = true;
            View view = bubbleView;
            if (view != null) {
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            }
            openMainActivity();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            createNotificationChannel();
            startForeground(NOTIFICATION_ID, buildNotification());
            running = true;
        } catch (Throwable t) {
            // 通知权限 / 前台服务类型异常时不要崩溃，直接结束自身。
            stopSelf();
            return;
        }
        showBubble();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!Settings.canDrawOverlays(this)) {
            Prefs.setBubbleEnabled(this, false);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (bubbleView == null) {
            showBubble();
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        running = false;
        mainHandler.removeCallbacksAndMessages(null);
        removeBubbleView();
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Throwable ignored) {
            // 忽略。
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------------
    // 通知
    // ---------------------------------------------------------------------

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.bubble_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.bubble_notification_text));
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        builder.setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(getString(R.string.bubble_notification_title))
                .setContentText(getString(R.string.bubble_notification_text))
                .setContentIntent(contentIntent)
                .setOngoing(true);
        return builder.build();
    }

    // ---------------------------------------------------------------------
    // 悬浮球
    // ---------------------------------------------------------------------

    private void showBubble() {
        if (bubbleView != null) {
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            Prefs.setBubbleEnabled(this, false);
            stopSelf();
            return;
        }
        try {
            windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            if (windowManager == null) {
                Prefs.setBubbleEnabled(this, false);
                stopSelf();
                return;
            }

            DisplayMetrics metrics = getResources().getDisplayMetrics();
            screenWidth = metrics.widthPixels;
            screenHeight = metrics.heightPixels;
            float density = metrics.density <= 0f ? 1f : metrics.density;

            bubbleSizePx = Math.round(BUBBLE_SIZE_DP * density);
            int iconSizePx = Math.round(ICON_SIZE_DP * density);
            int marginPx = Math.round(EDGE_MARGIN_DP * density);
            touchSlop = ViewConfiguration.get(this).getScaledTouchSlop();

            int x = Prefs.getBubbleX(this);
            int y = Prefs.getBubbleY(this);
            if (x < 0 || y < 0) {
                // 默认放在屏幕右侧中部。
                x = screenWidth - bubbleSizePx - marginPx;
                y = (screenHeight - bubbleSizePx) / 2;
            }
            x = clamp(x, 0, Math.max(0, screenWidth - bubbleSizePx));
            y = clamp(y, 0, Math.max(0, screenHeight - bubbleSizePx));

            FrameLayout container = new FrameLayout(this);
            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.OVAL);
            shape.setColor(getColor(R.color.bubble));
            container.setBackground(shape);
            container.setElevation(6f * density);

            ImageView icon = new ImageView(this);
            icon.setImageResource(R.drawable.ic_control_center);
            icon.setColorFilter(Color.WHITE);
            FrameLayout.LayoutParams iconParams =
                    new FrameLayout.LayoutParams(iconSizePx, iconSizePx);
            iconParams.gravity = Gravity.CENTER;
            container.addView(icon, iconParams);

            int windowType = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;
            params = new WindowManager.LayoutParams(
                    bubbleSizePx,
                    bubbleSizePx,
                    windowType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            params.x = x;
            params.y = y;

            container.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View view, MotionEvent event) {
                    return handleTouch(event);
                }
            });

            windowManager.addView(container, params);
            bubbleView = container;
        } catch (Throwable t) {
            // 覆盖层权限被撤销、WindowManager 异常等情况，避免崩溃。
            bubbleView = null;
            Prefs.setBubbleEnabled(this, false);
            stopSelf();
        }
    }

    private boolean handleTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                dragStartX = params == null ? 0 : params.x;
                dragStartY = params == null ? 0 : params.y;
                dragging = false;
                longPressFired = false;
                mainHandler.removeCallbacks(longPressRunnable);
                mainHandler.postDelayed(longPressRunnable, LONG_PRESS_MS);
                return true;

            case MotionEvent.ACTION_MOVE: {
                float dx = event.getRawX() - downRawX;
                float dy = event.getRawY() - downRawY;
                if (!dragging && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                    dragging = true;
                    mainHandler.removeCallbacks(longPressRunnable);
                }
                if (dragging) {
                    updateBubblePosition(dragStartX + Math.round(dx), dragStartY + Math.round(dy));
                }
                return true;
            }

            case MotionEvent.ACTION_UP:
                mainHandler.removeCallbacks(longPressRunnable);
                if (dragging) {
                    saveBubblePosition();
                } else if (!longPressFired) {
                    handleTap();
                }
                dragging = false;
                return true;

            case MotionEvent.ACTION_CANCEL:
                mainHandler.removeCallbacks(longPressRunnable);
                if (dragging) {
                    saveBubblePosition();
                }
                dragging = false;
                return true;

            default:
                return false;
        }
    }

    private void updateBubblePosition(int x, int y) {
        if (params == null || bubbleView == null || windowManager == null) {
            return;
        }
        params.x = clamp(x, 0, Math.max(0, screenWidth - bubbleSizePx));
        params.y = clamp(y, 0, Math.max(0, screenHeight - bubbleSizePx));
        try {
            windowManager.updateViewLayout(bubbleView, params);
        } catch (Throwable ignored) {
            // 忽略。
        }
    }

    private void saveBubblePosition() {
        if (params == null) {
            return;
        }
        Prefs.setBubblePos(this, params.x, params.y);
    }

    private void handleTap() {
        boolean ok = CCAccessibilityService.summonDefault(this);
        if (!ok) {
            Toast.makeText(this, R.string.toast_need_accessibility, Toast.LENGTH_SHORT).show();
        }
    }

    private void openMainActivity() {
        try {
            Intent intent = new Intent(this, MainActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Throwable ignored) {
            // 忽略。
        }
    }

    private void removeBubbleView() {
        FrameLayout view = bubbleView;
        bubbleView = null;
        if (view == null || windowManager == null) {
            return;
        }
        try {
            windowManager.removeView(view);
        } catch (Throwable ignored) {
            // 覆盖层可能已经失效。
        }
    }

    private static int clamp(int value, int min, int max) {
        if (max < min) {
            return min;
        }
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }
}
