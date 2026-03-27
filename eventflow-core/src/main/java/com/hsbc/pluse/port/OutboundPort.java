package com.hsbc.pluse.port;

import com.hsbc.pluse.model.Destination;

public interface OutboundPort {
    String channel();
    void publish(Destination destination);
}
