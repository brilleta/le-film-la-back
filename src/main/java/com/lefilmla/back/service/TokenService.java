package com.lefilmla.back.service;

import com.lefilmla.back.config.AuthProperties;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

@Service
public class TokenService {

	private final JwtEncoder jwtEncoder;

	private final AuthProperties properties;

	private final Clock clock;

	@Autowired
	public TokenService(JwtEncoder jwtEncoder, AuthProperties properties) {
		this(jwtEncoder, properties, Clock.systemUTC());
	}

	TokenService(JwtEncoder jwtEncoder, AuthProperties properties, Clock clock) {
		this.jwtEncoder = jwtEncoder;
		this.properties = properties;
		this.clock = clock;
	}

	/** JWT signé HS256 dont le sujet est l'id de l'utilisateur. */
	public String issue(UUID userId) {
		Instant now = clock.instant();
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.issuer("le-film-la")
			.subject(userId.toString())
			.issuedAt(now)
			.expiresAt(now.plus(properties.jwt().ttl()))
			.build();
		JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
		return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
	}
}
