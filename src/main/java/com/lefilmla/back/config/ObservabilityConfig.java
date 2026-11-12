package com.lefilmla.back.config;

import io.micrometer.observation.ObservationPredicate;
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
}
