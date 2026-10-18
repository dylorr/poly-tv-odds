package com.tvodds;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

/** Opening the app from the Google TV home screen turns the overlay on or off (same switch as the phone page). */
public class ToggleActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent svc = new Intent(this, OverlayService.class);
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Overlay permission missing: run install.sh", Toast.LENGTH_LONG).show();
        } else if (OverlayService.running) {
            // Flip the same on/off switch as the phone page (the service keeps running for it).
            boolean nowOn = !getSharedPreferences("tvodds", MODE_PRIVATE).getBoolean("on", true);
            startService(svc.setAction(OverlayService.ACTION_TOGGLE));
            Toast.makeText(this, nowOn ? "Odds overlay on" : "Odds overlay off", Toast.LENGTH_SHORT).show();
        } else {
            startForegroundService(svc.setAction(OverlayService.ACTION_ON));
            Toast.makeText(this, "Odds overlay on", Toast.LENGTH_SHORT).show();
        }
        finish();
    }
}
