package com.hsbc.pluse.dispatch;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe performance statistics for the Dispatcher.
 * Automatically updated by framework — zero business code dependency.
 */
public class DispatcherStats {

    private final AtomicLong totalDispatched = new AtomicLong();
    private final AtomicLong totalCompleted = new AtomicLong();
    private final AtomicLong totalSuccess = new AtomicLong();
    private final AtomicLong totalFiltered = new AtomicLong();
    private final AtomicLong totalFailed = new AtomicLong();
    private final AtomicLong totalDuplicate = new AtomicLong();

    private final LongAdder totalLatencyNanos = new LongAdder();
    private final AtomicLong maxLatencyNanos = new AtomicLong();
    private final AtomicLong startTimeNanos = new AtomicLong(System.nanoTime());

    // Reservoir sampling for percentile calculation (fixed-size buffer)
    private static final int RESERVOIR_SIZE = 4096;
    private final long[] latencyReservoir = new long[RESERVOIR_SIZE];
    private final AtomicLong reservoirCount = new AtomicLong();

    public void recordDispatched() {
        totalDispatched.incrementAndGet();
    }

    public void recordCompleted(String resultType, long latencyNanos) {
        totalCompleted.incrementAndGet();
        totalLatencyNanos.add(latencyNanos);

        // Update max latency (CAS loop)
        long currentMax;
        do {
            currentMax = maxLatencyNanos.get();
            if (latencyNanos <= currentMax) break;
        } while (!maxLatencyNanos.compareAndSet(currentMax, latencyNanos));

        // Reservoir sampling
        long idx = reservoirCount.getAndIncrement();
        if (idx < RESERVOIR_SIZE) {
            latencyReservoir[(int) idx] = latencyNanos;
        } else {
            // Vitter's Algorithm R: replace with probability RESERVOIR_SIZE/idx
            long j = (long) (Math.random() * (idx + 1));
            if (j < RESERVOIR_SIZE) {
                latencyReservoir[(int) j] = latencyNanos;
            }
        }

        switch (resultType) {
            case "SUCCESS" -> totalSuccess.incrementAndGet();
            case "FILTERED" -> totalFiltered.incrementAndGet();
            case "FAILED" -> totalFailed.incrementAndGet();
            case "DUPLICATE" -> totalDuplicate.incrementAndGet();
        }
    }

    public Snapshot snapshot() {
        long completed = totalCompleted.get();
        long elapsedNanos = System.nanoTime() - startTimeNanos.get();
        double elapsedSec = elapsedNanos / 1_000_000_000.0;
        double throughput = elapsedSec > 0 ? completed / elapsedSec : 0;

        int sampleSize = (int) Math.min(reservoirCount.get(), RESERVOIR_SIZE);
        long[] sorted = new long[sampleSize];
        System.arraycopy(latencyReservoir, 0, sorted, 0, sampleSize);
        java.util.Arrays.sort(sorted);

        double avgMs = completed > 0 ? (totalLatencyNanos.sum() / (double) completed) / 1_000_000.0 : 0;
        double p50Ms = percentile(sorted, 0.50);
        double p95Ms = percentile(sorted, 0.95);
        double p99Ms = percentile(sorted, 0.99);
        double maxMs = maxLatencyNanos.get() / 1_000_000.0;

        return new Snapshot(
                totalDispatched.get(), completed,
                totalSuccess.get(), totalFiltered.get(),
                totalFailed.get(), totalDuplicate.get(),
                throughput, avgMs, p50Ms, p95Ms, p99Ms, maxMs
        );
    }

    public void reset() {
        totalDispatched.set(0);
        totalCompleted.set(0);
        totalSuccess.set(0);
        totalFiltered.set(0);
        totalFailed.set(0);
        totalDuplicate.set(0);
        totalLatencyNanos.reset();
        maxLatencyNanos.set(0);
        reservoirCount.set(0);
        startTimeNanos.set(System.nanoTime());
    }

    private double percentile(long[] sorted, double p) {
        if (sorted.length == 0) return 0;
        int idx = (int) Math.ceil(p * sorted.length) - 1;
        if (idx < 0) idx = 0;
        return sorted[idx] / 1_000_000.0;
    }

    public record Snapshot(
            long totalDispatched, long totalCompleted,
            long totalSuccess, long totalFiltered,
            long totalFailed, long totalDuplicate,
            double throughputPerSec,
            double avgLatencyMs, double p50LatencyMs,
            double p95LatencyMs, double p99LatencyMs,
            double maxLatencyMs
    ) {}
}
