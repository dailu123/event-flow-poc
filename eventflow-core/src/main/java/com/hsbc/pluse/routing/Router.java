package com.hsbc.pluse.routing;

import com.hsbc.pluse.model.Envelope;
import com.hsbc.pluse.model.RouteKey;

@FunctionalInterface
public interface Router {
    RouteKey route(Envelope envelope);
}
