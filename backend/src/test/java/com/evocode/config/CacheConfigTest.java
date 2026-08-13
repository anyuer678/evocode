package com.evocode.config;

import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

/** AD-018：Redis 缓存配置与降级错误处理。 */
class CacheConfigTest {

    private final CacheConfig config = new CacheConfig();

    @Test
    void cacheManagerBuildsWithConnectionFactory() {
        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        RedisCacheManager manager = config.cacheManager(factory);
        assertNotNull(manager);
    }

    @Test
    void errorHandlerSwallowsAllCacheExceptions() {
        var handler = config.errorHandler();
        Cache cache = mock(Cache.class);
        RuntimeException ex = new RuntimeException("redis down");
        // 降级：缓存异常一律忽略，接口直接查库（不因缓存故障 500）
        assertDoesNotThrow(() -> handler.handleCacheGetError(ex, cache, "k"));
        assertDoesNotThrow(() -> handler.handleCachePutError(ex, cache, "k", "v"));
        assertDoesNotThrow(() -> handler.handleCacheEvictError(ex, cache, "k"));
        assertDoesNotThrow(() -> handler.handleCacheClearError(ex, cache));
    }
}
