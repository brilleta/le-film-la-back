package com.lefilmla.back.events.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class OutboxPublisherTest {

	private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");

	private final OutboxEventRepository repository = mock(OutboxEventRepository.class);

	@SuppressWarnings("unchecked")
	private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

	private final OutboxPublisher publisher = new OutboxPublisher(repository, kafkaTemplate,
			Clock.fixed(NOW, ZoneOffset.UTC), 10, Duration.ofSeconds(1));

	@Test
	void publishesPendingEventsInOrderWithKeyAndHeaders() {
		OutboxEvent first = event("REQUESTED");
		OutboxEvent second = event("GRANTED");
		when(repository.lockNextPending(10)).thenReturn(List.of(first, second));
		when(kafkaTemplate.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

		assertThat(publisher.publishBatch()).isEqualTo(2);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<ProducerRecord<String, String>> sent = ArgumentCaptor.forClass(ProducerRecord.class);
		verify(kafkaTemplate, org.mockito.Mockito.times(2)).send(sent.capture());
		ProducerRecord<String, String> record = sent.getAllValues().get(0);
		assertThat(record.topic()).isEqualTo("ai-access.events");
		assertThat(record.key()).isEqualTo(first.getMessageKey());
		assertThat(record.value()).isEqualTo(first.getPayload());
		assertThat(new String(record.headers().lastHeader(OutboxPublisher.HEADER_EVENT_ID).value(),
				StandardCharsets.UTF_8)).isEqualTo(first.getEventId().toString());
		assertThat(new String(record.headers().lastHeader(OutboxPublisher.HEADER_EVENT_TYPE).value(),
				StandardCharsets.UTF_8)).isEqualTo("REQUESTED");
		assertThat(first.getPublishedAt()).isEqualTo(NOW);
		assertThat(first.getAttempts()).isEqualTo(1);
		assertThat(second.getPublishedAt()).isEqualTo(NOW);
	}

	@Test
	void stopsAtFirstFailureToPreserveOrderAndRecordsTheError() {
		OutboxEvent first = event("REQUESTED");
		OutboxEvent second = event("GRANTED");
		when(repository.lockNextPending(10)).thenReturn(List.of(first, second));
		when(kafkaTemplate.send(any(ProducerRecord.class)))
			.thenReturn(CompletableFuture.failedFuture(new TimeoutException("broker injoignable")));

		assertThat(publisher.publishBatch()).isZero();

		assertThat(first.getPublishedAt()).isNull();
		assertThat(first.getAttempts()).isEqualTo(1);
		assertThat(first.getLastError()).contains("TimeoutException").contains("broker injoignable");
		assertThat(second.getAttempts()).isZero();
		verify(kafkaTemplate, never()).send(argThatKey(second.getMessageKey()));
	}

	private static ProducerRecord<String, String> argThatKey(String key) {
		return org.mockito.ArgumentMatchers.argThat(record -> key.equals(record.key()));
	}

	private static OutboxEvent event(String type) {
		return new OutboxEvent(UUID.randomUUID(), "ai-access.events", UUID.randomUUID().toString(), type,
				"{\"type\":\"" + type + "\"}", NOW);
	}
}
