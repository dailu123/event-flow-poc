package com.hsbc.pluse.sample.poc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hsbc.pluse.observe.EventFlowTracer;
import io.opentelemetry.api.trace.Span;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Java-embedded Kafka load generator for POC.
 * Directly writes messages to t-poc-events topic (no HTTP layer).
 *
 * Activate with: --poc.load-test=true
 * Configure with: --poc.load-test.total=10000 --poc.load-test.threads=4
 */
@Component
@ConditionalOnProperty(name = "poc.load-test", havingValue = "true")
public class PocLoadGenerator implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(PocLoadGenerator.class);
    private static final String TOPIC = "t-poc-events";

    @Value("${poc.load-test.total:10000}")
    private int totalEvents;

    @Value("${poc.load-test.threads:4}")
    private int threads;

    @Value("${poc.load-test.wait-ack:false}")
    private boolean waitAck;

    @Value("${poc.load-test.acks:0}")
    private String acks;

    @Value("${eventflow.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    private final EventFlowTracer tracer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PocLoadGenerator(EventFlowTracer tracer) {
        this.tracer = tracer;
    }

    @Override
    public void run(String... args) throws Exception {
        // Wait for KafkaInboundAdapter to start consuming
        log.info("=== POC Load Generator ===");
        log.info("Waiting 5s for framework to initialize...");
        Thread.sleep(5000);

        log.info("Starting load test: {} events, {} threads, topic={}, broker={}, waitAck={}, acks={}",
            totalEvents, threads, TOPIC, bootstrapServers, waitAck, acks);

        // Create KafkaProducer
        Properties props = new Properties();
        props.put("bootstrap.servers", bootstrapServers);
        props.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        props.put("value.serializer", "org.apache.kafka.common.serialization.ByteArraySerializer");
        props.put("acks", acks);
        props.put("linger.ms", waitAck ? "5" : "20");
        props.put("batch.size", waitAck ? "16384" : "262144");

        KafkaProducer<String, byte[]> producer = new KafkaProducer<>(props);
        Random random = new Random();

        AtomicInteger sent = new AtomicInteger(0);
        AtomicInteger errors = new AtomicInteger(0);
        AtomicLong totalSendLatencyNanos = new AtomicLong(0);

        long startTime = System.currentTimeMillis();

        // Concurrent sending
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        int eventsPerThread = totalEvents / threads;

        CountDownLatch latch = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            int threadId = t;
            int startIdx = t * eventsPerThread;
            int endIdx = (t == threads - 1) ? totalEvents : startIdx + eventsPerThread;

            executor.submit(() -> {
                try {
                    for (int i = startIdx; i < endIdx; i++) {
                        String eventId = "E-" + i;
                        int amount = random.nextInt(50000);

                        try {
                            tracer.tracedProducer("poc.kafka.send", "t_poc", eventId, TOPIC, () -> {
                                Span.current().setAttribute("poc.phase", "send");
                                Span.current().setAttribute("poc.component", "producer");
                                Span.current().setAttribute("poc.load.threadId", threadId);
                                Span.current().setAttribute("poc.load.waitAck", waitAck);

                                long sendTimestamp = System.currentTimeMillis();
                                String sendTraceId = Span.current().getSpanContext().getTraceId();
                                String sendSpanId = Span.current().getSpanContext().getSpanId();

                                Map<String, Object> data = new LinkedHashMap<>();
                                data.put("amount", amount);
                                data.put("timestamp", sendTimestamp);
                                data.put("sendTimestamp", sendTimestamp);
                                data.put("sendTraceId", sendTraceId);
                                data.put("sendSpanId", sendSpanId);
                                data.put("producerTopic", TOPIC);
                                data.put("producerThreadId", threadId);

                                Map<String, Object> message = new LinkedHashMap<>();
                                message.put("table", "t_poc");
                                message.put("op", "INSERT");
                                message.put("pk", eventId);
                                message.put("data", data);

                                try {
                                    byte[] payload = objectMapper.writeValueAsBytes(message);
                                    long sendStart = System.nanoTime();
                                    if (waitAck) {
                                        Future<RecordMetadata> future = producer.send(
                                            new ProducerRecord<>(TOPIC, eventId, payload));
                                        future.get();
                                        long sendLatencyNanos = System.nanoTime() - sendStart;
                                        Span.current().setAttribute("poc.sendAckLatencyMs", sendLatencyNanos / 1_000_000.0);
                                        totalSendLatencyNanos.addAndGet(sendLatencyNanos);
                                    } else {
                                        producer.send(new ProducerRecord<>(TOPIC, eventId, payload), (metadata, ex) -> {
                                            if (ex != null) {
                                                errors.incrementAndGet();
                                            }
                                        });
                                    }
                                } catch (Exception sendEx) {
                                    throw new RuntimeException("send failed for " + eventId, sendEx);
                                }
                                return null;
                            });
                        } catch (Exception e) {
                            errors.incrementAndGet();
                            log.error("Failed to send event {}", eventId, e);
                        }

                        int count = sent.incrementAndGet();
                        if (count % 1000 == 0) {
                            long elapsed = System.currentTimeMillis() - startTime;
                            double rps = count * 1000.0 / elapsed;
                            log.info("Sent {}/{} events ({} err), {} msg/s",
                                count, totalEvents, errors.get(), rps);
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                    log.error("Thread {} send error", threadId, e);
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        if (waitAck) {
            producer.flush();
            producer.close(Duration.ofSeconds(30));
        } else {
            producer.close(Duration.ofSeconds(1));
        }
        executor.shutdown();

        long sendDuration = System.currentTimeMillis() - startTime;
        double sendRps = totalEvents * 1000.0 / sendDuration;
        double avgSendLatencyMs = waitAck && sent.get() > 0
            ? totalSendLatencyNanos.get() / 1_000_000.0 / sent.get()
            : 0;

        log.info("========================================");
        log.info("=== SEND PHASE COMPLETE ===");
        log.info("Total sent:     {}", sent.get());
        log.info("Errors:         {}", errors.get());
        log.info("Duration:       {} ms", sendDuration);
        log.info("Throughput:     {} msg/s", sendRps);
        log.info("Avg send lat:   {} ms", waitAck ? avgSendLatencyMs : "N/A (async no-ack)");
        log.info("========================================");

        log.info("========================================");
        log.info("=== LOAD TEST SEND COMPLETE (no wait) ===");
        log.info("========================================");
    }
}
