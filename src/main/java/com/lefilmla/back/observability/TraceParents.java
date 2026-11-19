package com.lefilmla.back.observability;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Fait traverser une trace là où le contexte de thread ne suit pas : une ligne en base relue plus tard par
 * un autre thread (l'outbox). Le contexte est mis à plat au format W3C {@code traceparent}, le même que celui
 * des headers HTTP et Kafka, puis restauré au moment de l'envoi.
 */
public class TraceParents {

	static final String TRACEPARENT = "traceparent";

	private final Tracer tracer;

	private final Propagator propagator;

	public TraceParents(Tracer tracer, Propagator propagator) {
		this.tracer = tracer;
		this.propagator = propagator;
	}

	/** Sans traçage (tests unitaires) : rien n'est capturé, l'action s'exécute telle quelle. */
	public static TraceParents noop() {
		return new TraceParents(Tracer.NOOP, Propagator.NOOP);
	}

	/** @return le {@code traceparent} de la trace en cours, ou {@code null} hors trace */
	public String current() {
		TraceContext context = tracer.currentTraceContext().context();
		if (context == null) {
			return null;
		}
		Map<String, String> carrier = new HashMap<>();
		propagator.inject(context, carrier, Map::put);
		return carrier.get(TRACEPARENT);
	}

	/**
	 * Exécute {@code action} dans un span {@code name}, enfant de {@code traceparent} s'il est connu (sinon racine
	 * d'une nouvelle trace). Les spans créés pendant l'action, l'envoi Kafka par exemple, en deviennent les enfants.
	 */
	public <T> T inSpan(String name, String traceparent, Supplier<T> action) {
		Span.Builder builder = traceparent == null ? tracer.spanBuilder()
				: propagator.extract(Map.of(TRACEPARENT, traceparent), Map::get);
		Span span = builder.name(name).start();
		try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
			return action.get();
		}
		catch (RuntimeException ex) {
			span.error(ex);
			throw ex;
		}
		finally {
			span.end();
		}
	}
}
