package com.lefilmla.back.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.lefilmla.back.TestcontainersConfiguration;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Traces de bout en bout dans le back : requête entrante → appel TMDB, dans la même trace.
 * TMDB pointe sur un port fermé : l'appel sortant échoue tout de suite, sans réseau, mais produit bien son span.
 */
@SpringBootTest(properties = {
		"tmdb.base-url=http://127.0.0.1:1",
		// Les spans sont exportés par lots : on raccourcit l'attente pour le test
		"management.opentelemetry.tracing.export.schedule-delay=50ms" })
@AutoConfigureMockMvc
@AutoConfigureTracing
@Import({ TestcontainersConfiguration.class, TracingIntegrationTest.InMemoryExporter.class })
class TracingIntegrationTest {

	@TestConfiguration(proxyBeanMethods = false)
	static class InMemoryExporter {

		@Bean
		InMemorySpanExporter inMemorySpanExporter() {
			return InMemorySpanExporter.create();
		}
	}

	@Autowired
	MockMvc mockMvc;

	@Autowired
	InMemorySpanExporter spans;

	@BeforeEach
	void reset() {
		spans.reset();
	}

	@Test
	void tmdbCallIsAChildSpanOfTheIncomingRequest() throws Exception {
		mockMvc.perform(get("/api/trending"));

		SpanData server = awaitSpan(SpanKind.SERVER);
		SpanData tmdb = awaitSpan(SpanKind.CLIENT);

		assertThat(server.getName()).isEqualTo("http get /api/trending");
		assertThat(tmdb.getTraceId()).isEqualTo(server.getTraceId());
		assertThat(tmdb.getParentSpanId()).isEqualTo(server.getSpanId());
		// L'échec de TMDB se lit dans la trace
		assertThat(tmdb.getStatus().getStatusCode()).hasToString("ERROR");
	}

	@Test
	void healthProbesAndSecurityInternalsAreNotTraced() throws Exception {
		mockMvc.perform(get("/actuator/health"));
		mockMvc.perform(get("/api/trending"));

		awaitSpan(SpanKind.SERVER);
		List<SpanData> finished = spans.getFinishedSpanItems();
		assertThat(finished).extracting(SpanData::getName)
			.noneMatch(name -> name.contains("actuator"))
			.noneMatch(name -> name.startsWith("security") || name.startsWith("authorize"));
	}

	private SpanData awaitSpan(SpanKind kind) {
		return await().atMost(Duration.ofSeconds(5))
			.until(() -> spans.getFinishedSpanItems().stream().filter(s -> s.getKind() == kind).findFirst(),
					java.util.Optional::isPresent)
			.orElseThrow();
	}
}
