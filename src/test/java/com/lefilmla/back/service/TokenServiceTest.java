package com.lefilmla.back.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lefilmla.back.config.AuthProperties;
import com.lefilmla.back.config.JwtConfig;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

class TokenServiceTest {

	private static final String SECRET = "dGVzdC1zZWNyZXQtZm9yLWp3dC1zaWduaW5nLTMyYnl0ZXM=";

	private final AuthProperties properties = new AuthProperties(
			new AuthProperties.Jwt(SECRET, Duration.ofHours(2)), new AuthProperties.Cookie("auth_token", true));

	private final JwtConfig jwtConfig = new JwtConfig(properties);

	private final JwtDecoder decoder = jwtConfig.jwtDecoder();

	@Test
	void issuedTokenCarriesUserIdAndConfiguredLifetime() {
		UUID userId = UUID.randomUUID();
		TokenService service = new TokenService(jwtConfig.jwtEncoder(), properties);

		Jwt jwt = decoder.decode(service.issue(userId));

		assertThat(jwt.getSubject()).isEqualTo(userId.toString());
		assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt())).isEqualTo(Duration.ofHours(2));
	}

	@Test
	void expiredTokenIsRejected() {
		Clock threeHoursAgo = Clock.fixed(Instant.now().minus(Duration.ofHours(3)), ZoneOffset.UTC);
		TokenService service = new TokenService(jwtConfig.jwtEncoder(), properties, threeHoursAgo);

		String token = service.issue(UUID.randomUUID());

		assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
	}

	@Test
	void shortSecretPreventsStartup() {
		AuthProperties weak = new AuthProperties(new AuthProperties.Jwt("c2hvcnQ=", Duration.ofHours(1)),
				new AuthProperties.Cookie("auth_token", true));

		assertThatThrownBy(() -> new JwtConfig(weak)).isInstanceOf(IllegalStateException.class);
	}
}
