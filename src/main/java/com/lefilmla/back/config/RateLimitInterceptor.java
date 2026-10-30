package com.lefilmla.back.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.lefilmla.back.exception.RateLimitExceededException;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {

	// Borné et expirant : une rafale d'IP différentes ne peut pas faire grossir la mémoire sans limite.
	private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
		.expireAfterAccess(Duration.ofHours(1))
		.maximumSize(100_000)
		.build();

	private final RateLimitProperties properties;

	public RateLimitInterceptor(RateLimitProperties properties) {
		this.properties = properties;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		Bucket bucket = buckets.get(request.getRemoteAddr(), ip -> newBucket());
		ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
		if (!probe.isConsumed()) {
			long seconds = TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()) + 1;
			throw new RateLimitExceededException(seconds);
		}
		return true;
	}

	private Bucket newBucket() {
		return Bucket.builder()
			.addLimit(limit -> limit.capacity(properties.capacity())
				.refillGreedy(properties.capacity(), properties.period()))
			.build();
	}
}
