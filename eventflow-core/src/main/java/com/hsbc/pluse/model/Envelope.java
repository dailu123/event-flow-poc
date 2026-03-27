package com.hsbc.pluse.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable canonical event model (inspired by CloudEvents).
 * All inbound data is converted to this model before entering the core engine.
 */
public final class Envelope {

    private final String id;
    private final String source;
    private final String type;
    private final Instant time;
    private final String subject;
    private final JsonNode data;
    private final Map<String, String> headers;
    private final SourceMeta sourceMeta;

    private Envelope(String id, String source, String type, Instant time,
                     String subject, JsonNode data, Map<String, String> headers,
                     SourceMeta sourceMeta) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.source = Objects.requireNonNull(source, "source must not be null");
        this.type = Objects.requireNonNull(type, "type must not be null");
        this.time = Objects.requireNonNull(time, "time must not be null");
        this.subject = subject;
        this.data = data != null ? data : NullNode.getInstance();
        this.headers = Collections.unmodifiableMap(new HashMap<>(headers != null ? headers : Map.of()));
        this.sourceMeta = sourceMeta;
    }

    public String getId() { return id; }
    public String getSource() { return source; }
    public String getType() { return type; }
    public Instant getTime() { return time; }
    public String getSubject() { return subject; }
    public JsonNode getData() { return data; }
    public Map<String, String> getHeaders() { return headers; }
    public SourceMeta getSourceMeta() { return sourceMeta; }

    public Envelope withData(JsonNode newData) {
        return new Envelope(id, source, type, time, subject, newData, headers, sourceMeta);
    }

    public Envelope withHeader(String key, String value) {
        var newHeaders = new HashMap<>(this.headers);
        newHeaders.put(key, value);
        return new Envelope(id, source, type, time, subject, data, newHeaders, sourceMeta);
    }

    public Envelope withSourceMeta(SourceMeta meta) {
        return new Envelope(id, source, type, time, subject, data, headers, meta);
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public String toString() {
        return "Envelope{id='" + id + "', source='" + source + "', type='" + type
                + "', subject='" + subject + "', table=" + headers.get("table")
                + ", op=" + headers.get("op") + '}';
    }

    public static final class Builder {
        private String id;
        private String source;
        private String type;
        private Instant time;
        private String subject;
        private JsonNode data;
        private final Map<String, String> headers = new HashMap<>();
        private SourceMeta sourceMeta;

        private Builder() {}

        public Builder id(String id) { this.id = id; return this; }
        public Builder source(String source) { this.source = source; return this; }
        public Builder type(String type) { this.type = type; return this; }
        public Builder time(Instant time) { this.time = time; return this; }
        public Builder subject(String subject) { this.subject = subject; return this; }
        public Builder data(JsonNode data) { this.data = data; return this; }
        public Builder headers(Map<String, String> headers) { this.headers.putAll(headers); return this; }
        public Builder header(String key, String value) { this.headers.put(key, value); return this; }
        public Builder sourceMeta(SourceMeta sourceMeta) { this.sourceMeta = sourceMeta; return this; }

        public Envelope build() {
            if (id == null) id = UUID.randomUUID().toString();
            if (time == null) time = Instant.now();
            return new Envelope(id, source, type, time, subject, data, headers, sourceMeta);
        }
    }
}
