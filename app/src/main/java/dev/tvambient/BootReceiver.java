package dev.tvambient;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Starts the lights after boot when "Start on boot" is on. Shell screenshots and the colour/effect modes start a
 * foreground service directly. Screen recording needs an activity, which can open from the background only once
 * SYSTEM_ALERT_WINDOW is allowed for the app (adb shell appops set dev.tvambient SYSTEM_ALERT_WINDOW allow).
 */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent intent) {
        if (Prefs.get(Prefs.sp(c), Prefs.BOOT) != 1) return;
        try {
            AmbiService.start(c);
        } catch (RuntimeException e) {
            Log.w(AmbiService.TAG, "start on boot failed: " + e);
        }
    }
}
