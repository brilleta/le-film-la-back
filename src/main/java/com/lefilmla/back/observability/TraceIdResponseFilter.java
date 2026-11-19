package com.lefilmla.back.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Renvoie l'identifiant de la trace dans le header {@value #HEADER}, sur toutes les réponses (erreurs comprises) :
 * le front peut l'afficher avec un message d'erreur, et on retrouve la trace dans Tempo à partir d'une capture.
 * Placé juste après le filtre d'observation HTTP (la trace existe déjà) et avant Spring Security (un 401 ou un 403 le
 * porte aussi). Absent si la trace n'est pas échantillonnée : le header garantit que la trace existe.
 */
public class TraceIdResponseFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Trace-Id";

	private final Tracer tracer;

	public TraceIdResponseFilter(Tracer tracer) {
		this.tracer = tracer;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Span span = tracer.currentSpan();
		if (span != null && Boolean.TRUE.equals(span.context().sampled())) {
			response.setHeader(HEADER, span.context().traceId());
		}
		chain.doFilter(request, response);
	}

}
