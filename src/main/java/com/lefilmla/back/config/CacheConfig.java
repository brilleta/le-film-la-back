package com.lefilmla.back.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableCaching
public class CacheConfig {

	public static final String TMDB = "tmdb";

	public static final String AI = "ai";

	@Bean
	CacheManager cacheManager() {
		CaffeineCacheManager manager = new CaffeineCacheManager();
		manager.registerCustomCache(TMDB,
				Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(1)).maximumSize(5_000).recordStats().build());
		manager.registerCustomCache(AI,
				Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(24)).maximumSize(2_000).recordStats().build());
		return manager;
	}
}
