package com.lefilmla.back.config;

import com.lefilmla.back.events.InvalidEventException;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * Gestion d'erreur des listeners : quelques nouvelles tentatives espacées, puis publication du message tel quel
 * sur {@code <topic>.DLT} avec la cause en headers. Un message invalide saute les tentatives.
 */
@Configuration
public class KafkaConsumerConfig {

	@Bean
	CommonErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
		// Partition -1 : laisse le producteur choisir, le DLT n'a pas forcément autant de partitions que la source
		var recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
				(record, ex) -> new TopicPartition(KafkaTopics.deadLetterTopic(record.topic()), -1));
		var handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1_000L, 2));
		handler.addNotRetryableExceptions(InvalidEventException.class);
		return handler;
	}
}
