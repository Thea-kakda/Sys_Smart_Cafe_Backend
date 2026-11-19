package com.smartcoffee.config;
import java.time.Duration; import org.springframework.cache.annotation.EnableCaching; import org.springframework.context.annotation.*; import org.springframework.data.redis.cache.*;
@Configuration @EnableCaching public class RedisConfig { @Bean RedisCacheConfiguration defaultCacheConfiguration(){return RedisCacheConfiguration.defaultCacheConfig().entryTtl(Duration.ofMinutes(10)).disableCachingNullValues();} }

