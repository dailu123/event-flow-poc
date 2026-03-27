package com.hsbc.pluse.routing;

import com.hsbc.pluse.model.Envelope;
import com.hsbc.pluse.model.RouteKey;

public class HeaderBasedRouter implements Router {
    @Override
    public RouteKey route(Envelope envelope) {
        String table = envelope.getHeaders().getOrDefault("table", "__unknown__");
        String op = envelope.getHeaders().getOrDefault("op", "*");
        return new RouteKey(table, op);
    }
}
