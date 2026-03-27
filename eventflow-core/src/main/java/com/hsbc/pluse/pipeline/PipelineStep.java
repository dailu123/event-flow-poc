package com.hsbc.pluse.pipeline;

import com.hsbc.pluse.model.Envelope;

@FunctionalInterface
public interface PipelineStep {
    Envelope process(Envelope envelope);
}
