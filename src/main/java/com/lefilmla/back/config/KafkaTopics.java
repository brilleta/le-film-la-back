package com.lefilmla.back.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * Déclaration des topics : créés au démarrage par {@code KafkaAdmin} s'ils n'existent pas.
 * Les partitions sont clées par utilisateur : l'ordre des événements d'un même compte est garanti.
 */
@Configuration
public class KafkaTopics {

	/** Transitions d'accès à la recherche IA (demande, grant, reject, revoke). */
	public static final String AI_ACCESS_EVENTS = "ai-access.events";

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
}
