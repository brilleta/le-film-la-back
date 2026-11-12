package com.lefilmla.back.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lefilmla.back.TestcontainersConfiguration;
import com.lefilmla.back.config.KafkaTopics;
import com.lefilmla.back.entity.AiAccess;
import com.lefilmla.back.entity.Role;
import com.lefilmla.back.entity.User;
import com.lefilmla.back.events.outbox.OutboxEventRepository;
import com.lefilmla.back.events.outbox.OutboxPublisher;
import com.lefilmla.back.support.TestAuth;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/** De la requête HTTP au message Kafka : la transition écrit l'outbox, le relais publie, le payload est complet. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AiAccessEventsIntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	TestAuth testAuth;

	@Autowired
	OutboxEventRepository outbox;

	@Autowired
	ConsumerFactory<String, String> consumerFactory;

	@Autowired
	JsonMapper jsonMapper;

	@Test
	void requestThenGrantArePublishedInOrderOnTheUserKey() throws Exception {
		User user = testAuth.createUser(Role.USER, AiAccess.NONE);
		User admin = testAuth.createUser(Role.ADMIN, AiAccess.GRANTED);

		try (var consumer = consumerFactory.createConsumer("events-it-" + UUID.randomUUID(), null)) {
			consumer.subscribe(List.of(KafkaTopics.AI_ACCESS_EVENTS));

			TestAuth.performWithCsrf(mockMvc, post("/api/ai/access-request"), testAuth.sessionCookie(user))
				.andExpect(status().isOk());
			TestAuth.performWithCsrf(mockMvc, post("/api/admin/users/" + user.getId() + "/ai-access/grant"),
					testAuth.sessionCookie(admin))
				.andExpect(status().isOk());

			List<ConsumerRecord<String, String>> received = new ArrayList<>();
			await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
				consumer.poll(Duration.ofMillis(500)).forEach(record -> {
					if (user.getId().toString().equals(record.key())) {
						received.add(record);
					}
				});
				assertThat(received).hasSize(2);
			});

			AiAccessEvent requested = jsonMapper.readValue(received.get(0).value(), AiAccessEvent.class);
			assertThat(requested.type()).isEqualTo(AiAccessEventType.REQUESTED);
			assertThat(requested.userId()).isEqualTo(user.getId());
			assertThat(requested.previousStatus()).isEqualTo(AiAccess.NONE);
			assertThat(requested.newStatus()).isEqualTo(AiAccess.PENDING);
			assertThat(requested.decidedBy()).isNull();
			assertThat(requested.schemaVersion()).isEqualTo(AiAccessEvent.SCHEMA_VERSION);
			assertThat(header(received.get(0), OutboxPublisher.HEADER_EVENT_TYPE)).isEqualTo("REQUESTED");
			assertThat(header(received.get(0), OutboxPublisher.HEADER_EVENT_ID))
				.isEqualTo(requested.eventId().toString());

			AiAccessEvent granted = jsonMapper.readValue(received.get(1).value(), AiAccessEvent.class);
			assertThat(granted.type()).isEqualTo(AiAccessEventType.GRANTED);
			assertThat(granted.previousStatus()).isEqualTo(AiAccess.PENDING);
			assertThat(granted.newStatus()).isEqualTo(AiAccess.GRANTED);
			assertThat(granted.decidedBy()).isEqualTo(new AiAccessEvent.Actor(admin.getId(), admin.getUsername()));
			// Même clé : même partition, donc l'ordre de publication est conservé
			assertThat(received.get(1).partition()).isEqualTo(received.get(0).partition());

			var stored = outbox.findByEventId(granted.eventId()).orElseThrow();
			assertThat(stored.getPublishedAt()).isNotNull();
			assertThat(stored.getAttempts()).isEqualTo(1);
		}
	}

	@Test
	void rejectedTransitionWritesNothingToTheOutbox() throws Exception {
		User user = testAuth.createUser(Role.USER, AiAccess.GRANTED);
		long before = outbox.count();

		TestAuth.performWithCsrf(mockMvc, post("/api/ai/access-request"), testAuth.sessionCookie(user))
			.andExpect(status().isConflict());

		assertThat(outbox.count()).isEqualTo(before);
	}

	private static String header(ConsumerRecord<String, String> record, String name) {
		return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
	}
}
