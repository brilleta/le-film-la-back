package com.lefilmla.back.security;

import com.lefilmla.back.service.AiAccessService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Vérifie l'accès IA avant le controller : le résultat de la recherche est mis en cache (@Cacheable), le contrôle
 * doit donc passer avant. Enregistré avant le rate limiting pour qu'un compte refusé ne consomme pas de quota.
 */
@Component
public class AiAccessInterceptor implements HandlerInterceptor {

	private final AiAccessService aiAccessService;

	public AiAccessInterceptor(AiAccessService aiAccessService) {
		this.aiAccessService = aiAccessService;
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		aiAccessService.requireGranted(CurrentUser.get());
		return true;
	}
}
