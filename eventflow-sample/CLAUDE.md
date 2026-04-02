# CLAUDE.md — EventFlow Sample (POC) Context

## POC Goal

Compare two CDC event processing strategies via Kafka load test + Tempo/Grafana visualization:

| | Strategy A: Direct | Strategy B: Store-then-Process |
|---|---|---|
| Flow | Kafka -> Handler (5ms) -> poc-direct-out | Kafka -> PostgreSQL -> Scheduler(500ms poll) -> poc-stored-out |
| Trace shape | Single continuous span tree (~6ms) | Phase 2 root span + **Span Link** back to Phase 1 |
| E2E latency | ~6ms | ~500ms+ (scheduling interval + processing) |
| E2E metric | `poc.e2e.duration{strategy=A}` | `poc.e2e.duration{strategy=B}` |
| Key span attrs | `poc.strategy=A` | `poc.strategy=B`, `poc.e2eLatencyMs`, `poc.storeDelayMs`, `poc.originalTraceId` |

### E2E Latency Calculation

- **Start**: `sendTimestamp` injected by PocLoadGenerator at Kafka send time (stored in message data)
- **End**: `System.currentTimeMillis()` at processing completion
- Strategy A: measured in PocDirectHandler
- Strategy B: measured in PocEventProcessor (includes Phase 1 store + DB wait + Phase 2 process)
- Both use identical start point for fair comparison

### Load Test Levels

Run different levels by changing `--poc.load-test.total`:
```bash
# Recommended test levels:
--poc.load-test.total=1        # Smoke test
--poc.load-test.total=100      # Small batch
--poc.load-test.total=10000    # Medium load
--poc.load-test.total=100000   # High load
--poc.load-test.total=1000000  # Stress test
```

## Architecture

```
Kafka Topic: t-poc-events
         |
    KafkaInboundAdapter
         |
    Fan-out (RouteKey t_poc:*)
    +--------+--------+
    v                 v
PocDirectHandler   PocStoredHandler
(Strategy A)       (Strategy B Phase 1)
    |                 |
  5ms process    Write PostgreSQL (save traceId/spanId)
    |                 |
poc-direct-out     @Scheduled every 500ms
                      |
                PocEventProcessor (Strategy B Phase 2)
                      |
                   5ms process
                      |
                poc-stored-out
```

## Environment (all local binaries, NO Docker)

| Component | Path / Port |
|-----------|-------------|
| Java | Zulu JDK 17: `JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/zulu-17.jdk/Contents/Home` |
| PostgreSQL | Postgres.app, db=`dailu`, user=`dailu`, no password, port 5432 |
| Kafka | KRaft mode, `/tmp/kafka_2.13-3.9.0`, port 9092 |
| Tempo | `/tmp/tempo`, config=`eventflow-sample/tempo.yml`, ports 4317(OTLP gRPC) / 3200(HTTP) |
| Prometheus | `/tmp/prometheus-2.50.1.darwin-amd64/prometheus`, config=`eventflow-sample/prometheus.yml`, port 9090 |
| Grafana | `/tmp/grafana-v10.4.2/bin/grafana server`, port 3000 (admin/admin) |

## Infrastructure Start Commands

```bash
# PostgreSQL: Postgres.app auto-starts. Verify:
psql -h localhost -U dailu -d dailu -c "SELECT 1"

# Kafka (KRaft):
/tmp/kafka_2.13-3.9.0/bin/kafka-server-start.sh /tmp/kafka_2.13-3.9.0/config/kraft/server.properties

# Create Kafka topics (first time only):
KAFKA_BIN=/tmp/kafka_2.13-3.9.0/bin
$KAFKA_BIN/kafka-topics.sh --create --topic t-poc-events --partitions 4 --replication-factor 1 --bootstrap-server localhost:9092
$KAFKA_BIN/kafka-topics.sh --create --topic poc-direct-out --partitions 4 --replication-factor 1 --bootstrap-server localhost:9092
$KAFKA_BIN/kafka-topics.sh --create --topic poc-stored-out --partitions 4 --replication-factor 1 --bootstrap-server localhost:9092

# Tempo:
/tmp/tempo -config.file=/Users/dailu/aidemo/eventflow/eventflow-sample/tempo.yml

# Prometheus:
/tmp/prometheus-2.50.1.darwin-amd64/prometheus \
  --config.file=/Users/dailu/aidemo/eventflow/eventflow-sample/prometheus.yml \
  --storage.tsdb.path=/tmp/prometheus-data --web.listen-address=:9090

# Grafana:
cd /tmp/grafana-v10.4.2 && ./bin/grafana server
```

## Build & Run

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/zulu-17.jdk/Contents/Home

# Build from project root:
cd /Users/dailu/aidemo/eventflow
mvn clean install -s mvn-settings.xml -DskipTests

# Run normal mode (recommended — use REST API to trigger load tests):
cd /Users/dailu/aidemo/eventflow/eventflow-sample
mvn spring-boot:run -s ../mvn-settings.xml \
  -Dspring-boot.run.arguments="--demo.auto-run=false"

# Run with CLI load test (legacy, requires restart each time):
mvn spring-boot:run -s ../mvn-settings.xml \
  -Dspring-boot.run.arguments="--demo.auto-run=false --poc.load-test=true --poc.load-test.total=500 --poc.load-test.threads=4"
```

### POC Load Test REST API (推荐)

无需重启应用即可反复触发压测：

```bash
# 压测 10000 条（默认），清空旧数据
curl -X POST http://localhost:8080/api/poc/load-test \
  -H "Content-Type: application/json" \
  -d '{"total": 10000, "threads": 4, "clean": true}'

# 快速烟雾测试 1 条
curl -X POST http://localhost:8080/api/poc/load-test \
  -H "Content-Type: application/json" -d '{"total": 1}'

# 压测 100 万
curl -X POST http://localhost:8080/api/poc/load-test \
  -H "Content-Type: application/json" -d '{"total": 1000000, "threads": 8}'

# 查看当前压测状态
curl http://localhost:8080/api/poc/load-test/status
```

| Parameter | Default | Description |
|-----------|---------|-------------|
| `total` | 10000 | Total messages to send |
| `threads` | 4 | Concurrent sender threads |
| `clean` | true | Truncate event_store before test |

API 返回 202 后异步执行，日志和 Grafana Dashboard 实时监控进度。同一时间只能跑一个压测。

### CLI Run Parameters (legacy)

| Parameter | Default | Description |
|-----------|---------|-------------|
| `demo.auto-run` | true | Set false to disable demo module auto events |
| `poc.load-test` | false | Set true to enable Kafka load test |
| `poc.load-test.total` | 10000 | Total load test messages |
| `poc.load-test.threads` | 4 | Concurrent sender threads |

## Key Configuration (application.yml)

```yaml
eventflow:
  kafka:
    bootstrap-servers: localhost:9092
    group-id: eventflow-sample-eventflow
    topics: t-poc-events          # scalar format, not YAML list
  dispatch:
    ordered-worker-count: 4
    concurrent-core-size: 4
    concurrent-max-size: 8
  idempotent:
    enabled: false                # MUST be false for fan-out, otherwise second pipeline gets deduped
  poc:
    scheduler-delay-ms: 10        # Phase 2 scheduler poll interval (min delay between batches)
    batch-size: 500               # DB fetch batch size (drains continuously if full)
```

## Kafka Topics

- `t-poc-events` — inbound POC events
- `poc-direct-out` — Strategy A output
- `poc-stored-out` — Strategy B output

## Grafana Data Sources

```bash
# Prometheus datasource:
curl -X POST http://localhost:3000/api/datasources -u admin:admin \
  -H "Content-Type: application/json" \
  -d '{"name":"Prometheus","type":"prometheus","url":"http://localhost:9090","access":"proxy","isDefault":true}'

# Tempo datasource:
curl -X POST http://localhost:3000/api/datasources -u admin:admin \
  -H "Content-Type: application/json" \
  -d '{"name":"Tempo","type":"tempo","url":"http://localhost:3200","access":"proxy","jsonData":{"nodeGraph":{"enabled":true}}}'

# Import dashboard:
curl -X POST http://localhost:3000/api/dashboards/db -u admin:admin \
  -H "Content-Type: application/json" \
  -d @/Users/dailu/aidemo/eventflow/eventflow-sample/grafana-poc-dashboard.json
```

Dashboard URL: http://localhost:3000/d/eventflow-poc

### Dashboard Panels

| Panel | Type | Description |
|-------|------|-------------|
| Trace Comparison | Text | Entry point with Explore links and comparison table |
| Pipeline Throughput | Time Series | `rate(eventflow_pipeline_duration_seconds_count)` |
| Pipeline Avg Duration | Time Series | Pipeline avg execution time (ms) |
| Pipeline Total / Max | Stat | Cumulative count and max duration |
| **E2E Avg Latency: A vs B** | Time Series | `poc_e2e_duration_seconds` avg by strategy |
| **E2E P95 Latency: A vs B** | Time Series | 95th percentile E2E comparison |
| **E2E P99 Latency: A vs B** | Time Series | 99th percentile E2E comparison |
| **Throughput: A vs B** | Time Series | `rate(poc_events_processed_total)` by strategy |
| **Total Processed: A vs B** | Stat | Cumulative processed count by strategy |
| **E2E P50 Latency: A vs B** | Stat | Median E2E comparison |

## Trace Queries (TraceQL via Grafana Explore)

```
# Strategy A traces:
{resource.service.name="eventflow-sample" && span.poc.strategy="A"}

# Strategy B Phase 2 traces:
{resource.service.name="eventflow-sample" && span.poc.strategy="B"}

# Strategy B Phase 1 (store phase):
{resource.service.name="eventflow-sample" && span.poc.phase="store"}
```

Tempo API:
```bash
curl -s 'http://localhost:3200/api/search?q={span.poc.strategy="A"}&limit=5'
curl -s 'http://localhost:3200/api/search?q={span.poc.strategy="B"}&limit=5'
```

## Prometheus Metrics

Endpoint: http://localhost:8080/actuator/prometheus

### Pipeline Metrics (framework-level)

| Metric | Type |
|--------|------|
| `eventflow_pipeline_duration_seconds_count` | Counter - total executions |
| `eventflow_pipeline_duration_seconds_sum` | Counter - total duration |
| `eventflow_pipeline_duration_seconds_max` | Gauge - max single duration |

Labels: `routeKey="t_poc:*"`, `status="success"`

### POC E2E Metrics (A vs B comparison)

| Metric | Type | Labels |
|--------|------|--------|
| `poc_e2e_duration_seconds` | Timer (histogram) | `strategy=A\|B` |
| `poc_e2e_duration_seconds_bucket` | Histogram buckets | `strategy`, `le` |
| `poc_events_processed_total` | Counter | `strategy=A\|B` |

Percentiles available: P50, P95, P99 (via `publishPercentiles`)

## POC Source Files

| File | Purpose |
|------|---------|
| `poc/PocConfig.java` | Registers fan-out pipeline + Noop IdempotentFilter + @EnableScheduling |
| `poc/PocDirectHandler.java` | Strategy A: direct process 5ms -> poc-direct-out, records E2E metric |
| `poc/PocStoredHandler.java` | Strategy B Phase 1: store to PostgreSQL (with traceId/spanId/sendTimestamp) |
| `poc/PocEventProcessor.java` | Strategy B Phase 2: poll DB at max speed (batch=500, continuous drain), Span Link to Phase 1, records E2E metric |
| `poc/PocMetrics.java` | E2E Timer (`poc.e2e.duration`) + Counter (`poc.events.processed`) with P50/P95/P99 |
| `poc/PocLoadTestController.java` | REST API (`POST /api/poc/load-test`, `GET /api/poc/load-test/status`) — trigger load test without restart |
| `poc/PocLoadGenerator.java` | CLI KafkaProducer load test (legacy, requires restart), injects `sendTimestamp` in message data |
| `poc/EventStoreRepository.java` | PostgreSQL CRUD (JdbcTemplate), includes `send_timestamp` column |
| `poc/EventStoreEntry.java` | DB row record (Java record) with `sendTimestamp` field |
| `resources/schema.sql` | event_store table DDL (auto-created on first run) |

## Troubleshooting

- **Port conflicts**: `lsof -i :<port>` to check, `lsof -ti :<port> | xargs kill -9` to free
- **Kafka offset issues**: Reset with `kafka-consumer-groups.sh --group eventflow-sample-eventflow --topic t-poc-events --reset-offsets --to-latest --execute`
- **Tempo LIVE_TRACES_EXCEEDED**: Increase `max_traces_per_user` in tempo.yml and restart
- **Strategy B events stuck (pending not decreasing)**: Check `@EnableScheduling` on PocConfig, check `eventflow.poc.scheduler-delay-ms` is set
- **IdempotentFilter blocking second pipeline**: Ensure `idempotent.enabled: false` in config
- **Grafana Dashboard No Data**: Tempo TraceQL not supported in dashboard panels in Grafana 10.4, use Explore page instead
- **Clean old data**: `psql -h localhost -U dailu -d dailu -c "TRUNCATE event_store"`
- **Schema change (send_timestamp)**: If upgrading from old schema, run: `ALTER TABLE event_store ADD COLUMN IF NOT EXISTS send_timestamp BIGINT`
- **E2E metrics show 0**: Ensure PocLoadGenerator is used (it injects `sendTimestamp`); REST API events won't have this field
