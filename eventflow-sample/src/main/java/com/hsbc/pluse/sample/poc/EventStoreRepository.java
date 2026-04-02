package com.hsbc.pluse.sample.poc;

import com.hsbc.pluse.model.Envelope;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Repository
public class EventStoreRepository {

    private static final String STRATEGY_TAG = "B";
    private final JdbcTemplate jdbcTemplate;
    private final PocRuntimeTuning runtimeTuning;
    private final MeterRegistry meterRegistry;

    public EventStoreRepository(JdbcTemplate jdbcTemplate, PocRuntimeTuning runtimeTuning, MeterRegistry meterRegistry) {
        this.jdbcTemplate = jdbcTemplate;
        this.runtimeTuning = runtimeTuning;
        this.meterRegistry = meterRegistry;
    }

    public void save(Envelope envelope, String traceId, String spanId, Long sendTimestamp) {
        saveBatch(List.of(new StoreCommand(
            envelope.getId(),
            envelope.getSubject(),
            envelope.toString(),
            traceId,
            spanId,
            sendTimestamp
        )));
    }

    public void saveBatch(List<StoreCommand> commands) {
        if (commands == null || commands.isEmpty()) {
            return;
        }
        recordDbOperation("save", () -> {
            simulateDbLatency();
            String sql = """
                INSERT INTO event_store (id, route_key, envelope_json, parent_trace_id, parent_span_id, send_timestamp, status)
                VALUES (?, ?, ?, ?, ?, ?, 'PENDING')
                """;

            jdbcTemplate.batchUpdate(
                sql,
                commands,
                commands.size(),
                (ps, command) -> {
                    ps.setString(1, command.id());
                    ps.setString(2, command.routeKey());
                    ps.setString(3, command.envelopeJson());
                    ps.setString(4, command.parentTraceId());
                    ps.setString(5, command.parentSpanId());
                    if (command.sendTimestamp() == null) {
                        ps.setNull(6, java.sql.Types.BIGINT);
                    } else {
                        ps.setLong(6, command.sendTimestamp());
                    }
                }
            );
            return null;
        });
    }

    public List<EventStoreEntry> findPending(int limit) {
        return recordDbOperation("find_pending", () -> {
            simulateDbLatency();
            String sql = """
                SELECT id, route_key, envelope_json, parent_trace_id, parent_span_id,
                       send_timestamp, created_at, processed_at, status
                FROM event_store
                WHERE status = 'PENDING'
                ORDER BY created_at ASC
                LIMIT ?
                """;

            return jdbcTemplate.query(sql, new Object[]{limit}, this::mapRowToEntry);
        });
    }

    public void markProcessed(String eventId) {
        recordDbOperation("mark_processed_single", () -> {
            simulateDbLatency();
            String sql = "UPDATE event_store SET status = 'DONE', processed_at = NOW() WHERE id = ?";
            jdbcTemplate.update(sql, eventId);
            return null;
        });
    }

    public void markProcessedBatch(List<String> eventIds) {
        if (eventIds == null || eventIds.isEmpty()) {
            return;
        }
        recordDbOperation("mark_processed_batch", () -> {
            simulateDbLatency();
            String sql = "UPDATE event_store SET status = 'DONE', processed_at = NOW() WHERE id = ?";
            jdbcTemplate.batchUpdate(
                sql,
                eventIds,
                eventIds.size(),
                (ps, eventId) -> ps.setString(1, eventId)
            );
            return null;
        });
    }

    public long getPendingCount() {
        return recordDbOperation("count_pending", () -> {
            simulateDbLatency();
            String sql = "SELECT COUNT(*) FROM event_store WHERE status = 'PENDING'";
            Integer count = jdbcTemplate.queryForObject(sql, Integer.class);
            return count != null ? count : 0;
        });
    }

    public void truncate() {
        recordDbOperation("truncate", () -> {
            simulateDbLatency();
            jdbcTemplate.update("TRUNCATE event_store");
            return null;
        });
    }

    public long getProcessedCount() {
        return recordDbOperation("count_processed", () -> {
            simulateDbLatency();
            String sql = "SELECT COUNT(*) FROM event_store WHERE status = 'DONE'";
            Integer count = jdbcTemplate.queryForObject(sql, Integer.class);
            return count != null ? count : 0;
        });
    }

    private void simulateDbLatency() {
        long delayMs = runtimeTuning.getSimulatedDbLatencyMs();
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private EventStoreEntry mapRowToEntry(ResultSet rs, int rowNum) throws SQLException {
        Timestamp createdTs = rs.getTimestamp("created_at");
        Timestamp processedTs = rs.getTimestamp("processed_at");
        long sendTs = rs.getLong("send_timestamp");

        return new EventStoreEntry(
            rs.getString("id"),
            rs.getString("route_key"),
            rs.getString("envelope_json"),
            rs.getString("parent_trace_id"),
            rs.getString("parent_span_id"),
            rs.wasNull() ? null : sendTs,
            createdTs != null ? createdTs.toInstant() : Instant.now(),
            processedTs != null ? processedTs.toInstant() : null,
            rs.getString("status")
        );
    }

    private <T> T recordDbOperation(String operation, java.util.function.Supplier<T> supplier) {
        long startNanos = System.nanoTime();
        try {
            return supplier.get();
        } finally {
            long elapsedNanos = System.nanoTime() - startNanos;
            Timer.builder("poc.db.operation.duration")
                .description("Database operation latency for strategy B")
                .tag("strategy", STRATEGY_TAG)
                .tag("operation", operation)
                .register(meterRegistry)
                .record(elapsedNanos, TimeUnit.NANOSECONDS);
            Counter.builder("poc.db.operation.total")
                .description("Total database operation calls for strategy B")
                .tag("strategy", STRATEGY_TAG)
                .tag("operation", operation)
                .register(meterRegistry)
                .increment();
        }
    }

    public record StoreCommand(
        String id,
        String routeKey,
        String envelopeJson,
        String parentTraceId,
        String parentSpanId,
        Long sendTimestamp
    ) {}
}
