package com.hsbc.pluse.sample.poc;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Runtime-tunable knobs for POC behavior.
 * Values can be updated via REST without restarting the application.
 */
@Component
public class PocRuntimeTuning {

    private final AtomicLong simulatedDbLatencyMs;
    private final AtomicLong simulatedBusinessLatencyMs;
    private final AtomicLong schedulerDelayMs;

    public PocRuntimeTuning(
            @Value("${eventflow.poc.simulated-db-latency-ms:0}") long initialDbLatencyMs,
            @Value("${eventflow.poc.simulated-business-latency-ms:10}") long initialBusinessLatencyMs,
            @Value("${eventflow.poc.scheduler-delay-ms:500}") long initialSchedulerDelayMs) {
        this.simulatedDbLatencyMs = new AtomicLong(nonNegative(initialDbLatencyMs));
        this.simulatedBusinessLatencyMs = new AtomicLong(nonNegative(initialBusinessLatencyMs));
        this.schedulerDelayMs = new AtomicLong(Math.max(1, initialSchedulerDelayMs));
    }

    public long getSimulatedDbLatencyMs() {
        return simulatedDbLatencyMs.get();
    }

    public void setSimulatedDbLatencyMs(long latencyMs) {
        simulatedDbLatencyMs.set(nonNegative(latencyMs));
    }

    public long getSimulatedBusinessLatencyMs() {
        return simulatedBusinessLatencyMs.get();
    }

    public void setSimulatedBusinessLatencyMs(long latencyMs) {
        simulatedBusinessLatencyMs.set(nonNegative(latencyMs));
    }

    public long getSchedulerDelayMs() {
        return schedulerDelayMs.get();
    }

    public void setSchedulerDelayMs(long delayMs) {
        schedulerDelayMs.set(Math.max(1, delayMs));
    }

    private long nonNegative(long value) {
        return Math.max(0, value);
    }
}
