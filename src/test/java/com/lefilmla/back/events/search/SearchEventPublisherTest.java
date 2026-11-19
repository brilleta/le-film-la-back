package com.lefilmla.back.events.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import tools.jackson.databind.json.JsonMapper;

class SearchEventPublisherTest {

	@SuppressWarnings("unchecked")
	private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

	private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

	private final SearchEventPublisher publisher = new SearchEventPublisher(kafkaTemplate, JsonMapper.builder().build(),
			10, meters);

	@AfterEach
	void stop() throws InterruptedException {
		publisher.destroy();
	}

	@Test
	void countsEventsKafkaRejects() {
		when(kafkaTemplate.send(any(ProducerRecord.class)))
			.thenReturn(CompletableFuture.<SendResult<String, String>>failedFuture(new IllegalStateException("broker")))
			.thenThrow(new IllegalStateException("producteur fermé"));

		publisher.publish(event());
		publisher.publish(event());

		await().atMost(Duration.ofSeconds(5))
			.untilAsserted(() -> assertThat(dropped("send_failed")).isEqualTo(2));
		assertThat(dropped("queue_full")).isZero();
	}

	@Test
	void countsNothingWhenKafkaAccepts() {
		when(kafkaTemplate.send(any(ProducerRecord.class)))
			.thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

		publisher.publish(event());

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(kafkaTemplate)
			.send(any(ProducerRecord.class)));
		assertThat(dropped("send_failed")).isZero();
	}

	private double dropped(String reason) {
		return meters.counter("search.events.dropped", "reason", reason).count();
	}

	private static SearchEvent event() {
		return new SearchEvent(SearchEvent.SCHEMA_VERSION, UUID.randomUUID(), SearchMode.CLASSIC, "dune", 3,
				List.of("Dune"), 42, false, SearchOutcome.SUCCESS, Instant.parse("2026-11-19T10:00:00Z"));
	}

}
