package com.hsbc.pluse.model;

import java.util.List;

public sealed interface ProcessingResult {

    record Success(List<Destination> outputs) implements ProcessingResult {
        public Success {
            outputs = outputs != null ? List.copyOf(outputs) : List.of();
        }
    }

    record Filtered(String reason) implements ProcessingResult {
    }

    record Failed(Throwable cause, boolean retryable) implements ProcessingResult {
    }
}
