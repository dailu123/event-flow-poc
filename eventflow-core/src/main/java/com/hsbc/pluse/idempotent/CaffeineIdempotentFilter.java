package com.hsbc.pluse.idempotent;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hsbc.pluse.model.Envelope;

import java.time.Duration;

public class CaffeineIdempotentFilter implements IdempotentFilter {

    private final Cache<String, Boolean> cache;

    public CaffeineIdempotentFilter(long maxSize, Duration ttl) {
        this.cache = Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(ttl)
                .build();
    }

    @Override
    public boolean isDuplicate(Envelope envelope) {
        String key = deduplicationKey(envelope);
        return cache.asMap().putIfAbsent(key, Boolean.TRUE) != null;
    }
}
