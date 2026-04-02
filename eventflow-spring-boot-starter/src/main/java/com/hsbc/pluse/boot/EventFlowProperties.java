package com.hsbc.pluse.boot;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

@ConfigurationProperties(prefix = "eventflow")
public class EventFlowProperties {

    private boolean enabled = true;
    private KafkaProps kafka = new KafkaProps();
    private DispatchProps dispatch = new DispatchProps();
    private BackpressureProps backpressure = new BackpressureProps();
    private IdempotentProps idempotent = new IdempotentProps();
    private ShutdownProps shutdown = new ShutdownProps();
    private ObservabilityProps observability = new ObservabilityProps();

    // --- getters & setters ---
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public KafkaProps getKafka() { return kafka; }
    public void setKafka(KafkaProps kafka) { this.kafka = kafka; }
    public DispatchProps getDispatch() { return dispatch; }
    public void setDispatch(DispatchProps dispatch) { this.dispatch = dispatch; }
    public BackpressureProps getBackpressure() { return backpressure; }
    public void setBackpressure(BackpressureProps backpressure) { this.backpressure = backpressure; }
    public IdempotentProps getIdempotent() { return idempotent; }
    public void setIdempotent(IdempotentProps idempotent) { this.idempotent = idempotent; }
    public ShutdownProps getShutdown() { return shutdown; }
    public void setShutdown(ShutdownProps shutdown) { this.shutdown = shutdown; }
    public ObservabilityProps getObservability() { return observability; }
    public void setObservability(ObservabilityProps observability) { this.observability = observability; }

    public static class KafkaProps {
        private String bootstrapServers = "localhost:9092";
        private String groupId = "eventflow-default";
        private String autoOffsetReset = "earliest";
        private int maxPollRecords = 500;
        private long pollTimeoutMs = 1000;
        private List<String> topics = List.of();
        private DltProps dlt = new DltProps();

        public String getBootstrapServers() { return bootstrapServers; }
        public void setBootstrapServers(String v) { this.bootstrapServers = v; }
        public String getGroupId() { return groupId; }
        public void setGroupId(String v) { this.groupId = v; }
        public String getAutoOffsetReset() { return autoOffsetReset; }
        public void setAutoOffsetReset(String v) { this.autoOffsetReset = v; }
        public int getMaxPollRecords() { return maxPollRecords; }
        public void setMaxPollRecords(int v) { this.maxPollRecords = v; }
        public long getPollTimeoutMs() { return pollTimeoutMs; }
        public void setPollTimeoutMs(long v) { this.pollTimeoutMs = v; }
        public List<String> getTopics() { return topics; }
        public void setTopics(List<String> v) { this.topics = v; }
        public DltProps getDlt() { return dlt; }
        public void setDlt(DltProps dlt) { this.dlt = dlt; }
    }

    public static class DltProps {
        private boolean enabled = true;
        private String topicSuffix = ".DLT";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public String getTopicSuffix() { return topicSuffix; }
        public void setTopicSuffix(String v) { this.topicSuffix = v; }
    }

    public static class DispatchProps {
        private int orderedWorkerCount = 16;
        private int concurrentCoreSize = 8;
        private int concurrentMaxSize = 32;

        public int getOrderedWorkerCount() { return orderedWorkerCount; }
        public void setOrderedWorkerCount(int v) { this.orderedWorkerCount = v; }
        public int getConcurrentCoreSize() { return concurrentCoreSize; }
        public void setConcurrentCoreSize(int v) { this.concurrentCoreSize = v; }
        public int getConcurrentMaxSize() { return concurrentMaxSize; }
        public void setConcurrentMaxSize(int v) { this.concurrentMaxSize = v; }
    }

    public static class BackpressureProps {
        private int highWaterMark = 1000;
        private int lowWaterMark = 500;

        public int getHighWaterMark() { return highWaterMark; }
        public void setHighWaterMark(int v) { this.highWaterMark = v; }
        public int getLowWaterMark() { return lowWaterMark; }
        public void setLowWaterMark(int v) { this.lowWaterMark = v; }
    }

    public static class IdempotentProps {
        private boolean enabled = true;
        private long cacheSize = 100000;
        private Duration ttl = Duration.ofMinutes(10);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }
        public long getCacheSize() { return cacheSize; }
        public void setCacheSize(long v) { this.cacheSize = v; }
        public Duration getTtl() { return ttl; }
        public void setTtl(Duration v) { this.ttl = v; }
    }

    public static class ShutdownProps {
        private int timeoutSeconds = 30;

        public int getTimeoutSeconds() { return timeoutSeconds; }
        public void setTimeoutSeconds(int v) { this.timeoutSeconds = v; }
    }

    public static class ObservabilityProps {
        private boolean tracingEnabled = true;
        private boolean metricsEnabled = true;

        public boolean isTracingEnabled() { return tracingEnabled; }
        public void setTracingEnabled(boolean v) { this.tracingEnabled = v; }
        public boolean isMetricsEnabled() { return metricsEnabled; }
        public void setMetricsEnabled(boolean v) { this.metricsEnabled = v; }
    }
}
