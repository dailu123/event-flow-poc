package com.hsbc.pluse.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsbc.pluse.model.Destination;
import com.hsbc.pluse.port.OutboundPort;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes pipeline output to Kafka topics.
 */
public class KafkaOutboundAdapter implements OutboundPort {

    private static final Logger log = LoggerFactory.getLogger(KafkaOutboundAdapter.class);

    private final KafkaProducer<String, byte[]> producer;
    private final ObjectMapper objectMapper;

    public KafkaOutboundAdapter(KafkaProducer<String, byte[]> producer) {
        this.producer = producer;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String channel() {
        return "kafka";
    }

    @Override
    public void publish(Destination destination) {
        try {
            byte[] payload = objectMapper.writeValueAsBytes(destination.payload());
            ProducerRecord<String, byte[]> record = new ProducerRecord<>(destination.target(), payload);
            producer.send(record, (metadata, exception) -> {
                if (exception != null) {
                    log.error("Failed to publish to {}: {}", destination.target(), exception.getMessage());
                } else {
                    log.debug("Published to {} partition={} offset={}",
                            metadata.topic(), metadata.partition(), metadata.offset());
                }
            });
        } catch (Exception e) {
            log.error("Failed to serialize payload for {}", destination.target(), e);
            throw new RuntimeException("Outbound publish failed", e);
        }
    }
}
