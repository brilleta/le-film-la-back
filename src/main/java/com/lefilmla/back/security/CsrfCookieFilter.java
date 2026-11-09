package com.lefilmla.back.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Spring Security charge le jeton CSRF à la demande : sans ce filtre, le cookie XSRF-TOKEN n'est jamais émis
 * tant qu'aucune page ne lit le jeton. Le charger ici garantit le cookie dès la première visite de la SPA
 * (un cookie déjà présent est réutilisé, pas réémis).
 */
public class CsrfCookieFilter extends OncePerRequestFilter {

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
		if (csrfToken != null) {
			csrfToken.getToken();
		}
		chain.doFilter(request, response);
	}
}
