package com.tvodds;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

/** Opening the app from the Google TV home screen turns the overlay on or off. */
public class ToggleActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent svc = new Intent(this, OverlayService.class);
        if (OverlayService.running) {
            stopService(svc);
            Toast.makeText(this, "Odds overlay off", Toast.LENGTH_SHORT).show();
        } else if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Overlay permission missing: run install.sh", Toast.LENGTH_LONG).show();
        } else {
            startForegroundService(svc);
            Toast.makeText(this, "Odds overlay on", Toast.LENGTH_SHORT).show();
        }
        finish();
    }
}
