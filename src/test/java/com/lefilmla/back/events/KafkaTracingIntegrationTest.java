package com.lefilmla.back.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lefilmla.back.TestcontainersConfiguration;
import com.lefilmla.back.client.TmdbClient;
import com.lefilmla.back.config.KafkaTopics;
import com.lefilmla.back.entity.AiAccess;
import com.lefilmla.back.entity.Role;
import com.lefilmla.back.entity.User;
import com.lefilmla.back.events.outbox.OutboxEventRepository;
import com.lefilmla.back.events.outbox.OutboxPublisher;
import com.lefilmla.back.support.InMemorySpans;
import com.lefilmla.back.support.TestAuth;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/**
 * Une même trace de la requête HTTP jusqu'au consommateur Kafka, y compris à travers l'outbox (relue plus tard
 * par un autre thread) et le pool dédié aux événements de recherche.
 */
@SpringBootTest(properties = "management.opentelemetry.tracing.export.schedule-delay=50ms")
@AutoConfigureMockMvc
@AutoConfigureTracing
@Import({ TestcontainersConfiguration.class, InMemorySpans.class })
class KafkaTracingIntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	TestAuth testAuth;

	@Autowired
	OutboxEventRepository outbox;

	@Autowired
	InMemorySpanExporter spans;

	@MockitoBean
	TmdbClient tmdbClient;

	@Test
	void outboxEventKeepsTheTraceOfTheRequestUpToTheListener() throws Exception {
		User user = testAuth.createUser(Role.USER, AiAccess.NONE);

		TestAuth.performWithCsrf(mockMvc, post("/api/ai/access-request"), testAuth.sessionCookie(user))
			.andExpect(status().isOk());

		SpanData request = InMemorySpans.await(spans,
				span -> span.getKind() == SpanKind.SERVER && span.getName().equals("http post /api/ai/access-request"));
		String traceId = request.getTraceId();

		// Le contexte a été mis à plat dans la ligne d'outbox…
		var stored = outbox.findAll()
			.stream()
			.filter(event -> user.getId().toString().equals(event.getMessageKey()))
			.findFirst()
			.orElseThrow();
		assertThat(stored.getTraceparent()).startsWith("00-" + traceId + "-");

		// …puis restauré par le relais : publication, envoi et consommation restent dans la même trace
		SpanData publish = InMemorySpans.await(spans,
				span -> span.getName().equals(OutboxPublisher.PUBLISH_SPAN) && span.getTraceId().equals(traceId));
		SpanData send = InMemorySpans.await(spans,
				span -> span.getKind() == SpanKind.PRODUCER && span.getTraceId().equals(traceId));
		SpanData receive = InMemorySpans.await(spans,
				span -> span.getKind() == SpanKind.CONSUMER && span.getTraceId().equals(traceId));

		assertThat(publish.getParentSpanId()).isEqualTo(request.getSpanId());
		assertThat(send.getParentSpanId()).isEqualTo(publish.getSpanId());
		assertThat(send.getName()).contains(KafkaTopics.AI_ACCESS_EVENTS);
		assertThat(receive.getParentSpanId()).isEqualTo(send.getSpanId());
	}

	@Test
	void searchEventIsSentInTheTraceOfTheSearch() throws Exception {
		String query = "dune " + UUID.randomUUID();
		when(tmdbClient.searchMulti(query)).thenReturn(JsonMapper.builder().build().readTree("""
				{"results":[{"id":1,"media_type":"movie","title":"Dune"}]}
				"""));

		mockMvc.perform(get("/api/movies/search").param("q", query)).andExpect(status().isOk());

		SpanData request = InMemorySpans.await(spans,
				span -> span.getKind() == SpanKind.SERVER && span.getName().equals("http get /api/movies/search"));
		SpanData send = InMemorySpans.await(spans, span -> span.getKind() == SpanKind.PRODUCER
				&& span.getTraceId().equals(request.getTraceId()));

		// Envoyé depuis le thread dédié, après la réponse : le contexte a suivi la tâche
		assertThat(send.getName()).contains(KafkaTopics.SEARCH_EVENTS);
		assertThat(send.getParentSpanId()).isEqualTo(request.getSpanId());
	}
}
