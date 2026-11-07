package dev.tvambient;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Changes settings without opening the app (which would pause the video being measured). Only senders holding
 * android.permission.DUMP can reach it, which in practice means ADB:
 * adb shell am broadcast -a dev.tvambient.SET -n dev.tvambient/.SettingsReceiver --ei method 1 --ei rate 2
 */
public final class SettingsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        SharedPreferences.Editor e = Prefs.sp(c).edit();
        StringBuilder sb = new StringBuilder("settings from ADB:");
        for (Prefs.Def d : Prefs.ALL) {
            if (i.hasExtra(d.key)) {
                int v = Math.max(d.min, Math.min(d.max, i.getIntExtra(d.key, d.def)));
                e.putInt(d.key, v);
                sb.append(' ').append(d.key).append('=').append(d.format(v));
            }
        }
        e.apply();
        Log.i(AmbiService.TAG, sb.toString());
        if (i.getBooleanExtra("start", false)) {
            // Start capture without bringing the settings screen up (allowed once SYSTEM_ALERT_WINDOW is granted).
            AmbiService.start(c);
        }
    }
}
