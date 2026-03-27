package com.hsbc.pluse.port;

import com.hsbc.pluse.model.Destination;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class CompositeOutboundPublisher {

    private static final Logger log = LoggerFactory.getLogger(CompositeOutboundPublisher.class);
    private final Map<String, OutboundPort> ports = new ConcurrentHashMap<>();

    public CompositeOutboundPublisher(List<OutboundPort> outboundPorts) {
        for (OutboundPort port : outboundPorts) {
            ports.put(port.channel(), port);
        }
    }

    public void publish(List<Destination> destinations) {
        for (Destination dest : destinations) {
            OutboundPort port = ports.get(dest.channel());
            if (port == null) {
                log.error("No outbound port for channel '{}', destination: {}", dest.channel(), dest);
                throw new IllegalStateException("No outbound port for channel: " + dest.channel());
            }
            port.publish(dest);
        }
    }
}
