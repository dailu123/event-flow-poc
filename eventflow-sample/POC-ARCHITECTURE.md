# EventFlow POC 测试方案架构图（A vs B）

## 1) 总体架构

```mermaid
flowchart LR
    LT[Load Test / 业务请求\n/api/poc/load-test] --> P[Producer\n(eventflow-sample)]
    P --> K[(Kafka Topic\nt-poc-events)]

    K --> C_A[Consumer 实例 A\nport=8080\nstrategy-a=true\nstrategy-b=false\ngroup-id=A]
    K --> C_B[Consumer 实例 B\nport=8081\nstrategy-a=false\nstrategy-b=true\ngroup-id=B]

    subgraph A_Path[Strategy A: Direct]
      C_A --> A1[PocDirectHandler]
      A1 --> A2[业务处理]
      A2 --> A3[完成]
    end

    subgraph B_Path[Strategy B: Store + Process]
      C_B --> B1[PocStoredHandler\n按 Kafka poll 批次同步落库]
      B1 --> DB[(PostgreSQL\nevent_store)]
      DB --> B2[PocEventProcessor Scheduler\n批量拉取 PENDING]
      B2 --> B3[业务处理]
      B3 --> B4[批量更新 DONE]
      B4 --> DB
    end

    C_A --> M1[/Actuator Prometheus\n:8080/]
    C_B --> M2[/Actuator Prometheus\n:8081/]

    M1 --> PR[(Prometheus)]
    M2 --> PR
    PR --> GF[(Grafana Dashboard)]

    C_A -. traces .-> TP[(Tempo)]
    C_B -. traces .-> TP
    GF -. trace explore links .-> TP
```

## 2) 对比口径（本次测试）

- 同一 Kafka Topic，不同 `group-id`，A/B 独立消费互不抢分区。
- A 策略：消息到达后直接执行业务处理。
- B 策略：先落库，再由调度器异步处理并回写状态。
- B 落库策略：以 Kafka 单次 `poll` 实际拉取条数为批次边界，批量同步写库。
- 观测面：
  - Metrics：Prometheus + Grafana（E2E Avg/P50/P95、吞吐、B DB操作统计）
  - Trace：Tempo（可区分 send/process/store/scheduler/DB 阶段）

## 3) 关键指标映射

- A/B E2E 延迟：`poc_e2e_duration_seconds_*`
- A/B 吞吐：`poc_events_processed_total`（配合 `increase/rate`）
- B DB 操作次数：`poc_db_operation_total{strategy="B"}`
- B DB 操作耗时：`poc_db_operation_duration_seconds_{sum,count,bucket}{strategy="B"}`

