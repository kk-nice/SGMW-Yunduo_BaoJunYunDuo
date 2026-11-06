package com.sgmwyunduo.pwoff;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    // 车机（MT8666/LingOS）专用开机广播，CarStateService.BootReceiver 用的就是它
    private static final String ACTION_BOOT_HU = "android.intent.action.ACTION_BOOT_HU";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || ACTION_BOOT_HU.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            GuardService.schedulePersistedJob(context);
            GuardService.start(context, "boot:" + action);
        }
    }
}
