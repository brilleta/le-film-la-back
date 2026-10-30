package com.lefilmla.back.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("tmdb")
public record TmdbProperties(String baseUrl, String token, String language, Duration connectTimeout,
		Duration readTimeout) {
}
