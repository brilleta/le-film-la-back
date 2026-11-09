package com.lefilmla.back.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("auth")
public record AuthProperties(Jwt jwt, Cookie cookie) {

	public record Jwt(String secret, Duration ttl) {
	}

	public record Cookie(String name, boolean secure) {
	}
}
