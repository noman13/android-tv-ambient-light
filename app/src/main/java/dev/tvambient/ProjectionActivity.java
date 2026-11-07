package dev.tvambient;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;

/** Invisible: asks Android for screen capture, hands the result to AmbiService, and finishes. */
public final class ProjectionActivity extends Activity {
    private static final int REQ = 1;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (b == null) {
            MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ && resultCode == RESULT_OK && data != null) {
            Intent svc = new Intent(this, AmbiService.class)
                    .setAction(AmbiService.ACTION_START_CAPTURE)
                    .putExtra(AmbiService.EXTRA_CODE, resultCode)
                    .putExtra(AmbiService.EXTRA_DATA, data);
            startForegroundService(svc);
        } else {
            AmbiService.sStatus = "Screen capture was not allowed";
        }
        finish();
    }
}
