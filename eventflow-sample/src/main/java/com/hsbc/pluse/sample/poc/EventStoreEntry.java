package com.hsbc.pluse.sample.poc;

import com.hsbc.pluse.model.Envelope;

import java.time.Instant;

/**
 * Represents an event stored in PostgreSQL for Strategy B (store-and-process).
 */
public record EventStoreEntry(
    String id,
    String routeKey,
    String envelopeJson,
    String parentTraceId,
    String parentSpanId,
    Long sendTimestamp,
    Instant createdAt,
    Instant processedAt,
    String status
) {
    public static EventStoreEntry fromEnvelope(Envelope envelope, String traceId, String spanId, Long sendTimestamp) {
        return new EventStoreEntry(
            envelope.getId(),
            envelope.getSubject(),
            envelope.toString(),
            traceId,
            spanId,
            sendTimestamp,
            Instant.now(),
            null,
            "PENDING"
        );
    }

    public boolean isPending() {
        return "PENDING".equals(status);
    }

    public EventStoreEntry withStatus(String newStatus) {
        return new EventStoreEntry(
            id, routeKey, envelopeJson, parentTraceId, parentSpanId,
            sendTimestamp, createdAt,
            "DONE".equals(newStatus) ? Instant.now() : processedAt,
            newStatus
        );
    }
}
