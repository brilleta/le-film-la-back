package com.lefilmla.back.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.lefilmla.back.exception.RateLimitExceededException;
import com.lefilmla.back.security.AuthenticatedUser;
import com.lefilmla.back.security.CurrentUser;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.springframework.web.servlet.HandlerInterceptor;

/** Un bucket par clé (IP ou utilisateur) ; une instance par groupe d'endpoints (voir WebConfig). */
public class RateLimitInterceptor implements HandlerInterceptor {

	public static final Function<HttpServletRequest, String> BY_IP = HttpServletRequest::getRemoteAddr;

	/** Par utilisateur connecté ; repli sur l'IP si la route est appelée sans authentification. */
	public static final Function<HttpServletRequest, String> BY_USER = request -> {
		AuthenticatedUser user = CurrentUser.get();
		return user != null ? "user:" + user.id() : "ip:" + request.getRemoteAddr();
	};

	// Borné et expirant : une rafale de clés différentes ne peut pas faire grossir la mémoire sans limite.
	private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
		.expireAfterAccess(Duration.ofHours(1))
		.maximumSize(100_000)
		.build();

	private final RateLimitProperties.Limit limit;

	private final String message;

	private final Function<HttpServletRequest, String> keyResolver;

	public RateLimitInterceptor(RateLimitProperties.Limit limit, String message,
			Function<HttpServletRequest, String> keyResolver) {
		this.limit = limit;
		this.message = message;
		this.keyResolver = keyResolver;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		Bucket bucket = buckets.get(keyResolver.apply(request), key -> newBucket());
		ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
		if (!probe.isConsumed()) {
			long seconds = TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()) + 1;
			throw new RateLimitExceededException(message, seconds);
		}
		return true;
	}

	private Bucket newBucket() {
		return Bucket.builder()
			.addLimit(l -> l.capacity(limit.capacity()).refillGreedy(limit.capacity(), limit.period()))
			.build();
	}
}
