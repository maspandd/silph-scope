package com.tapjackos.protection;

import android.os.SystemClock;
import android.view.ActionMode;
import android.view.KeyEvent;
import android.view.KeyboardShortcutGroup;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SearchEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import java.util.List;

/**
 * Wraps the activity's existing {@link Window.Callback} so every touch passes through
 * {@link TouchGuard} before the view hierarchy sees it. Every other call is forwarded
 * untouched. Once {@link #deactivate()} is called it becomes a pure pass-through, which keeps
 * the chain intact if another library wrapped us in the meantime.
 */
final class GuardedWindowCallback implements Window.Callback {

    private final Window.Callback delegate;
    private final TouchGuard guard;
    private volatile boolean active = true;

    GuardedWindowCallback(Window.Callback delegate, TouchGuard guard) {
        this.delegate = delegate;
        this.guard = guard;
    }

    Window.Callback getDelegate() {
        return delegate;
    }

    void deactivate() {
        active = false;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (!active) {
            return delegate.dispatchTouchEvent(event);
        }
        TouchGuard.Verdict verdict =
                guard.onTouch(event.getActionMasked(), event.getFlags(), SystemClock.uptimeMillis());
        switch (verdict) {
            case DROP:
                return true;
            case CANCEL_AND_DROP:
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                try {
                    delegate.dispatchTouchEvent(cancel);
                } finally {
                    cancel.recycle();
                }
                return true;
            case DELIVER:
            default:
                return delegate.dispatchTouchEvent(event);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        return delegate.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchKeyShortcutEvent(KeyEvent event) {
        return delegate.dispatchKeyShortcutEvent(event);
    }

    @Override
    public boolean dispatchTrackballEvent(MotionEvent event) {
        return delegate.dispatchTrackballEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        return delegate.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
        return delegate.dispatchPopulateAccessibilityEvent(event);
    }

    @Override
    public View onCreatePanelView(int featureId) {
        return delegate.onCreatePanelView(featureId);
    }

    @Override
    public boolean onCreatePanelMenu(int featureId, Menu menu) {
        return delegate.onCreatePanelMenu(featureId, menu);
    }

    @Override
    public boolean onPreparePanel(int featureId, View view, Menu menu) {
        return delegate.onPreparePanel(featureId, view, menu);
    }

    @Override
    public boolean onMenuOpened(int featureId, Menu menu) {
        return delegate.onMenuOpened(featureId, menu);
    }

    @Override
    public boolean onMenuItemSelected(int featureId, MenuItem item) {
        return delegate.onMenuItemSelected(featureId, item);
    }

    @Override
    public void onWindowAttributesChanged(WindowManager.LayoutParams attrs) {
        delegate.onWindowAttributesChanged(attrs);
    }

    @Override
    public void onContentChanged() {
        delegate.onContentChanged();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        delegate.onWindowFocusChanged(hasFocus);
    }

    @Override
    public void onAttachedToWindow() {
        delegate.onAttachedToWindow();
    }

    @Override
    public void onDetachedFromWindow() {
        delegate.onDetachedFromWindow();
    }

    @Override
    public void onPanelClosed(int featureId, Menu menu) {
        delegate.onPanelClosed(featureId, menu);
    }

    @Override
    public boolean onSearchRequested() {
        return delegate.onSearchRequested();
    }

    @Override
    public boolean onSearchRequested(SearchEvent searchEvent) {
        return delegate.onSearchRequested(searchEvent);
    }

    @Override
    public ActionMode onWindowStartingActionMode(ActionMode.Callback callback) {
        return delegate.onWindowStartingActionMode(callback);
    }

    @Override
    public ActionMode onWindowStartingActionMode(ActionMode.Callback callback, int type) {
        return delegate.onWindowStartingActionMode(callback, type);
    }

    @Override
    public void onActionModeStarted(ActionMode mode) {
        delegate.onActionModeStarted(mode);
    }

    @Override
    public void onActionModeFinished(ActionMode mode) {
        delegate.onActionModeFinished(mode);
    }

    @Override
    public void onProvideKeyboardShortcuts(List<KeyboardShortcutGroup> data, Menu menu, int deviceId) {
        delegate.onProvideKeyboardShortcuts(data, menu, deviceId);
    }

    @Override
    public void onPointerCaptureChanged(boolean hasCapture) {
        delegate.onPointerCaptureChanged(hasCapture);
    }
}
