package com.hsbc.pluse.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsbc.pluse.model.Envelope;
import com.hsbc.pluse.model.SourceMeta;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Convenient factory methods for constructing test Envelopes.
 */
public final class EnvelopeFixtures {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EnvelopeFixtures() {}

    public static Envelope cdcInsert(String table, String pk, Map<String, Object> data) {
        return cdcEvent(table, pk, "INSERT", data);
    }

    public static Envelope cdcUpdate(String table, String pk, Map<String, Object> data) {
        return cdcEvent(table, pk, "UPDATE", data);
    }

    public static Envelope cdcDelete(String table, String pk) {
        return cdcEvent(table, pk, "DELETE", Map.of());
    }

    public static Envelope cdcEvent(String table, String pk, String op, Map<String, Object> data) {
        return Envelope.builder()
                .id(UUID.randomUUID().toString())
                .source("test://mock")
                .type("cdc.row_change")
                .time(Instant.now())
                .subject(table + ":" + pk)
                .data(MAPPER.valueToTree(data))
                .header("table", table)
                .header("op", op)
                .header("pk", pk)
                .sourceMeta(new SourceMeta("test-topic", 0, 0))
                .build();
    }
}
