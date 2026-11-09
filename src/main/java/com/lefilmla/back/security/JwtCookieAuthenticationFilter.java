package com.lefilmla.back.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.WebUtils;

/**
 * Authentifie la requête à partir du cookie JWT. Un cookie absent, invalide ou expiré laisse la requête anonyme
 * au lieu de la rejeter : les routes publiques restent accessibles, les routes protégées répondent 401.
 */
public class JwtCookieAuthenticationFilter extends OncePerRequestFilter {

	private final JwtDecoder jwtDecoder;

	private final String cookieName;

	private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

	public JwtCookieAuthenticationFilter(JwtDecoder jwtDecoder, String cookieName) {
		this.jwtDecoder = jwtDecoder;
		this.cookieName = cookieName;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Cookie cookie = WebUtils.getCookie(request, cookieName);
		if (cookie != null && !cookie.getValue().isBlank()) {
			authenticate(cookie.getValue());
		}
		chain.doFilter(request, response);
	}

	private void authenticate(String token) {
		try {
			Jwt jwt = jwtDecoder.decode(token);
			AuthenticatedUser principal = new AuthenticatedUser(UUID.fromString(jwt.getSubject()));
			SecurityContext context = contextHolder.createEmptyContext();
			context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
			contextHolder.setContext(context);
		}
		catch (JwtException | IllegalArgumentException | NullPointerException ex) {
			// Jeton invalide, expiré ou sujet illisible : la requête reste anonyme.
		}
	}
}
