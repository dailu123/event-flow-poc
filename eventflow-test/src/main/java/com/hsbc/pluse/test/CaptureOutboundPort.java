package com.hsbc.pluse.test;

import com.hsbc.pluse.model.Destination;
import com.hsbc.pluse.port.OutboundPort;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Captures all outbound messages for test assertions.
 */
public class CaptureOutboundPort implements OutboundPort {

    private final List<Destination> captured = new CopyOnWriteArrayList<>();

    @Override
    public String channel() {
        return "kafka"; // captures kafka channel by default
    }

    @Override
    public void publish(Destination destination) {
        captured.add(destination);
    }

    public List<Destination> getCaptured() {
        return List.copyOf(captured);
    }

    public void reset() {
        captured.clear();
    }
}
