package com.hsbc.pluse.dispatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-partition offset tracking with reference counting and sliding-window commit.
 * Lock-free: register/committableOffset by poll thread, complete by Worker threads (CAS).
 */
public class OffsetTracker {

    private static final Logger log = LoggerFactory.getLogger(OffsetTracker.class);

    private final String partitionId;
    private final ConcurrentSkipListMap<Long, OffsetEntry> window = new ConcurrentSkipListMap<>();

    public OffsetTracker(String partitionId) {
        this.partitionId = partitionId;
    }

    public void register(long offset, int pipelineCount) {
        window.put(offset, new OffsetEntry(pipelineCount, Instant.now()));
    }

    public void complete(long offset) {
        OffsetEntry entry = window.get(offset);
        if (entry != null) {
            entry.remaining().decrementAndGet();
        }
    }

    public OptionalLong committableOffset() {
        long lastDone = -1;
        Iterator<Map.Entry<Long, OffsetEntry>> it = window.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, OffsetEntry> entry = it.next();
            if (entry.getValue().remaining().get() <= 0) {
                lastDone = entry.getKey();
                it.remove();
            } else {
                break;
            }
        }
        return lastDone >= 0 ? OptionalLong.of(lastDone + 1) : OptionalLong.empty();
    }

    public int inflightCount() {
        return window.size();
    }

    public Duration oldestPendingAge() {
        Map.Entry<Long, OffsetEntry> first = window.firstEntry();
        if (first == null) return Duration.ZERO;
        return Duration.between(first.getValue().registeredAt(), Instant.now());
    }

    public OptionalLong oldestPendingOffset() {
        Map.Entry<Long, OffsetEntry> first = window.firstEntry();
        return first != null ? OptionalLong.of(first.getKey()) : OptionalLong.empty();
    }

    public void awaitDrain(Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!window.isEmpty() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (!window.isEmpty()) {
            log.warn("OffsetTracker [{}] drain timeout, {} offsets remaining", partitionId, window.size());
        }
    }

    public String getPartitionId() {
        return partitionId;
    }

    private record OffsetEntry(AtomicInteger remaining, Instant registeredAt) {
        OffsetEntry(int count, Instant registeredAt) {
            this(new AtomicInteger(count), registeredAt);
        }
    }
}
