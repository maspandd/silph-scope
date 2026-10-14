package com.tapjackos.attacker;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Control panel for {@link OverlayService}. Built in code so the project needs no resources. */
public class MainActivity extends Activity {

    private static final int REQUEST_NOTIFICATIONS = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);

        TextView help = new TextView(this);
        help.setText("Start an overlay, then switch to the app under test and tap its buttons.");
        layout.addView(help);

        addButton(layout, "Grant overlay permission", () -> startActivity(new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
        addOverlayButton(layout, "Full overlay, opaque (alpha 1.0)", 1.0f, false);
        addOverlayButton(layout, "Full overlay, alpha 0.9", 0.9f, false);
        addOverlayButton(layout, "Full overlay, alpha 0.5", 0.5f, false);
        addOverlayButton(layout, "Partial overlay (top quarter), opaque", 1.0f, true);
        addButton(layout, "Stop overlay", () -> stopService(new Intent(this, OverlayService.class)));

        setContentView(layout);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(new String[] {android.Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
    }

    private void addOverlayButton(LinearLayout layout, String label, float alpha, boolean partial) {
        addButton(layout, label, () -> {
            Intent intent = OverlayService.intent(this, alpha, partial);
            startForegroundService(intent);
        });
    }

    private void addButton(LinearLayout layout, String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setOnClickListener(v -> action.run());
        layout.addView(button);
    }
}
