package com.hsbc.pluse.kafka;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsbc.pluse.model.Envelope;
import com.hsbc.pluse.model.SourceMeta;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

/**
 * Converts Kafka ConsumerRecord (CDC JSON) into Envelope.
 * Uses lenient JSON deserialization to handle unknown fields gracefully.
 */
public class KafkaEnvelopeConverter {

    private static final Logger log = LoggerFactory.getLogger(KafkaEnvelopeConverter.class);

    private final ObjectMapper objectMapper;

    public KafkaEnvelopeConverter() {
        this.objectMapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .configure(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES, false);
    }

    public Envelope convert(ConsumerRecord<String, byte[]> record) throws IOException {
        JsonNode root = objectMapper.readTree(record.value());

        // Extract CDC fields — adapt to your OceanBase CDC JSON structure
        String table = extractText(root, "table", "__unknown__");
        String op = extractText(root, "op", "UNKNOWN");
        String pk = record.key() != null ? record.key() : extractText(root, "pk", "");

        JsonNode data = root.has("data") ? root.get("data") : root;

        return Envelope.builder()
                .id(UUID.randomUUID().toString())
                .source("cdc://kafka/" + record.topic())
                .type("cdc.row_change")
                .time(Instant.ofEpochMilli(record.timestamp()))
                .subject(table + ":" + pk)
                .data(data)
                .header("table", table)
                .header("op", op)
                .header("pk", pk)
                .header("kafka.topic", record.topic())
                .header("kafka.partition", String.valueOf(record.partition()))
                .header("kafka.offset", String.valueOf(record.offset()))
                .sourceMeta(new SourceMeta(record.topic(), record.partition(), record.offset()))
                .build();
    }

    private String extractText(JsonNode root, String field, String defaultValue) {
        JsonNode node = root.get(field);
        return node != null && !node.isNull() ? node.asText() : defaultValue;
    }
}
