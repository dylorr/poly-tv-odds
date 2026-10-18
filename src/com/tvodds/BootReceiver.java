package com.tvodds;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/** Brings the overlay back after the Chromecast restarts. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(i.getAction()) && Settings.canDrawOverlays(c)) {
            c.startForegroundService(new Intent(c, OverlayService.class));
        }
    }
}
