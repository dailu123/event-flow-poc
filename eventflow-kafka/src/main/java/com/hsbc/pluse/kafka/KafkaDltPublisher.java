package com.hsbc.pluse.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * Publishes poison messages or failed messages to a Dead Letter Topic.
 */
public class KafkaDltPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaDltPublisher.class);

    private final KafkaProducer<String, byte[]> producer;
    private final String dltTopicSuffix;

    public KafkaDltPublisher(KafkaProducer<String, byte[]> producer, String dltTopicSuffix) {
        this.producer = producer;
        this.dltTopicSuffix = dltTopicSuffix;
    }

    public void sendToDlt(ConsumerRecord<String, byte[]> record, Throwable cause) {
        String dltTopic = record.topic() + dltTopicSuffix;

        ProducerRecord<String, byte[]> dltRecord = new ProducerRecord<>(dltTopic, record.key(), record.value());
        dltRecord.headers().add("X-DLT-Original-Topic", record.topic().getBytes(StandardCharsets.UTF_8));
        dltRecord.headers().add("X-DLT-Original-Partition", String.valueOf(record.partition()).getBytes(StandardCharsets.UTF_8));
        dltRecord.headers().add("X-DLT-Original-Offset", String.valueOf(record.offset()).getBytes(StandardCharsets.UTF_8));
        dltRecord.headers().add("X-DLT-Error", cause.getMessage() != null
                ? cause.getMessage().getBytes(StandardCharsets.UTF_8)
                : "unknown".getBytes(StandardCharsets.UTF_8));

        try {
            producer.send(dltRecord).get(); // sync send to ensure DLT delivery
            log.warn("Sent to DLT: topic={}, partition={}, offset={}, error={}",
                    record.topic(), record.partition(), record.offset(), cause.getMessage());
        } catch (Exception e) {
            log.error("Failed to send to DLT: topic={}, offset={}", dltTopic, record.offset(), e);
            throw new RuntimeException("DLT publish failed", e);
        }
    }
}
