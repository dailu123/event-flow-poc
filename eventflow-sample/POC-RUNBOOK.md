# EventFlow POC 运维手册

> 直接 Kafka 压测 + Tempo/Grafana 可视化，对比两种 CDC 事件处理策略。
> **上次成功运行：2026-03-31**

---

## 1. POC 目标

通过 Grafana + Tempo 直观展示两种事件处理策略的 **trace 形状差异和延迟对比**：

| | Strategy A: 直接处理 | Strategy B: 落库再处理 |
|---|---|---|
| 数据流 | Kafka → Handler (5ms) → poc-direct-out | Kafka → PostgreSQL → Scheduler(500ms轮询) → poc-stored-out |
| Trace 形状 | **单段连续** span 树 (~6ms) | **两段断裂**: Phase1 存储(~2ms) + Phase2 处理(~6ms) |
| 端到端延迟 | ~6ms | ~500ms+（调度间隔 + 处理） |
| 关键 span 属性 | `poc.strategy=A` | `poc.strategy=B`, `poc.storeDelayMs`, `poc.originalTraceId` |
| 适用场景 | 低延迟实时处理 | 需要持久化/重试的场景 |

### 架构图

```
                          ┌─────────────────────────────────────────────┐
                          │         Kafka Topic: t-poc-events           │
                          └──────────────┬──────────────────────────────┘
                                         │
                                         ▼
                              KafkaInboundAdapter
                                         │
                                    Fan-out (同一 RouteKey t_poc:*)
                                    ┌────┴────┐
                                    ▼         ▼
                          PocDirectHandler  PocStoredHandler
                          (Strategy A)      (Strategy B Phase 1)
                               │                  │
                          5ms 处理            写入 PostgreSQL
                               │            (保存 traceId/spanId)
                               ▼                  │
                      poc-direct-out              ...
                                            @Scheduled 每 500ms
                                                  │
                                                  ▼
                                          PocEventProcessor
                                          (Strategy B Phase 2)
                                                  │
                                            5ms 处理
                                                  ▼
                                          poc-stored-out
```

---

## 2. 环境要求

| 组件 | 版本 | 说明 |
|------|------|------|
| Java | Zulu JDK 17 | `JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/zulu-17.jdk/Contents/Home` |
| Maven | 3.5+ | 使用项目自带 `mvn-settings.xml`（阿里云镜像）|
| PostgreSQL | 任意 | Postgres.app，数据库名 `dailu`，无密码 |
| Kafka | 3.9.0 | KRaft 模式（无 Zookeeper），路径 `/tmp/kafka_2.13-3.9.0` |
| Tempo | 2.3.1 | 路径 `/tmp/tempo`，配置 `eventflow-sample/tempo.yml` |
| Prometheus | 2.50.1 | 路径 `/tmp/prometheus-2.50.1.darwin-amd64/prometheus` |
| Grafana | 10.4.2 | 路径 `/tmp/grafana-v10.4.2/bin/grafana server` |

> **注意：不使用 Docker**，所有组件本地二进制运行（macOS 13.5 不兼容 Docker Desktop）。

---

## 3. 一键启动基础设施

按顺序执行以下命令。每条命令在**独立终端**中运行（或加 `&` 放后台）。

### 3.1 PostgreSQL

Postgres.app 默认已随系统启动，确认端口 5432 可连：

```bash
psql -h localhost -U dailu -d dailu -c "SELECT 1"
```

如未创建 event_store 表，应用首次启动会通过 `schema.sql` 自动创建（`spring.sql.init.mode=always`）。
如需手动清理旧数据：

```bash
psql -h localhost -U dailu -d dailu -c "TRUNCATE event_store"
```

### 3.2 Kafka (KRaft 模式)

```bash
# 生成集群 ID（仅首次）
cd /tmp/kafka_2.13-3.9.0
KAFKA_CLUSTER_ID="$(/tmp/kafka_2.13-3.9.0/bin/kafka-storage.sh random-uuid)"

# 格式化存储（仅首次）
/tmp/kafka_2.13-3.9.0/bin/kafka-storage.sh format -t $KAFKA_CLUSTER_ID \
  -c /tmp/kafka_2.13-3.9.0/config/kraft/server.properties

# 启动 Kafka
/tmp/kafka_2.13-3.9.0/bin/kafka-server-start.sh \
  /tmp/kafka_2.13-3.9.0/config/kraft/server.properties
```

验证：

```bash
/tmp/kafka_2.13-3.9.0/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
```

### 3.3 创建 Kafka Topics（仅首次）

```bash
KAFKA_BIN=/tmp/kafka_2.13-3.9.0/bin

$KAFKA_BIN/kafka-topics.sh --create --topic t-poc-events --partitions 4 --replication-factor 1 --bootstrap-server localhost:9092
$KAFKA_BIN/kafka-topics.sh --create --topic poc-direct-out --partitions 4 --replication-factor 1 --bootstrap-server localhost:9092
$KAFKA_BIN/kafka-topics.sh --create --topic poc-stored-out --partitions 4 --replication-factor 1 --bootstrap-server localhost:9092
```

### 3.4 Tempo

```bash
/tmp/tempo -config.file=/Users/dailu/aidemo/eventflow/eventflow-sample/tempo.yml
```

端口：
- **4317**: OTLP gRPC（应用发 trace 到这里）
- **3200**: HTTP API（Grafana 连接用）

验证：

```bash
curl -s http://localhost:3200/ready
# 输出: ready
```

### 3.5 Prometheus

```bash
/tmp/prometheus-2.50.1.darwin-amd64/prometheus \
  --config.file=/Users/dailu/aidemo/eventflow/eventflow-sample/prometheus.yml \
  --storage.tsdb.path=/tmp/prometheus-data \
  --web.listen-address=:9090
```

验证：

```bash
curl -s http://localhost:9090/-/ready
# 输出: Prometheus Server is Ready.
```

### 3.6 Grafana

```bash
cd /tmp/grafana-v10.4.2 && ./bin/grafana server
```

访问 http://localhost:3000 ，默认账号 `admin / admin`。

---

## 4. 配置 Grafana 数据源（仅首次）

```bash
# Prometheus 数据源
curl -X POST http://localhost:3000/api/datasources -u admin:admin \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Prometheus",
    "type": "prometheus",
    "url": "http://localhost:9090",
    "access": "proxy",
    "isDefault": true
  }'

# Tempo 数据源
curl -X POST http://localhost:3000/api/datasources -u admin:admin \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Tempo",
    "type": "tempo",
    "url": "http://localhost:3200",
    "access": "proxy",
    "jsonData": {
      "nodeGraph": { "enabled": true }
    }
  }'
```

验证数据源：

```bash
curl -s http://localhost:3000/api/datasources -u admin:admin | python3 -m json.tool
# 应该看到 Prometheus 和 Tempo 两个数据源
```

### 导入 Dashboard

```bash
curl -X POST http://localhost:3000/api/dashboards/db -u admin:admin \
  -H "Content-Type: application/json" \
  -d @/Users/dailu/aidemo/eventflow/eventflow-sample/grafana-poc-dashboard.json
```

Dashboard 地址：http://localhost:3000/d/eventflow-poc

---

## 5. 构建和运行应用

### 5.1 构建

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/zulu-17.jdk/Contents/Home
cd /Users/dailu/aidemo/eventflow
mvn clean install -s mvn-settings.xml -DskipTests
```

### 5.2 运行压测模式

```bash
cd /Users/dailu/aidemo/eventflow/eventflow-sample
mvn spring-boot:run -s ../mvn-settings.xml \
  -Dspring-boot.run.arguments="--demo.auto-run=false --poc.load-test=true --poc.load-test.total=500 --poc.load-test.threads=4"
```

**参数说明**：

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `demo.auto-run` | true | 设 false 禁用示例模块的自动事件 |
| `poc.load-test` | false | 设 true 启动 Kafka 压测 |
| `poc.load-test.total` | 10000 | 压测消息总数 |
| `poc.load-test.threads` | 4 | 并发发送线程数 |

### 5.3 运行普通模式（不压测）

```bash
cd /Users/dailu/aidemo/eventflow/eventflow-sample
mvn spring-boot:run -s ../mvn-settings.xml \
  -Dspring-boot.run.arguments="--demo.auto-run=false"
```

---

## 6. 查看 Trace 对比

### 6.1 通过 Grafana Explore

> **重要**：Grafana 10.4 内置 Tempo 插件**不支持在 Dashboard 面板中直接展示 TraceQL 搜索结果**，
> 需要通过 **Explore** 页面查看。Dashboard 中已预置了 Explore 直达链接。

**手动操作步骤**：

1. 打开 http://localhost:3000/explore
2. 左上角选择数据源 **Tempo**
3. 查询模式选择 **TraceQL**
4. 输入查询：

```
# Strategy A 全部 traces
{resource.service.name="eventflow-sample" && span.poc.strategy="A"}

# Strategy B Phase 2 traces（调度处理阶段）
{resource.service.name="eventflow-sample" && span.poc.strategy="B"}

# Strategy B Phase 1 traces（存储阶段）
{resource.service.name="eventflow-sample" && span.poc.phase="store"}
```

5. 点击 **Run query**
6. 点击任意 trace → 展开 span 瀑布图

### 6.2 对比要点

- **Strategy A** 的 trace：一个完整的 span 树（eventflow.pipeline.execute → handler → outbound），~6ms
- **Strategy B Phase 2** 的 trace：独立的 root span（poc.scheduler.process），~6ms，展开属性可见：
  - `poc.storeDelayMs` — 事件在 PostgreSQL 中的等待时间（正常 ~500ms，压测高峰期可能更长）
  - `poc.originalTraceId` — Phase 1 的 traceId，可复制后用 "Search by TraceID" 找到对应的存储 span

### 6.3 通过 Tempo API 直接验证

```bash
# Strategy A traces
curl -s 'http://localhost:3200/api/search?q={span.poc.strategy="A"}&limit=5'

# Strategy B traces
curl -s 'http://localhost:3200/api/search?q={span.poc.strategy="B"}&limit=5'

# 按 traceID 查看完整 trace
curl -s 'http://localhost:3200/api/traces/<traceID>'
```

---

## 7. Prometheus 指标

应用暴露的关键指标（http://localhost:8080/actuator/prometheus）：

| 指标 | 类型 | 说明 |
|------|------|------|
| `eventflow_pipeline_duration_seconds_count` | Counter | Pipeline 执行总次数 |
| `eventflow_pipeline_duration_seconds_sum` | Counter | Pipeline 执行总耗时 |
| `eventflow_pipeline_duration_seconds_max` | Gauge | Pipeline 最大单次耗时 |

标签：`routeKey="t_poc:*"`, `status="success"`

PromQL 示例：

```promql
# 吞吐量（每秒事件数）
rate(eventflow_pipeline_duration_seconds_count{routeKey="t_poc:*"}[1m])

# 平均处理延迟（ms）
rate(eventflow_pipeline_duration_seconds_sum{routeKey="t_poc:*"}[1m])
  / rate(eventflow_pipeline_duration_seconds_count{routeKey="t_poc:*"}[1m]) * 1000
```

---

## 8. 关键配置文件

### application.yml（核心配置）

```yaml
eventflow:
  enabled: true
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP:localhost:9092}
    group-id: ${spring.application.name}-eventflow
    topics: t-poc-events            # 标量格式，不要用 YAML list
  dispatch:
    ordered-worker-count: 4
    concurrent-core-size: 4
    concurrent-max-size: 8
  idempotent:
    enabled: false                  # fan-out 必须关闭，否则同一事件第二个 pipeline 会被判重
  poc:
    scheduler-delay-ms: 500         # Phase 2 调度器轮询间隔
    batch-size: 20                  # 每次从 DB 取多少条
```

### tempo.yml

```yaml
distributor:
  receivers:
    otlp:
      protocols:
        grpc:
          endpoint: 0.0.0.0:4317    # 应用发 trace 到这里
        http:
          endpoint: 0.0.0.0:4318
compactor:
  compaction:
    block_retention: 1h             # trace 保留时间
overrides:
  defaults:
    ingestion:
      max_traces_per_user: 100000   # 压测时需要足够大
```

### prometheus.yml

```yaml
scrape_configs:
  - job_name: 'eventflow-sample'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['localhost:8080']
```

---

## 9. POC 源码文件清单

| 文件 | 作用 |
|------|------|
| `poc/PocConfig.java` | 注册 fan-out pipeline + Noop IdempotentFilter + @EnableScheduling |
| `poc/PocDirectHandler.java` | Strategy A：直接处理，5ms → 发布到 poc-direct-out |
| `poc/PocStoredHandler.java` | Strategy B Phase 1：存 PostgreSQL（带 traceId/spanId） |
| `poc/PocEventProcessor.java` | Strategy B Phase 2：轮询 DB → 处理 → 发布到 poc-stored-out |
| `poc/PocLoadGenerator.java` | Java 内嵌 KafkaProducer 压测（CommandLineRunner） |
| `poc/EventStoreRepository.java` | PostgreSQL CRUD（JdbcTemplate） |
| `poc/EventStoreEntry.java` | DB 行记录（Java record） |
| `resources/schema.sql` | event_store 表 DDL |
| `resources/application.yml` | 全部配置 |
| `tempo.yml` | Tempo 配置 |
| `prometheus.yml` | Prometheus 抓取配置 |
| `grafana-poc-dashboard.json` | Grafana 仪表板定义 |

---

## 10. 常见问题排查

### 端口冲突

```bash
# 查看谁占了端口
lsof -i :8080    # 应用
lsof -i :9092    # Kafka
lsof -i :3000    # Grafana
lsof -i :3200    # Tempo
lsof -i :9090    # Prometheus

# 强制释放
lsof -ti :8080 | xargs kill -9
```

### Kafka consumer group offset 问题

如果消费到之前的旧消息，重置 offset：

```bash
# 先停掉应用，然后：
/tmp/kafka_2.13-3.9.0/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 \
  --group eventflow-sample-eventflow \
  --topic t-poc-events \
  --reset-offsets --to-latest --execute
```

### Tempo LIVE_TRACES_EXCEEDED

压测量大时 Tempo 可能报 `LIVE_TRACES_EXCEEDED`。在 `tempo.yml` 中增大：

```yaml
overrides:
  defaults:
    ingestion:
      max_traces_per_user: 100000
```

然后重启 Tempo。

### Strategy B 事件不处理（pending 数不减）

检查：
1. `@EnableScheduling` 是否在 PocConfig 上 — 没有它 `@Scheduled` 不会执行
2. `eventflow.poc.scheduler-delay-ms` 是否配置 — PocEventProcessor 的 `@ConditionalOnProperty` 依赖它
3. 查看日志是否有 `Processing X pending events from PostgreSQL`

### IdempotentFilter 导致第二个 pipeline 被跳过

症状：Strategy A 处理但 Strategy B 显示 `isDuplicate=true`。
原因：Fan-out 场景下同一事件的两份副本有相同的 dedup key。
解决：PocConfig 中已注册 Noop IdempotentFilter（永远返回 false）。确认 `idempotent.enabled: false`。

### Grafana Dashboard 面板 No Data

1. **Prometheus 面板**：确认 Prometheus 在运行 (`curl localhost:9090/-/ready`)，且应用已启动 (`curl localhost:8080/actuator/prometheus | grep eventflow`)
2. **Tempo 面板**：Grafana 10.4 内置 Tempo 插件不支持 dashboard 面板中的 TraceQL 搜索，改用 Explore 页面

---

## 11. 快速重启一切（速查命令）

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/zulu-17.jdk/Contents/Home

# 1. Kafka（新终端）
/tmp/kafka_2.13-3.9.0/bin/kafka-server-start.sh /tmp/kafka_2.13-3.9.0/config/kraft/server.properties

# 2. Tempo（新终端）
/tmp/tempo -config.file=/Users/dailu/aidemo/eventflow/eventflow-sample/tempo.yml

# 3. Prometheus（新终端）
/tmp/prometheus-2.50.1.darwin-amd64/prometheus \
  --config.file=/Users/dailu/aidemo/eventflow/eventflow-sample/prometheus.yml \
  --storage.tsdb.path=/tmp/prometheus-data --web.listen-address=:9090

# 4. Grafana（新终端）
cd /tmp/grafana-v10.4.2 && ./bin/grafana server

# 5. 清理旧数据 + 运行压测
psql -h localhost -U dailu -d dailu -c "TRUNCATE event_store"
cd /Users/dailu/aidemo/eventflow/eventflow-sample
mvn spring-boot:run -s ../mvn-settings.xml \
  -Dspring-boot.run.arguments="--demo.auto-run=false --poc.load-test=true --poc.load-test.total=500"

# 6. 验证
curl -s http://localhost:3200/api/search?q='{span.poc.strategy="A"}'&limit=3
curl -s http://localhost:3200/api/search?q='{span.poc.strategy="B"}'&limit=3
open http://localhost:3000/explore
```
