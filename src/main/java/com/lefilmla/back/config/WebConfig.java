package com.lefilmla.back.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class WebConfig implements WebMvcConfigurer {

	private final RateLimitInterceptor aiRateLimit;

	private final RateLimitInterceptor authRateLimit;

	public WebConfig(RateLimitProperties properties) {
		this.aiRateLimit = new RateLimitInterceptor(properties.ai(), "Trop de recherches, réessayez dans un instant");
		this.authRateLimit = new RateLimitInterceptor(properties.auth(), "Trop de tentatives, réessayez dans un instant");
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(aiRateLimit).addPathPatterns("/api/ai/search");
		registry.addInterceptor(authRateLimit).addPathPatterns("/api/auth/login", "/api/auth/register");
	}
}
