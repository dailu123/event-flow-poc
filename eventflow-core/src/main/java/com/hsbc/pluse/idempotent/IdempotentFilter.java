package com.hsbc.pluse.idempotent;

import com.hsbc.pluse.model.Envelope;

/**
 * SPI for deduplication. Implementations can use local cache, Redis, etc.
 */
public interface IdempotentFilter {

    boolean isDuplicate(Envelope envelope);

    default String deduplicationKey(Envelope envelope) {
        var h = envelope.getHeaders();
        if (h.containsKey("txId")) {
            return h.get("txId");
        }
        return h.getOrDefault("table", "") + ":"
                + h.getOrDefault("pk", "") + ":"
                + envelope.getTime().toEpochMilli();
    }
}
