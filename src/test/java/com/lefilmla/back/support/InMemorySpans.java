package com.lefilmla.back.support;

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.time.Duration;
import java.util.List;
import java.util.function.Predicate;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Capture les spans terminés en mémoire, pour les tests annotés {@code @AutoConfigureTracing}.
 * Les spans partent par lots : penser à réduire {@code management.opentelemetry.tracing.export.schedule-delay}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class InMemorySpans {

	@Bean
	InMemorySpanExporter inMemorySpanExporter() {
		return InMemorySpanExporter.create();
	}

	/** Attend un span qui vérifie {@code condition} (les lots arrivent en différé). */
	public static SpanData await(InMemorySpanExporter spans, Predicate<SpanData> condition) {
		return org.awaitility.Awaitility.await()
			.atMost(Duration.ofSeconds(20))
			.until(() -> first(spans.getFinishedSpanItems(), condition), java.util.Optional::isPresent)
			.orElseThrow();
	}

	private static java.util.Optional<SpanData> first(List<SpanData> spans, Predicate<SpanData> condition) {
		return spans.stream().filter(condition).findFirst();
	}
}
