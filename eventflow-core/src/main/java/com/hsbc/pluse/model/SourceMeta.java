package com.hsbc.pluse.model;

public record SourceMeta(String topic, int partition, long offset) {
}
