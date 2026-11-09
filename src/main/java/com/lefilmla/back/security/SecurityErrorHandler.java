package com.lefilmla.back.security;

import com.lefilmla.back.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/** Les refus de Spring Security surviennent dans les filtres, hors du @ControllerAdvice : même format ici. */
@Component
public class SecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

	private final JsonMapper jsonMapper;

	public SecurityErrorHandler(JsonMapper jsonMapper) {
		this.jsonMapper = jsonMapper;
	}

	@Override
	public void commence(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException ex) throws IOException {
		write(response, HttpStatus.UNAUTHORIZED, new ErrorResponse("UNAUTHENTICATED", "Authentification requise"));
	}

	@Override
	public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException ex)
			throws IOException {
		ErrorResponse error = ex instanceof CsrfException
				? new ErrorResponse("CSRF_INVALID", "Jeton CSRF manquant ou invalide")
				: new ErrorResponse("FORBIDDEN", "Accès refusé");
		write(response, HttpStatus.FORBIDDEN, error);
	}

	private void write(HttpServletResponse response, HttpStatus status, ErrorResponse error) throws IOException {
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		jsonMapper.writeValue(response.getOutputStream(), error);
	}
}
