package com.pgms.property;

import com.pgms.common.exception.NotFoundException;
import com.pgms.property.PropertyDtos.PropertyResponse;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-through Redis cache for property details - read on almost every screen, changed rarely.
 * Access control is enforced by callers <em>before</em> hitting the cache, so cached data is never served
 * to someone who may not see it. Bed availability is deliberately NOT cached (it must be exact).
 */
@Component
public class PropertyCache {

    public static final String CACHE_NAME = "properties";

    private final PropertyRepository repository;

    public PropertyCache(PropertyRepository repository) {
        this.repository = repository;
    }

    @Cacheable(cacheNames = CACHE_NAME, key = "#id")
    @Transactional(readOnly = true)
    public PropertyResponse get(Long id) {
        return repository.findById(id).map(PropertyResponse::of).orElseThrow(() -> new NotFoundException("Property", id));
    }

    @CacheEvict(cacheNames = CACHE_NAME, key = "#id")
    public void evict(Long id) {
        // annotation-driven
    }
}
