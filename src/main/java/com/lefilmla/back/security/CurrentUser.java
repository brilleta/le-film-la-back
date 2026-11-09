package com.lefilmla.back.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Accès au principal hors des controllers (intercepteurs). */
public final class CurrentUser {

	private CurrentUser() {
	}

	/** Principal de la requête courante, ou null si anonyme. */
	public static AuthenticatedUser get() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		return authentication != null && authentication.getPrincipal() instanceof AuthenticatedUser user ? user : null;
	}
}
