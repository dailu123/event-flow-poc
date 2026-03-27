package com.hsbc.pluse.pipeline;

import com.hsbc.pluse.model.Envelope;
import com.hsbc.pluse.model.ProcessingResult;

@FunctionalInterface
public interface EventHandler {
    ProcessingResult handle(Envelope envelope);
}
