package com.dsh.controlcenter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.widget.Toast;

/**
 * 供 adb / Tasker 等外部工具触发的广播接收器。
 *
 * <pre>
 * adb shell am broadcast -a com.dsh.controlcenter.ACTION_SUMMON -n com.dsh.controlcenter/.ExpandReceiver
 * </pre>
 */
public class ExpandReceiver extends BroadcastReceiver {

    public static final String ACTION_SUMMON = "com.dsh.controlcenter.ACTION_SUMMON";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        if (!ACTION_SUMMON.equals(action)) {
            return;
        }
        boolean ok = CCAccessibilityService.summonDefault(context);
        int message = ok ? R.string.toast_summoned : R.string.toast_service_off;
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }
}
