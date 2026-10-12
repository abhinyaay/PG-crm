package com.pgms.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pgms.dashboard.DashboardDtos.OperationsDashboard;
import com.pgms.dashboard.DashboardService;
import com.pgms.property.PropertyCache;
import com.pgms.property.PropertyDtos.PropertyResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.cache.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.Cache;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;

/**
 * Redis-backed caches with per-cache TTLs and <em>typed</em> JSON serializers (no polymorphic type info in the
 * payload, so cached bytes can never instantiate arbitrary classes). Cache failures are logged and treated as a
 * miss: Redis is an optimisation, never a dependency for correctness.
 */
@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(CacheConfig.class);

    @Bean
    public RedisCacheManagerBuilderCustomizer cacheCustomizer(ObjectMapper objectMapper, AppProperties properties) {
        return builder -> builder
                // puts/evicts issued inside a transaction run after it commits: an evict before commit would let a
                // concurrent reader re-cache the old row for the whole TTL
                .transactionAware()
                .withCacheConfiguration(PropertyCache.CACHE_NAME,
                        typed(objectMapper, PropertyResponse.class).entryTtl(properties.cache().propertyTtl()))
                .withCacheConfiguration(DashboardService.CACHE_NAME,
                        typed(objectMapper, OperationsDashboard.class).entryTtl(properties.cache().dashboardTtl()));
    }

    private static <T> RedisCacheConfiguration typed(ObjectMapper objectMapper, Class<T> type) {
        return RedisCacheConfiguration.defaultCacheConfig()
                .disableCachingNullValues()
                .prefixCacheNameWith("pgms:cache:")
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(
                        new Jackson2JsonRedisSerializer<>(objectMapper, type)));
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return new CacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
                log.warn("Cache GET failed on '{}' - falling back to the database: {}", cache.getName(), e.getMessage());
            }

            @Override
            public void handleCachePutError(RuntimeException e, Cache cache, Object key, Object value) {
                log.warn("Cache PUT failed on '{}': {}", cache.getName(), e.getMessage());
            }

            @Override
            public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) {
                log.error("Cache EVICT failed on '{}' key={} - entry may be stale until TTL", cache.getName(), key);
            }

            @Override
            public void handleCacheClearError(RuntimeException e, Cache cache) {
                log.error("Cache CLEAR failed on '{}'", cache.getName());
            }
        };
    }
}
