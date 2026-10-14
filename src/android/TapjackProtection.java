package com.tapjackos.protection;

import android.app.Activity;
import android.os.Build;
import android.util.Log;
import android.view.View;
import android.view.Window;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Tapjacking / screen overlay protection for the Cordova activity.
 *
 * Layers, all applied on the UI thread:
 * 1. {@code setFilterTouchesWhenObscured} on the decor view and WebView (block mode).
 * 2. {@code Window.setHideOverlayWindows} on API 31+ so third-party overlays are not drawn.
 * 3. A {@link GuardedWindowCallback} that detects obscured touches, reports them to JS
 *    and, in block mode, swallows them (including partially obscured ones in strict mode).
 *
 * Loaded with {@code onload=true}, so protection is on before any JS runs.
 */
public final class TapjackProtection extends CordovaPlugin implements TouchGuard.Reporter {

    private static final String TAG = "TapjackProtection";

    private static final String PREF_MODE = "TapjackProtectionMode";
    private static final String PREF_STRICT = "TapjackStrictMode";
    private static final String PREF_HIDE_OVERLAYS = "TapjackHideOverlays";

    private static final String OPT_MODE = "mode";
    private static final String OPT_STRICT = "strict";
    private static final String OPT_HIDE_OVERLAYS = "hideOverlays";

    private static final long MIN_REPORT_INTERVAL_MS = 500;
    private static final int MAX_PENDING_EVENTS = 10;

    private final TouchGuard guard = new TouchGuard(this, MIN_REPORT_INTERVAL_MS);

    // UI-thread state.
    private boolean hideOverlays = true;
    private boolean filterTouchesActive;
    private boolean hideOverlaysActive;
    private GuardedWindowCallback windowCallback;

    // Guarded by pendingEvents.
    private CallbackContext eventCallback;
    private final List<JSONObject> pendingEvents = new ArrayList<JSONObject>();

    @Override
    protected void pluginInitialize() {
        String rawMode = preferences.getString(PREF_MODE, TouchGuard.Mode.BLOCK.jsName);
        TouchGuard.Mode mode = TouchGuard.Mode.fromString(rawMode);
        if (mode == null) {
            Log.w(TAG, "Unknown " + PREF_MODE + " '" + rawMode + "', falling back to 'block'");
            mode = TouchGuard.Mode.BLOCK;
        }
        final TouchGuard.Mode initialMode = mode;
        final boolean initialStrict = preferences.getBoolean(PREF_STRICT, false);
        final boolean initialHideOverlays = preferences.getBoolean(PREF_HIDE_OVERLAYS, true);

        cordova.getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                guard.configure(initialMode, initialStrict);
                hideOverlays = initialHideOverlays;
                applyProtection();
            }
        });
    }

    @Override
    public boolean execute(String action, JSONArray args, final CallbackContext callbackContext)
            throws JSONException {
        if ("enable".equals(action)) {
            enable(args.optJSONObject(0), callbackContext);
            return true;
        }
        if ("disable".equals(action)) {
            runAndReportStatus(TouchGuard.Mode.OFF, guard.isStrict(), null, callbackContext);
            return true;
        }
        if ("getStatus".equals(action)) {
            runAndReportStatus(null, false, null, callbackContext);
            return true;
        }
        if ("subscribe".equals(action)) {
            subscribe(callbackContext);
            return true;
        }
        return false;
    }

    private void enable(JSONObject options, CallbackContext callbackContext) {
        JSONObject opts = options != null ? options : new JSONObject();

        TouchGuard.Mode current = guard.getMode();
        TouchGuard.Mode mode = current == TouchGuard.Mode.OFF ? TouchGuard.Mode.BLOCK : current;
        if (opts.has(OPT_MODE)) {
            TouchGuard.Mode requested = TouchGuard.Mode.fromString(opts.optString(OPT_MODE, null));
            if (requested == null || requested == TouchGuard.Mode.OFF) {
                callbackContext.error("Invalid mode '" + opts.opt(OPT_MODE)
                        + "': expected 'block' or 'detect' (use disable() to turn protection off)");
                return;
            }
            mode = requested;
        }

        Boolean strict = optBoolean(opts, OPT_STRICT, callbackContext);
        if (strict == null && opts.has(OPT_STRICT)) {
            return;
        }
        Boolean hide = optBoolean(opts, OPT_HIDE_OVERLAYS, callbackContext);
        if (hide == null && opts.has(OPT_HIDE_OVERLAYS)) {
            return;
        }

        runAndReportStatus(mode, strict != null ? strict : guard.isStrict(), hide, callbackContext);
    }

    /** @return the boolean option, or null when absent or invalid (invalid also fails the call). */
    private static Boolean optBoolean(JSONObject opts, String key, CallbackContext callbackContext) {
        if (!opts.has(key)) {
            return null;
        }
        Object value = opts.opt(key);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        callbackContext.error("Invalid " + key + " '" + value + "': expected a boolean");
        return null;
    }

    /**
     * Applies a new configuration (when {@code mode} is non-null) on the UI thread, then
     * answers the call with the resulting status.
     */
    private void runAndReportStatus(final TouchGuard.Mode mode, final boolean strict,
                                    final Boolean hide, final CallbackContext callbackContext) {
        cordova.getActivity().runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (mode != null) {
                    guard.configure(mode, strict);
                    if (hide != null) {
                        hideOverlays = hide;
                    }
                    applyProtection();
                }
                try {
                    callbackContext.success(status());
                } catch (JSONException e) {
                    callbackContext.error("Failed to build status: " + e.getMessage());
                }
            }
        });
    }

    private void subscribe(CallbackContext callbackContext) {
        List<JSONObject> backlog;
        synchronized (pendingEvents) {
            eventCallback = callbackContext;
            backlog = new ArrayList<JSONObject>(pendingEvents);
            pendingEvents.clear();
        }
        PluginResult noResult = new PluginResult(PluginResult.Status.NO_RESULT);
        noResult.setKeepCallback(true);
        callbackContext.sendPluginResult(noResult);
        for (JSONObject event : backlog) {
            sendEvent(callbackContext, event);
        }
    }

    @Override
    public void onObscuredTouch(boolean partial, boolean blocked) {
        JSONObject event = new JSONObject();
        try {
            event.put("partial", partial);
            event.put("blocked", blocked);
            event.put("timestamp", System.currentTimeMillis());
        } catch (JSONException e) {
            Log.e(TAG, "Failed to build obscured-touch event", e);
            return;
        }
        Log.w(TAG, "Obscured touch detected (partial=" + partial + ", blocked=" + blocked + ")");

        CallbackContext target;
        synchronized (pendingEvents) {
            target = eventCallback;
            if (target == null) {
                if (pendingEvents.size() >= MAX_PENDING_EVENTS) {
                    pendingEvents.remove(0);
                }
                pendingEvents.add(event);
                return;
            }
        }
        sendEvent(target, event);
    }

    private static void sendEvent(CallbackContext target, JSONObject event) {
        PluginResult result = new PluginResult(PluginResult.Status.OK, event);
        result.setKeepCallback(true);
        target.sendPluginResult(result);
    }

    // Lifecycle callbacks arrive on the UI thread. Re-applying covers activity recreation and
    // other code resetting the window callback or flags while we were in the background.
    @Override
    public void onStart() {
        applyProtection();
    }

    @Override
    public void onResume(boolean multitasking) {
        applyProtection();
    }

    @Override
    public void onReset() {
        // The page is reloading; its JS will subscribe again.
        synchronized (pendingEvents) {
            eventCallback = null;
        }
    }

    @Override
    public void onDestroy() {
        Activity activity = cordova.getActivity();
        if (activity != null && activity.getWindow() != null) {
            uninstallWindowCallback(activity.getWindow());
        }
        synchronized (pendingEvents) {
            eventCallback = null;
            pendingEvents.clear();
        }
    }

    /** Idempotent. Must run on the UI thread. */
    private void applyProtection() {
        Activity activity = cordova.getActivity();
        Window window = activity != null ? activity.getWindow() : null;
        if (window == null) {
            Log.w(TAG, "No activity window yet; protection not applied");
            return;
        }

        TouchGuard.Mode mode = guard.getMode();
        boolean block = mode == TouchGuard.Mode.BLOCK;

        setFilterTouches(window.getDecorView(), block);
        setFilterTouches(webView != null ? webView.getView() : null, block);
        filterTouchesActive = block;

        applyHideOverlays(window, mode != TouchGuard.Mode.OFF && hideOverlays);

        if (mode == TouchGuard.Mode.OFF) {
            uninstallWindowCallback(window);
        } else {
            installWindowCallback(window);
        }
    }

    private static void setFilterTouches(View view, boolean enabled) {
        if (view != null) {
            view.setFilterTouchesWhenObscured(enabled);
        }
    }

    private void applyHideOverlays(Window window, boolean enabled) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            hideOverlaysActive = false;
            return;
        }
        if (!enabled && !hideOverlaysActive) {
            return;
        }
        try {
            window.setHideOverlayWindows(enabled);
            hideOverlaysActive = enabled;
        } catch (SecurityException e) {
            // HIDE_OVERLAY_WINDOWS missing from the merged manifest.
            Log.w(TAG, "setHideOverlayWindows denied; is HIDE_OVERLAY_WINDOWS declared?", e);
            hideOverlaysActive = false;
        }
    }

    private void installWindowCallback(Window window) {
        Window.Callback current = window.getCallback();
        if (windowCallback != null && current == windowCallback) {
            return;
        }
        if (current == null) {
            Log.w(TAG, "Window has no callback; obscured-touch detection unavailable");
            return;
        }
        if (windowCallback != null) {
            // Someone replaced or wrapped our callback. Retire the old one so only one guard runs.
            windowCallback.deactivate();
        }
        windowCallback = new GuardedWindowCallback(current, guard);
        window.setCallback(windowCallback);
    }

    private void uninstallWindowCallback(Window window) {
        if (windowCallback == null) {
            return;
        }
        windowCallback.deactivate();
        if (window.getCallback() == windowCallback) {
            window.setCallback(windowCallback.getDelegate());
        }
        windowCallback = null;
    }

    private JSONObject status() throws JSONException {
        JSONObject status = new JSONObject();
        status.put("supported", true);
        status.put("platform", "android");
        status.put("sdkInt", Build.VERSION.SDK_INT);
        status.put("mode", guard.getMode().jsName);
        status.put("strict", guard.isStrict());
        status.put("hideOverlays", hideOverlays);
        status.put("filterTouchesActive", filterTouchesActive);
        status.put("hideOverlaysActive", hideOverlaysActive);
        status.put("hideOverlaysSupported", Build.VERSION.SDK_INT >= Build.VERSION_CODES.S);
        status.put("guardActive", windowCallback != null);
        return status;
    }
}
