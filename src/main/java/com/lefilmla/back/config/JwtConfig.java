package com.lefilmla.back.config;

import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration
public class JwtConfig {

	private static final int MIN_KEY_BYTES = 32;

	private final SecretKey key;

	public JwtConfig(AuthProperties properties) {
		this.key = toKey(properties.jwt().secret());
	}

	@Bean
	public JwtEncoder jwtEncoder() {
		return NimbusJwtEncoder.withSecretKey(key).algorithm(MacAlgorithm.HS256).build();
	}

	@Bean
	public JwtDecoder jwtDecoder() {
		return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
	}

	// Démarrage refusé si la clé est absente ou trop courte pour HS256 : on ne signe pas avec une clé faible.
	private static SecretKey toKey(String secret) {
		byte[] bytes;
		try {
			bytes = Base64.getDecoder().decode(secret == null ? "" : secret.trim());
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalStateException("JWT_SECRET doit être encodé en base64");
		}
		if (bytes.length < MIN_KEY_BYTES) {
			throw new IllegalStateException("JWT_SECRET doit faire au moins " + MIN_KEY_BYTES + " octets");
		}
		return new SecretKeySpec(bytes, "HmacSHA256");
	}
}
