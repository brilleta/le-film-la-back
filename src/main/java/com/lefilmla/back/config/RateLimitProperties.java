package com.lefilmla.back.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rate-limit")
public record RateLimitProperties(Limit ai, Limit auth) {

	public record Limit(long capacity, Duration period) {
	}
}
