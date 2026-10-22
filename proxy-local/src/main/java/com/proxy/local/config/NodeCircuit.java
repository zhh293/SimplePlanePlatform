package com.proxy.local.config;

/** Per-node state, owned by the controller's control thread. All times are monotonic milliseconds. */
final class NodeCircuit {
    enum State { CLOSED, OPEN, HALF_OPEN }
    static final long GRACE_MS = 15000, HOLD_MS = 30000, DISCONNECTED_MS = 5000;
    static final long CHECK_INTERVAL_MS = 5000, STABLE_MS = 60000;
    State state = State.CLOSED;
    long openUntil, activatedAt, disconnectedAt = -1, nextCheckAt;
    int failures, recoverySuccesses, openings;
    boolean blocked, observedResponse;
    String reason = "";

    boolean eligible() { return !blocked && state == State.CLOSED; }

    void activate(long now) {
        activatedAt = now;
        failures = 0;
        observedResponse = false;
    }

    boolean disconnected(long now, boolean available) {
        if (available) { disconnectedAt = -1; return false; }
        if (disconnectedAt < 0) disconnectedAt = now;
        return now - disconnectedAt >= DISCONNECTED_MS;
    }

    boolean failedCheck(long now) {
        nextCheckAt = now + CHECK_INTERVAL_MS;
        if (now - activatedAt < GRACE_MS) return false;
        failures++;
        return failures >= 3 && now - activatedAt >= HOLD_MS;
    }

    void response(long now) {
        failures = 0;
        observedResponse = true;
        if (now - activatedAt >= STABLE_MS) openings = 0;
    }

    void trip(long now, String cause, double jitter) {
        state = State.OPEN;
        long cooldown = Math.min(300000L, 30000L << Math.min(openings, 4));
        openUntil = now + cooldown + (long) (cooldown * Math.max(0, Math.min(0.2, jitter)));
        openings++;
        failures = recoverySuccesses = 0;
        disconnectedAt = -1;
        reason = cause;
    }

    boolean beginRecovery(long now) {
        if (blocked || state != State.OPEN || now < openUntil) return false;
        state = State.HALF_OPEN;
        recoverySuccesses = 0;
        nextCheckAt = now;
        return true;
    }

    boolean recoveredResponse(long now) {
        if (state != State.HALF_OPEN || now < nextCheckAt) return false;
        nextCheckAt = now + CHECK_INTERVAL_MS;
        if (++recoverySuccesses < 2) return false;
        state = State.CLOSED;
        disconnectedAt = -1;
        reason = "";
        return true;
    }
}
