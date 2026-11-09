package com.lefilmla.back.security;

import com.lefilmla.back.config.AuthProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Component;

/** Pose et supprime le cookie de session JWT ; renouvelle le jeton CSRF à chaque changement d'identité. */
@Component
public class AuthCookies {

	private final AuthProperties properties;

	private final CsrfTokenRepository csrfTokenRepository;

	public AuthCookies(AuthProperties properties, CsrfTokenRepository csrfTokenRepository) {
		this.properties = properties;
		this.csrfTokenRepository = csrfTokenRepository;
	}

	public void signIn(String token, HttpServletRequest request, HttpServletResponse response) {
		addCookie(response, token, properties.jwt().ttl());
		rotateCsrfToken(request, response);
	}

	public void signOut(HttpServletRequest request, HttpServletResponse response) {
		addCookie(response, "", Duration.ZERO);
		rotateCsrfToken(request, response);
	}

	private void addCookie(HttpServletResponse response, String value, Duration maxAge) {
		ResponseCookie cookie = ResponseCookie.from(properties.cookie().name(), value)
			.httpOnly(true)
			.secure(properties.cookie().secure())
			.sameSite("Lax")
			.path("/")
			.maxAge(maxAge)
			.build();
		response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
	}

	// Comme Spring Security après un login/logout standard : un jeton connu avant authentification n'est plus valable.
	private void rotateCsrfToken(HttpServletRequest request, HttpServletResponse response) {
		csrfTokenRepository.saveToken(csrfTokenRepository.generateToken(request), request, response);
	}
}
