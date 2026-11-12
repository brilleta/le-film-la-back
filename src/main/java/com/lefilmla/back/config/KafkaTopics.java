package com.lefilmla.back.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Déclaration des topics : créés au démarrage par {@code KafkaAdmin} s'ils n'existent pas.
 * La clé de chaque topic fixe l'ordre garanti : par utilisateur pour les accès IA, par mode pour les recherches.
 */
@Configuration
public class KafkaTopics {

	/** Transitions d'accès à la recherche IA (demande, grant, reject, revoke). */
	public static final String AI_ACCESS_EVENTS = "ai-access.events";

	/** Une recherche (classique ou IA), anonyme ; clé = mode de recherche. Source des statistiques temps réel. */
	public static final String SEARCH_EVENTS = "search.events";

	/** Suffixe des Dead Letter Topics (convention Spring Kafka). */
	public static final String DLT_SUFFIX = ".DLT";

	public static String deadLetterTopic(String topic) {
		return topic + DLT_SUFFIX;
	}

	private final int partitions;

	private final int replicationFactor;

	public KafkaTopics(@Value("${kafka.topics.partitions:3}") int partitions,
			@Value("${kafka.topics.replication-factor:1}") int replicationFactor) {
		this.partitions = partitions;
		this.replicationFactor = replicationFactor;
	}

	@Bean
	NewTopic aiAccessEventsTopic() {
		return TopicBuilder.name(AI_ACCESS_EVENTS).partitions(partitions).replicas(replicationFactor).build();
	}

	@Bean
	NewTopic searchEventsTopic() {
		return TopicBuilder.name(SEARCH_EVENTS).partitions(partitions).replicas(replicationFactor).build();
	}

	/** Messages que le consommateur n'a pas pu traiter ; une partition suffit, ils sont rares et inspectés à la main. */
	@Bean
	NewTopic aiAccessEventsDeadLetterTopic() {
		return TopicBuilder.name(deadLetterTopic(AI_ACCESS_EVENTS)).partitions(1).replicas(replicationFactor).build();
	}
}
