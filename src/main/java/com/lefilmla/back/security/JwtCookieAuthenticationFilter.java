package com.lefilmla.back.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.lefilmla.back.entity.User;
import com.lefilmla.back.repository.UserRepository;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
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
 * L'utilisateur est relu en base à chaque requête (rôle, accès IA, compte supprimé).
 */
public class JwtCookieAuthenticationFilter extends OncePerRequestFilter {

	private final JwtDecoder jwtDecoder;

	private final String cookieName;

	private final UserRepository userRepository;

	private final SecurityContextHolderStrategy contextHolder = SecurityContextHolder.getContextHolderStrategy();

	public JwtCookieAuthenticationFilter(JwtDecoder jwtDecoder, String cookieName, UserRepository userRepository) {
		this.jwtDecoder = jwtDecoder;
		this.cookieName = cookieName;
		this.userRepository = userRepository;
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
		Optional<User> user;
		try {
			Jwt jwt = jwtDecoder.decode(token);
			user = userRepository.findById(UUID.fromString(jwt.getSubject()));
		}
		catch (JwtException | IllegalArgumentException | NullPointerException ex) {
			// Jeton invalide, expiré ou sujet illisible : la requête reste anonyme.
			return;
		}
		// Compte supprimé depuis l'émission du jeton : anonyme aussi.
		user.ifPresent(this::setAuthentication);
	}

	private void setAuthentication(User user) {
		AuthenticatedUser principal = new AuthenticatedUser(user.getId(), user.getRole(), user.getAiAccess());
		var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name()));
		SecurityContext context = contextHolder.createEmptyContext();
		context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, authorities));
		contextHolder.setContext(context);
	}
}
