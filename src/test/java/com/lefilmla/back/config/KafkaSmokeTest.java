package com.lefilmla.back.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.lefilmla.back.TestcontainersConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;

/** Vérifie le câblage Kafka de bout en bout : topic créé au démarrage, aller-retour d'un message. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class KafkaSmokeTest {

	@Autowired
	KafkaAdmin kafkaAdmin;

	@Autowired
	KafkaTemplate<String, String> kafkaTemplate;

	@Autowired
	ConsumerFactory<String, String> consumerFactory;

	@Test
	void topicIsCreatedAtStartupWithConfiguredPartitions() {
		var description = kafkaAdmin.describeTopics(KafkaTopics.AI_ACCESS_EVENTS).get(KafkaTopics.AI_ACCESS_EVENTS);

		assertThat(description).isNotNull();
		assertThat(description.partitions()).hasSize(3);
	}

	@Test
	void producesAndConsumesAMessage() throws Exception {
		String key = UUID.randomUUID().toString();
		String value = "ping-" + key;

		// La ConsumerFactory porte l'adresse du conteneur Testcontainers (ConnectionDetails), pas KafkaProperties
		try (var consumer = consumerFactory.createConsumer("smoke-" + UUID.randomUUID(), null)) {
			consumer.subscribe(List.of(KafkaTopics.AI_ACCESS_EVENTS));
			kafkaTemplate.send(KafkaTopics.AI_ACCESS_EVENTS, key, value).get();

			// Un poll peut revenir vide tant que le consumer rejoint le groupe : on insiste jusqu'à l'échéance
			Instant deadline = Instant.now().plusSeconds(20);
			List<ConsumerRecord<String, String>> received = new ArrayList<>();
			while (received.isEmpty() && Instant.now().isBefore(deadline)) {
				consumer.poll(Duration.ofSeconds(1)).forEach(received::add);
			}

			assertThat(received).anySatisfy(record -> {
				assertThat(record.key()).isEqualTo(key);
				assertThat(record.value()).isEqualTo(value);
			});
		}
	}
}
