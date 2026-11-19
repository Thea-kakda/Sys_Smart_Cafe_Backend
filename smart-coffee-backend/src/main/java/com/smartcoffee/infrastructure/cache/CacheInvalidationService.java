package com.smartcoffee.infrastructure.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

@Service
public class CacheInvalidationService {

    private static final Logger log = LoggerFactory.getLogger(CacheInvalidationService.class);
    private final CacheManager manager;

    public CacheInvalidationService(CacheManager manager) {
        this.manager = manager;
    }

    public void evict(String cache, Object key) {
        var c = manager.getCache(cache);
        if (c != null) {
            c.evict(key);
            log.debug("Evicted key '{}' from cache '{}'", key, cache);
        } else {
            log.warn("Cache '{}' not found while attempting to evict key '{}'", cache, key);
        }
    }
}
