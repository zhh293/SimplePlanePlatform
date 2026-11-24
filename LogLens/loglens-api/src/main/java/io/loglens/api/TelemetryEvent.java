package io.loglens.api;

import java.util.Objects;
import java.util.UUID;

/** Immutable, payload-free proxy telemetry event. Byte counts are cumulative per session. */
public record TelemetryEvent(String eventId, String sessionId, long captureSequence,
                             long eventTime, long capturedAt, long monotonicNanos,
                             TelemetryKind kind, String connectionId, String streamId,
                             String peerAddress, String transport, String targetHost, int targetPort,
                             String status, String closeReason, long uplinkBytes, long downlinkBytes,
                             String deviceId, String accountId, String proxySessionId) {
    public TelemetryEvent(String eventId, String sessionId, long captureSequence,
                          long eventTime, long capturedAt, long monotonicNanos, TelemetryKind kind,
                          String connectionId, String streamId, String peerAddress, String transport, String targetHost,
                          int targetPort, String status, String closeReason, long uplinkBytes, long downlinkBytes) {
        this(eventId, sessionId, captureSequence, eventTime, capturedAt, monotonicNanos, kind, connectionId, streamId,
                peerAddress, transport, targetHost, targetPort, status, closeReason, uplinkBytes, downlinkBytes,
                null, null, null);
    }
    public TelemetryEvent {
        eventId = eventId == null ? UUID.randomUUID().toString() : eventId;
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(kind, "kind");
        if (captureSequence < 0 || eventTime < 0 || capturedAt < 0 || monotonicNanos < 0
                || targetPort < 0 || targetPort > 65535 || uplinkBytes < 0 || downlinkBytes < 0)
            throw new IllegalArgumentException("invalid telemetry event value");
        transport = transport == null ? "unknown" : transport;
        status = status == null ? "unknown" : status;
    }
    public TelemetryEvent withCaptureSequence(long value) {
        return new TelemetryEvent(eventId, sessionId, value, eventTime, capturedAt, monotonicNanos,
                kind, connectionId, streamId, peerAddress, transport, targetHost, targetPort,
                status, closeReason, uplinkBytes, downlinkBytes, deviceId, accountId, proxySessionId);
    }
}
