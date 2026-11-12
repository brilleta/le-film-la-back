package com.lefilmla.back.events.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lefilmla.back.TestcontainersConfiguration;
import com.lefilmla.back.config.KafkaTopics;
import com.lefilmla.back.entity.AiAccess;
import com.lefilmla.back.entity.Role;
import com.lefilmla.back.entity.User;
import com.lefilmla.back.events.AiAccessEvent;
import com.lefilmla.back.events.AiAccessEventType;
import com.lefilmla.back.events.InvalidEventException;
import com.lefilmla.back.support.TestAuth;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/** Du message Kafka à l'historique exposé aux admins : ordre, idempotence et Dead Letter Topic. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AiAccessHistoryIntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	TestAuth testAuth;

	@Autowired
	KafkaTemplate<String, String> kafkaTemplate;

	@Autowired
	ConsumerFactory<String, String> consumerFactory;

	@Autowired
	AiAccessHistoryRepository repository;

	@Autowired
	JsonMapper jsonMapper;

	@Test
	void httpTransitionsEndUpInTheHistoryNewestFirst() throws Exception {
		User user = testAuth.createUser(Role.USER, AiAccess.NONE);
		User admin = testAuth.createUser(Role.ADMIN, AiAccess.GRANTED);
		String historyUrl = "/api/admin/users/" + user.getId() + "/ai-access/history";

		TestAuth.performWithCsrf(mockMvc, post("/api/ai/access-request"), testAuth.sessionCookie(user))
			.andExpect(status().isOk());
		TestAuth.performWithCsrf(mockMvc, post("/api/admin/users/" + user.getId() + "/ai-access/grant"),
				testAuth.sessionCookie(admin))
			.andExpect(status().isOk());

		await().atMost(Duration.ofSeconds(20))
			.untilAsserted(() -> assertThat(repository.findByUserIdOrderByOccurredAtDescIdDesc(user.getId()))
				.hasSize(2));

		mockMvc.perform(get(historyUrl).cookie(testAuth.sessionCookie(admin)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].type").value("GRANTED"))
			.andExpect(jsonPath("$[0].previousStatus").value("PENDING"))
			.andExpect(jsonPath("$[0].newStatus").value("GRANTED"))
			.andExpect(jsonPath("$[0].decidedBy.id").value(admin.getId().toString()))
			.andExpect(jsonPath("$[0].decidedBy.username").value(admin.getUsername()))
			.andExpect(jsonPath("$[0].occurredAt").isString())
			.andExpect(jsonPath("$[1].type").value("REQUESTED"))
			.andExpect(jsonPath("$[1].previousStatus").value("NONE"))
			.andExpect(jsonPath("$[1].newStatus").value("PENDING"))
			.andExpect(jsonPath("$[1].decidedBy").doesNotExist());
	}

	@Test
	void historyIsAdminOnlyAndUnknownUserIs404() throws Exception {
		User user = testAuth.createUser(Role.USER, AiAccess.NONE);
		User admin = testAuth.createUser(Role.ADMIN, AiAccess.GRANTED);

		mockMvc.perform(get("/api/admin/users/" + user.getId() + "/ai-access/history")
			.cookie(testAuth.sessionCookie(user))).andExpect(status().isForbidden());
		mockMvc.perform(get("/api/admin/users/" + UUID.randomUUID() + "/ai-access/history")
			.cookie(testAuth.sessionCookie(admin)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error").value("NOT_FOUND"));
		mockMvc.perform(get("/api/admin/users/" + user.getId() + "/ai-access/history")
			.cookie(testAuth.sessionCookie(admin))).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
	}

	@Test
	void redeliveredEventIsRecordedOnce() throws Exception {
		UUID userId = UUID.randomUUID();
		AiAccessEvent event = new AiAccessEvent(AiAccessEvent.SCHEMA_VERSION, UUID.randomUUID(),
				AiAccessEventType.REQUESTED, userId, AiAccess.NONE, AiAccess.PENDING, null, Instant.now());
		String json = jsonMapper.writeValueAsString(event);

		kafkaTemplate.send(KafkaTopics.AI_ACCESS_EVENTS, userId.toString(), json).get();
		kafkaTemplate.send(KafkaTopics.AI_ACCESS_EVENTS, userId.toString(), json).get();

		await().atMost(Duration.ofSeconds(20))
			.untilAsserted(() -> assertThat(repository.findByUserIdOrderByOccurredAtDescIdDesc(userId)).hasSize(1));
		// La condition doit tenir : le second message est consommé puis ignoré, pas simplement en retard
		await().during(Duration.ofSeconds(2))
			.atMost(Duration.ofSeconds(10))
			.until(() -> repository.findByUserIdOrderByOccurredAtDescIdDesc(userId).size() == 1);
	}

	@Test
	void unreadableMessageGoesToDeadLetterTopicAndConsumerKeepsGoing() throws Exception {
		String poisonKey = "poison-" + UUID.randomUUID();
		UUID userId = UUID.randomUUID();

		try (var dlt = consumerFactory.createConsumer("dlt-it-" + UUID.randomUUID(), null)) {
			dlt.subscribe(List.of(KafkaTopics.deadLetterTopic(KafkaTopics.AI_ACCESS_EVENTS)));

			kafkaTemplate.send(KafkaTopics.AI_ACCESS_EVENTS, poisonKey, "{pas du json").get();
			AiAccessEvent valid = new AiAccessEvent(AiAccessEvent.SCHEMA_VERSION, UUID.randomUUID(),
					AiAccessEventType.REQUESTED, userId, AiAccess.NONE, AiAccess.PENDING, null, Instant.now());
			kafkaTemplate.send(KafkaTopics.AI_ACCESS_EVENTS, userId.toString(), jsonMapper.writeValueAsString(valid))
				.get();

			List<ConsumerRecord<String, String>> dead = new ArrayList<>();
			await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
				dlt.poll(Duration.ofMillis(500)).forEach(record -> {
					if (poisonKey.equals(record.key())) {
						dead.add(record);
					}
				});
				assertThat(dead).hasSize(1);
			});
			ConsumerRecord<String, String> record = dead.get(0);
			assertThat(record.value()).isEqualTo("{pas du json");
			// Spring enveloppe l'exception du listener : la cause métier est dans le header « cause »
			assertThat(new String(record.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN).value(),
					StandardCharsets.UTF_8)).isEqualTo(InvalidEventException.class.getName());
			assertThat(new String(record.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC).value(),
					StandardCharsets.UTF_8)).isEqualTo(KafkaTopics.AI_ACCESS_EVENTS);

			// Le message suivant est traité normalement : le poison n'a pas bloqué la partition
			await().atMost(Duration.ofSeconds(20))
				.untilAsserted(() -> assertThat(repository.findByUserIdOrderByOccurredAtDescIdDesc(userId))
					.hasSize(1));
		}
	}
}
