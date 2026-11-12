package com.lefilmla.back;

import com.lefilmla.back.support.TestAuth;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Postgres et Kafka jetables pour tout test qui charge le contexte complet
 * (la datasource est obligatoire, et sans broker les listeners Kafka boucleraient en erreur).
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(TestAuth.class)
public class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
	}

	@Bean
	@ServiceConnection
	KafkaContainer kafkaContainer() {
		return new KafkaContainer(DockerImageName.parse("apache/kafka:4.1.0"));
	}
}
