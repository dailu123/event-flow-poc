package com.hsbc.pluse.boot;

import com.hsbc.pluse.dispatch.BackpressureController;
import com.hsbc.pluse.dispatch.Dispatcher;
import com.hsbc.pluse.dispatch.DispatcherConfig;
import com.hsbc.pluse.dispatch.DispatcherStats;
import com.hsbc.pluse.idempotent.CaffeineIdempotentFilter;
import com.hsbc.pluse.idempotent.IdempotentFilter;
import com.hsbc.pluse.kafka.KafkaDltPublisher;
import com.hsbc.pluse.kafka.KafkaEnvelopeConverter;
import com.hsbc.pluse.kafka.KafkaInboundAdapter;
import com.hsbc.pluse.kafka.KafkaOutboundAdapter;
import com.hsbc.pluse.observe.EventFlowTracer;
import com.hsbc.pluse.port.CompositeOutboundPublisher;
import com.hsbc.pluse.port.OutboundPort;
import com.hsbc.pluse.routing.HeaderBasedRouter;
import com.hsbc.pluse.routing.PipelineRegistry;
import com.hsbc.pluse.routing.Router;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;

import java.util.List;
import java.util.Properties;

@AutoConfiguration
@EnableConfigurationProperties(EventFlowProperties.class)
@ConditionalOnProperty(prefix = "eventflow", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EventFlowAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Router router() {
        return new HeaderBasedRouter();
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotentFilter idempotentFilter(EventFlowProperties props) {
        return new CaffeineIdempotentFilter(
                props.getIdempotent().getCacheSize(),
                props.getIdempotent().getTtl());
    }

    @Bean
    public PipelineRegistry pipelineRegistry() {
        return new PipelineRegistry();
    }

    @Bean
    public EventFlowRegistrar eventFlowRegistrar(ApplicationContext ctx, PipelineRegistry registry) {
        return new EventFlowRegistrar(ctx, registry);
    }

    @Bean
    @ConditionalOnMissingBean
    public MeterRegistry eventFlowMeterRegistry() {
        return new SimpleMeterRegistry();
    }

    @Bean
    public CompositeOutboundPublisher outboundPublisher(List<OutboundPort> ports) {
        return new CompositeOutboundPublisher(ports);
    }

    @Bean
    public DispatcherStats dispatcherStats() {
        return new DispatcherStats();
    }

    @Bean
    public Dispatcher dispatcher(EventFlowProperties props,
                                 CompositeOutboundPublisher outboundPublisher,
                                 MeterRegistry meterRegistry,
                                 DispatcherStats dispatcherStats,
                                 @org.springframework.beans.factory.annotation.Autowired(required = false) EventFlowTracer tracer,
                                 @org.springframework.beans.factory.annotation.Autowired(required = false) IdempotentFilter idempotentFilter) {
        DispatcherConfig config = new DispatcherConfig();
        config.setOrderedWorkerCount(props.getDispatch().getOrderedWorkerCount());
        config.setConcurrentCoreSize(props.getDispatch().getConcurrentCoreSize());
        config.setConcurrentMaxSize(props.getDispatch().getConcurrentMaxSize());
        return new Dispatcher(config, outboundPublisher, meterRegistry, tracer, idempotentFilter, dispatcherStats);
    }

    @Bean
    public BackpressureController backpressureController(EventFlowProperties props) {
        return new BackpressureController(
                props.getBackpressure().getHighWaterMark(),
                props.getBackpressure().getLowWaterMark());
    }

    @Bean
    @ConditionalOnMissingBean
    public EventFlowTracer eventFlowTracer(io.opentelemetry.api.OpenTelemetry openTelemetry) {
        return new EventFlowTracer(openTelemetry);
    }

    // ---- Kafka Beans ----

    @Bean
    @ConditionalOnClass(name = "org.apache.kafka.clients.consumer.KafkaConsumer")
    @ConditionalOnProperty(prefix = "eventflow.kafka", name = "topics")
    public KafkaConsumer<String, byte[]> eventFlowKafkaConsumer(EventFlowProperties props) {
        Properties p = new Properties();
        p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, props.getKafka().getBootstrapServers());
        p.put(ConsumerConfig.GROUP_ID_CONFIG, props.getKafka().getGroupId());
        p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, props.getKafka().getAutoOffsetReset());
        p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        p.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, String.valueOf(props.getKafka().getMaxPollRecords()));
        p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        return new KafkaConsumer<>(p);
    }

    @Bean
    @ConditionalOnClass(name = "org.apache.kafka.clients.producer.KafkaProducer")
    @ConditionalOnProperty(prefix = "eventflow.kafka", name = "topics")
    public KafkaProducer<String, byte[]> eventFlowKafkaProducer(EventFlowProperties props) {
        Properties p = new Properties();
        p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, props.getKafka().getBootstrapServers());
        p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        p.put(ProducerConfig.ACKS_CONFIG, "all");
        return new KafkaProducer<>(p);
    }

    @Bean
    @ConditionalOnProperty(prefix = "eventflow.kafka", name = "topics")
    public KafkaOutboundAdapter kafkaOutboundAdapter(KafkaProducer<String, byte[]> producer) {
        return new KafkaOutboundAdapter(producer);
    }

    @Bean
    @ConditionalOnProperty(prefix = "eventflow.kafka", name = "topics")
    public KafkaDltPublisher kafkaDltPublisher(KafkaProducer<String, byte[]> producer,
                                               EventFlowProperties props) {
        return new KafkaDltPublisher(producer, props.getKafka().getDlt().getTopicSuffix());
    }

    @Bean
    @ConditionalOnProperty(prefix = "eventflow.kafka", name = "topics")
    public KafkaInboundAdapter kafkaInboundAdapter(
            KafkaConsumer<String, byte[]> consumer,
            IdempotentFilter idempotentFilter,
            Router router,
            PipelineRegistry registry,
            Dispatcher dispatcher,
            KafkaDltPublisher dltPublisher,
            BackpressureController backpressure,
            EventFlowProperties props) {
        return new KafkaInboundAdapter(
                consumer,
                new KafkaEnvelopeConverter(),
                idempotentFilter,
                router,
                registry,
                dispatcher,
                dltPublisher,
                backpressure,
                props.getKafka().getTopics(),
                props.getShutdown().getTimeoutSeconds());
    }

    /**
     * SmartLifecycle to auto-start/stop the KafkaInboundAdapter with Spring context.
     */
    @Bean
    @ConditionalOnProperty(prefix = "eventflow.kafka", name = "topics")
    public SmartLifecycle kafkaInboundLifecycle(KafkaInboundAdapter adapter) {
        return new SmartLifecycle() {
            private volatile boolean running = false;

            @Override public void start() { adapter.start(); running = true; }
            @Override public void stop() { adapter.stop(); running = false; }
            @Override public boolean isRunning() { return running; }
            @Override public int getPhase() { return Integer.MAX_VALUE - 100; }
        };
    }
}
