package com.hsbc.pluse.sample;

import com.hsbc.pluse.model.Destination;
import com.hsbc.pluse.port.OutboundPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Demo outbound port: logs output instead of sending to Kafka.
 */
@Component
public class LoggingOutboundPort implements OutboundPort {

    private static final Logger log = LoggerFactory.getLogger(LoggingOutboundPort.class);

    @Override
    public String channel() {
        return "kafka";
    }

    @Override
    public void publish(Destination destination) {
        log.info("▶ [OUTPUT] channel={}, target={}, payload={}", destination.channel(), destination.target(), destination.payload());
    }
}
