package com.tapjackos.protection;

import java.util.Locale;

/**
 * Decides, per touch event, whether a touch that arrived while the window was
 * obscured by another window should be delivered to the app.
 *
 * Pure Java on purpose: it only looks at {@code MotionEvent} action/flag ints so it
 * can be unit tested on a plain JVM. {@link #onTouch} and {@link #configure} must be called
 * from the UI thread.
 */
final class TouchGuard {

    /** Mirrors {@code MotionEvent.FLAG_WINDOW_IS_OBSCURED} (API 9). */
    static final int FLAG_WINDOW_IS_OBSCURED = 0x1;
    /** Mirrors {@code MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED} (API 29). Never set on API 28. */
    static final int FLAG_WINDOW_IS_PARTIALLY_OBSCURED = 0x2;

    /** Mirrors {@code MotionEvent.ACTION_DOWN}; callers pass {@code getActionMasked()}. */
    static final int ACTION_DOWN = 0;

    enum Mode {
        OFF("off"),
        DETECT("detect"),
        BLOCK("block");

        final String jsName;

        Mode(String jsName) {
            this.jsName = jsName;
        }

        /** @return the matching mode, or {@code null} when {@code value} is not a known mode. */
        static Mode fromString(String value) {
            if (value == null) {
                return null;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (Mode mode : values()) {
                if (mode.jsName.equals(normalized)) {
                    return mode;
                }
            }
            return null;
        }
    }

    enum Verdict {
        /** Pass the event to the app unchanged. */
        DELIVER,
        /** Swallow the event. */
        DROP,
        /** The app already saw part of this gesture: send it an ACTION_CANCEL, then swallow. */
        CANCEL_AND_DROP
    }

    interface Reporter {
        /**
         * @param partial true when only another part of the window was covered, not the touch point
         * @param blocked true when the gesture was blocked
         */
        void onObscuredTouch(boolean partial, boolean blocked);
    }

    private final Reporter reporter;
    private final long minReportIntervalMs;

    // Written on the UI thread; volatile so the Cordova exec thread can read the current config.
    private volatile Mode mode = Mode.BLOCK;
    private volatile boolean strict;

    private boolean gestureBlocked;
    private boolean gestureReported;
    private boolean gestureReportedBlocked;
    private long lastReportAtMs;
    private boolean hasReported;

    TouchGuard(Reporter reporter, long minReportIntervalMs) {
        this.reporter = reporter;
        this.minReportIntervalMs = minReportIntervalMs;
    }

    Mode getMode() {
        return mode;
    }

    boolean isStrict() {
        return strict;
    }

    void configure(Mode mode, boolean strict) {
        this.mode = mode;
        this.strict = strict;
    }

    /**
     * @param actionMasked {@code MotionEvent.getActionMasked()}
     * @param flags        {@code MotionEvent.getFlags()}
     * @param nowMs        a monotonic clock, used only to throttle reports
     */
    Verdict onTouch(int actionMasked, int flags, long nowMs) {
        if (actionMasked == ACTION_DOWN) {
            gestureBlocked = false;
            gestureReported = false;
            gestureReportedBlocked = false;
        }
        if (mode == Mode.OFF) {
            gestureBlocked = false;
            return Verdict.DELIVER;
        }

        boolean fully = (flags & FLAG_WINDOW_IS_OBSCURED) != 0;
        boolean partially = (flags & FLAG_WINDOW_IS_PARTIALLY_OBSCURED) != 0;
        boolean shouldBlock = mode == Mode.BLOCK && (fully || (strict && partially));

        Verdict verdict;
        if (gestureBlocked) {
            verdict = Verdict.DROP;
        } else if (shouldBlock) {
            gestureBlocked = true;
            verdict = actionMasked == ACTION_DOWN ? Verdict.DROP : Verdict.CANCEL_AND_DROP;
        } else {
            verdict = Verdict.DELIVER;
        }

        if (fully || partially) {
            maybeReport(!fully, nowMs);
        }
        return verdict;
    }

    /**
     * Reports at most once per gesture, plus once more if the gesture escalates to blocked.
     * The first report of a new gesture is throttled to {@code minReportIntervalMs}; an
     * escalation is never throttled because the app needs to know its touch was eaten.
     */
    private void maybeReport(boolean partial, long nowMs) {
        boolean escalation = gestureReported && gestureBlocked && !gestureReportedBlocked;
        if (gestureReported && !escalation) {
            return;
        }
        if (!escalation && hasReported && nowMs - lastReportAtMs < minReportIntervalMs) {
            gestureReported = true;
            gestureReportedBlocked = gestureBlocked;
            return;
        }
        gestureReported = true;
        gestureReportedBlocked = gestureBlocked;
        hasReported = true;
        lastReportAtMs = nowMs;
        reporter.onObscuredTouch(partial, gestureBlocked);
    }
}
