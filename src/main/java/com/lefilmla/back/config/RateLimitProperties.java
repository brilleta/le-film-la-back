package com.lefilmla.back.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rate-limit.ai")
public record RateLimitProperties(long capacity, Duration period) {
}
