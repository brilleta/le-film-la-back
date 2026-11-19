package com.lefilmla.back.config;

import com.lefilmla.back.events.outbox.OutboxEventRepository;
import com.lefilmla.back.observability.TraceIdResponseFilter;
import com.lefilmla.back.observability.TraceParents;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.InitializingBean;
import io.micrometer.observation.ObservationPredicate;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * Écarte des traces ce qui n'aide pas à diagnostiquer et noierait les vraies requêtes :
 * <ul>
 * <li>les sondes {@code /actuator/**} (Render vérifie la santé en continu) ;</li>
 * <li>le flux SSE des stats, ouvert 15 min : un span de 15 min ne dit rien d'utile ;</li>
 * <li>les tâches {@code @Scheduled} (balayage de l'outbox toutes les 2 s) ;</li>
 * <li>les observations internes de Spring Security (une dizaine de spans par requête).</li>
 * </ul>
 */
@Configuration
public class ObservabilityConfig {

	static final String STATS_STREAM_PATH = "/api/admin/stats/stream";

	@Bean
	ObservationPredicate ignoreNoisyObservations() {
		return (name, context) -> {
			if (context instanceof ServerRequestObservationContext server) {
				String path = server.getCarrier().getRequestURI();
				return !path.startsWith("/actuator") && !path.equals(STATS_STREAM_PATH);
			}
			return !name.equals("tasks.scheduled.execution") && !name.startsWith("spring.security.");
		};
	}

	/** Juste après le filtre d'observation HTTP de Boot (ordre {@code HIGHEST_PRECEDENCE + 1}) ; sans traçage, inerte. */
	@Bean
	FilterRegistrationBean<TraceIdResponseFilter> traceIdResponseFilter(ObjectProvider<Tracer> tracer) {
		var registration = new FilterRegistrationBean<>(
				new TraceIdResponseFilter(tracer.getIfAvailable(() -> Tracer.NOOP)));
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 2);
		return registration;
	}

	/**
	 * Branche l'appender Logback déclaré dans {@code logback-spring.xml} sur le SDK OpenTelemetry de Boot.
	 * Les logs émis avant (démarrage) sont gardés en mémoire puis envoyés ; sans endpoint de logs, rien ne part.
	 */
	@Bean
	InitializingBean installOpenTelemetryLogAppender(OpenTelemetry openTelemetry) {
		return () -> OpenTelemetryAppender.install(openTelemetry);
	}

	/**
	 * Santé de l'outbox : un stock qui monte ou un âge qui grandit signifie que Kafka ne reçoit plus les événements
	 * (broker absent, erreur répétée). Lu à chaque export (une requête par minute, la base est déjà sollicitée par
	 * le balayage de l'outbox toutes les 2 s).
	 */
	@Bean
	MeterBinder outboxMetrics(OutboxEventRepository outbox, Clock clock) {
		return registry -> {
			Gauge.builder("outbox.events.pending", outbox, OutboxEventRepository::countByPublishedAtIsNull)
				.description("Événements de l'outbox pas encore publiés sur Kafka")
				.register(registry);
			Gauge.builder("outbox.events.oldest.pending.age", outbox, repo -> {
				Instant oldest = repo.oldestPendingCreatedAt();
				return oldest == null ? 0 : Duration.between(oldest, clock.instant()).toMillis() / 1000.0;
			}).description("Âge du plus ancien événement en attente").baseUnit("seconds").register(registry);
		};
	}

	/** Tracer et propagateur absents quand le traçage est coupé (tests sans {@code @AutoConfigureTracing}). */
	@Bean
	TraceParents traceParents(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
		return new TraceParents(tracer.getIfAvailable(() -> Tracer.NOOP),
				propagator.getIfAvailable(() -> Propagator.NOOP));
	}
}
