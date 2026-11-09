package com.lefilmla.back.exception;

import com.lefilmla.back.dto.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(TmdbNotFoundException.class)
	ResponseEntity<ErrorResponse> handleNotFound(TmdbNotFoundException ex) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse("NOT_FOUND", ex.getMessage()));
	}

	@ExceptionHandler(RateLimitExceededException.class)
	ResponseEntity<ErrorResponse> handleRateLimit(RateLimitExceededException ex) {
		return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
			.header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.retryAfterSeconds()))
			.body(new ErrorResponse("RATE_LIMITED", ex.getMessage()));
	}

	@ExceptionHandler(ConflictException.class)
	ResponseEntity<ErrorResponse> handleConflict(ConflictException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(ex.code(), ex.getMessage()));
	}

	@ExceptionHandler(InvalidCredentialsException.class)
	ResponseEntity<ErrorResponse> handleInvalidCredentials(InvalidCredentialsException ex) {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
			.body(new ErrorResponse("INVALID_CREDENTIALS", "Email ou mot de passe incorrect"));
	}

	@ExceptionHandler(UnauthenticatedException.class)
	ResponseEntity<ErrorResponse> handleUnauthenticated(UnauthenticatedException ex) {
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
			.body(new ErrorResponse("UNAUTHENTICATED", "Authentification requise"));
	}

	@ExceptionHandler(InvalidRequestException.class)
	ResponseEntity<ErrorResponse> handleInvalidRequest(InvalidRequestException ex) {
		return ResponseEntity.badRequest().body(new ErrorResponse("INVALID_REQUEST", ex.getMessage()));
	}

	@ExceptionHandler(UpstreamException.class)
	ResponseEntity<ErrorResponse> handleUpstream(UpstreamException ex) {
		log.error("Échec d'un service externe : {}", ex.getMessage());
		return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
			.body(new ErrorResponse("UPSTREAM_ERROR", "Service externe indisponible"));
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
		log.error("Erreur inattendue", ex);
		return ResponseEntity.internalServerError().body(new ErrorResponse("INTERNAL_ERROR", "Erreur interne"));
	}

	// Toutes les erreurs Spring MVC (400, 404, 405...) passent ici : on impose le format du contrat.
	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
			HttpStatusCode statusCode, WebRequest request) {
		if (ex instanceof MethodArgumentNotValidException invalid && invalid.getBindingResult().hasFieldErrors()) {
			ErrorResponse error = new ErrorResponse("INVALID_REQUEST",
					invalid.getBindingResult().getFieldErrors().get(0).getDefaultMessage());
			return ResponseEntity.status(statusCode).headers(headers).body(error);
		}
		return ResponseEntity.status(statusCode).headers(headers).body(toErrorResponse(statusCode));
	}

	private ErrorResponse toErrorResponse(HttpStatusCode statusCode) {
		if (statusCode.value() == 400) {
			return new ErrorResponse("INVALID_REQUEST", "Requête invalide");
		}
		if (statusCode.value() == 404) {
			return new ErrorResponse("NOT_FOUND", "Ressource introuvable");
		}
		if (statusCode.value() == 405) {
			return new ErrorResponse("METHOD_NOT_ALLOWED", "Méthode non autorisée");
		}
		return new ErrorResponse("REQUEST_ERROR", "Requête non traitée");
	}
}
