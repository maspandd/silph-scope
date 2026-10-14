package com.tapjackos.protection;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import com.tapjackos.protection.TouchGuard.Mode;
import com.tapjackos.protection.TouchGuard.Verdict;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class TouchGuardTest {

    // MotionEvent masked actions.
    private static final int DOWN = 0;
    private static final int UP = 1;
    private static final int MOVE = 2;
    private static final int CANCEL = 3;

    private static final int CLEAN = 0;
    private static final int OBSCURED = TouchGuard.FLAG_WINDOW_IS_OBSCURED;
    private static final int PARTIAL = TouchGuard.FLAG_WINDOW_IS_PARTIALLY_OBSCURED;

    private static final long INTERVAL = 500;

    private final List<String> reports = new ArrayList<String>();
    private TouchGuard guard;

    @Before
    public void setUp() {
        guard = new TouchGuard(new TouchGuard.Reporter() {
            @Override
            public void onObscuredTouch(boolean partial, boolean blocked) {
                reports.add("partial=" + partial + ",blocked=" + blocked);
            }
        }, INTERVAL);
    }

    @Test
    public void defaultsToBlockNonStrict() {
        assertSame(Mode.BLOCK, guard.getMode());
        assertEquals(false, guard.isStrict());
    }

    @Test
    public void cleanGestureIsDeliveredWithoutReport() {
        assertEquals(Verdict.DELIVER, guard.onTouch(DOWN, CLEAN, 0));
        assertEquals(Verdict.DELIVER, guard.onTouch(MOVE, CLEAN, 10));
        assertEquals(Verdict.DELIVER, guard.onTouch(UP, CLEAN, 20));
        assertEquals(0, reports.size());
    }

    @Test
    public void blockModeDropsWholeGestureWhenDownIsObscured() {
        assertEquals(Verdict.DROP, guard.onTouch(DOWN, OBSCURED, 0));
        assertEquals(Verdict.DROP, guard.onTouch(MOVE, CLEAN, 10));
        assertEquals(Verdict.DROP, guard.onTouch(UP, CLEAN, 20));
        assertEquals(listOf("partial=false,blocked=true"), reports);
    }

    @Test
    public void blockModeCancelsGestureThatBecomesObscuredMidway() {
        assertEquals(Verdict.DELIVER, guard.onTouch(DOWN, CLEAN, 0));
        assertEquals(Verdict.CANCEL_AND_DROP, guard.onTouch(MOVE, OBSCURED, 10));
        assertEquals(Verdict.DROP, guard.onTouch(MOVE, CLEAN, 20));
        assertEquals(Verdict.DROP, guard.onTouch(UP, CLEAN, 30));
        assertEquals(listOf("partial=false,blocked=true"), reports);
    }

    @Test
    public void blockedGestureDropsItsOwnCancel() {
        guard.onTouch(DOWN, OBSCURED, 0);
        assertEquals(Verdict.DROP, guard.onTouch(CANCEL, CLEAN, 10));
    }

    @Test
    public void nextGestureStartsFresh() {
        guard.onTouch(DOWN, OBSCURED, 0);
        guard.onTouch(UP, CLEAN, 10);
        assertEquals(Verdict.DELIVER, guard.onTouch(DOWN, CLEAN, 20));
        assertEquals(Verdict.DELIVER, guard.onTouch(UP, CLEAN, 30));
    }

    @Test
    public void nonStrictDeliversPartiallyObscuredButReportsIt() {
        assertEquals(Verdict.DELIVER, guard.onTouch(DOWN, PARTIAL, 0));
        assertEquals(Verdict.DELIVER, guard.onTouch(UP, PARTIAL, 10));
        assertEquals(listOf("partial=true,blocked=false"), reports);
    }

    @Test
    public void strictBlocksPartiallyObscured() {
        guard.configure(Mode.BLOCK, true);
        assertEquals(Verdict.DROP, guard.onTouch(DOWN, PARTIAL, 0));
        assertEquals(Verdict.DROP, guard.onTouch(UP, CLEAN, 10));
        assertEquals(listOf("partial=true,blocked=true"), reports);
    }

    @Test
    public void bothFlagsCountAsFullyObscured() {
        guard.onTouch(DOWN, OBSCURED | PARTIAL, 0);
        assertEquals(listOf("partial=false,blocked=true"), reports);
    }

    @Test
    public void detectModeDeliversAndReports() {
        guard.configure(Mode.DETECT, true);
        assertEquals(Verdict.DELIVER, guard.onTouch(DOWN, OBSCURED, 0));
        assertEquals(Verdict.DELIVER, guard.onTouch(UP, OBSCURED, 10));
        assertEquals(listOf("partial=false,blocked=false"), reports);
    }

    @Test
    public void offModeDeliversEverythingSilently() {
        guard.configure(Mode.OFF, true);
        assertEquals(Verdict.DELIVER, guard.onTouch(DOWN, OBSCURED, 0));
        assertEquals(Verdict.DELIVER, guard.onTouch(UP, OBSCURED | PARTIAL, 10));
        assertEquals(0, reports.size());
    }

    @Test
    public void switchingOffMidGestureStopsDropping() {
        guard.onTouch(DOWN, OBSCURED, 0);
        guard.configure(Mode.OFF, false);
        assertEquals(Verdict.DELIVER, guard.onTouch(UP, CLEAN, 10));
    }

    @Test
    public void reportsOncePerGesture() {
        guard.configure(Mode.DETECT, false);
        guard.onTouch(DOWN, OBSCURED, 0);
        guard.onTouch(MOVE, OBSCURED, 10);
        guard.onTouch(UP, OBSCURED, 20);
        assertEquals(1, reports.size());
    }

    @Test
    public void throttlesReportsAcrossGestures() {
        tapObscured(0);
        tapObscured(100);
        assertEquals(1, reports.size());
        tapObscured(INTERVAL);
        assertEquals(2, reports.size());
    }

    @Test
    public void escalationToBlockedIsReportedEvenWhenThrottled() {
        tapObscured(0);
        // Partial-only, inside the throttle window: suppressed.
        guard.onTouch(DOWN, PARTIAL, 100);
        assertEquals(1, reports.size());
        // Same gesture now fully obscured: the app must hear that its touch was eaten.
        assertEquals(Verdict.CANCEL_AND_DROP, guard.onTouch(MOVE, OBSCURED, 110));
        assertEquals(listOf("partial=false,blocked=true", "partial=false,blocked=true"), reports);
    }

    @Test
    public void escalationAfterUnblockedReport() {
        guard.onTouch(DOWN, PARTIAL, 0);
        guard.onTouch(MOVE, OBSCURED, 10);
        guard.onTouch(MOVE, OBSCURED, 20);
        assertEquals(listOf("partial=true,blocked=false", "partial=false,blocked=true"), reports);
    }

    @Test
    public void modeFromString() {
        assertSame(Mode.BLOCK, Mode.fromString("block"));
        assertSame(Mode.BLOCK, Mode.fromString("BLOCK"));
        assertSame(Mode.DETECT, Mode.fromString(" detect "));
        assertSame(Mode.OFF, Mode.fromString("off"));
        assertNull(Mode.fromString("bogus"));
        assertNull(Mode.fromString(""));
        assertNull(Mode.fromString(null));
    }

    private void tapObscured(long at) {
        guard.onTouch(DOWN, OBSCURED, at);
        guard.onTouch(UP, CLEAN, at + 1);
    }

    private static List<String> listOf(String... items) {
        List<String> list = new ArrayList<String>();
        for (String item : items) {
            list.add(item);
        }
        return list;
    }
}
