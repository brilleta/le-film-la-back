package com.lefilmla.back.config;

import com.lefilmla.back.security.AiAccessInterceptor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@EnableConfigurationProperties(RateLimitProperties.class)
public class WebConfig implements WebMvcConfigurer {

	private final AiAccessInterceptor aiAccessInterceptor;

	private final RateLimitInterceptor aiRateLimit;

	private final RateLimitInterceptor authRateLimit;

	public WebConfig(RateLimitProperties properties, AiAccessInterceptor aiAccessInterceptor) {
		this.aiAccessInterceptor = aiAccessInterceptor;
		this.aiRateLimit = new RateLimitInterceptor(properties.ai(), "Trop de recherches, réessayez dans un instant",
				RateLimitInterceptor.BY_USER);
		this.authRateLimit = new RateLimitInterceptor(properties.auth(), "Trop de tentatives, réessayez dans un instant",
				RateLimitInterceptor.BY_IP);
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		// Ordre important : accès IA vérifié avant de consommer le quota.
		registry.addInterceptor(aiAccessInterceptor).addPathPatterns("/api/ai/search");
		registry.addInterceptor(aiRateLimit).addPathPatterns("/api/ai/search");
		registry.addInterceptor(authRateLimit).addPathPatterns("/api/auth/login", "/api/auth/register");
	}
}
