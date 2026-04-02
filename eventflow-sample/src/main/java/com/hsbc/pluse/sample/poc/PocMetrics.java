package com.hsbc.pluse.sample.poc;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * POC metrics for end-to-end latency comparison between Strategy A and B.
 */
@Component
public class PocMetrics {
    private static final long SECONDS_EPOCH_THRESHOLD = 10_000_000_000L;
    private static final long MAX_E2E_MS = Duration.ofHours(24).toMillis();

    private final Timer e2eTimerA;
    private final Timer e2eTimerB;
    private final Timer storeDelayTimerB;
    private final Counter processedCounterA;
    private final Counter processedCounterB;

    public PocMetrics(MeterRegistry registry) {
        this.e2eTimerA = Timer.builder("poc.e2e.duration")
                .tag("strategy", "A")
                .description("End-to-end latency from message send to processing complete")
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofMinutes(5))
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry);

        this.e2eTimerB = Timer.builder("poc.e2e.duration")
                .tag("strategy", "B")
                .description("End-to-end latency from message send to processing complete")
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofMinutes(5))
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry);

        this.storeDelayTimerB = Timer.builder("poc.store.delay")
                .tag("strategy", "B")
                .description("Store-to-process delay for Strategy B")
                .minimumExpectedValue(Duration.ofMillis(1))
                .maximumExpectedValue(Duration.ofMinutes(5))
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry);

        this.processedCounterA = Counter.builder("poc.events.processed")
                .tag("strategy", "A")
                .description("Total events processed")
                .register(registry);

        this.processedCounterB = Counter.builder("poc.events.processed")
                .tag("strategy", "B")
                .description("Total events processed")
                .register(registry);
    }

    public void recordE2eLatency(String strategy, long sendTimestamp) {
        long nowMs = System.currentTimeMillis();
        long normalizedSendTsMs = normalizeSendTimestampMs(sendTimestamp);
        long e2eMs = nowMs - normalizedSendTsMs;

        if ("A".equals(strategy)) {
            processedCounterA.increment();
            if (isValidE2e(e2eMs)) {
                e2eTimerA.record(e2eMs, TimeUnit.MILLISECONDS);
            }
        } else {
            processedCounterB.increment();
            if (isValidE2e(e2eMs)) {
                e2eTimerB.record(e2eMs, TimeUnit.MILLISECONDS);
            }
        }
    }

    public void recordStoreDelay(long storeDelayMs) {
        storeDelayTimerB.record(storeDelayMs, TimeUnit.MILLISECONDS);
    }

    private long normalizeSendTimestampMs(long sendTimestamp) {
        // Some producers may send epoch seconds instead of milliseconds.
        if (sendTimestamp > 0 && sendTimestamp < SECONDS_EPOCH_THRESHOLD) {
            return sendTimestamp * 1000L;
        }
        return sendTimestamp;
    }

    private boolean isValidE2e(long e2eMs) {
        return e2eMs >= 0 && e2eMs <= MAX_E2E_MS;
    }
}
