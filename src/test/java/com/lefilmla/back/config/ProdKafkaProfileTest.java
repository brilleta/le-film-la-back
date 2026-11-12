package com.lefilmla.back.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.common.config.SaslConfigs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Le profil prod (Aiven) doit se câbler sans broker SASL sous la main : on vérifie la configuration produite.
 * Sans conteneur Kafka exprès : ses {@code KafkaConnectionDetails} (@ServiceConnection) imposeraient PLAINTEXT
 * et masqueraient le profil. Listeners et création de topics désactivés : aucune connexion n'est tentée.
 */
@SpringBootTest(properties = { "KAFKA_USERNAME=avnadmin", "KAFKA_PASSWORD=secret-de-test",
		"KAFKA_CA_CERT=classpath:kafka-test-ca.pem", "spring.kafka.bootstrap-servers=kafka.invalid:9092",
		"spring.kafka.listener.auto-startup=false", "spring.kafka.admin.auto-create=false" })
@ActiveProfiles("prod")
@Import(ProdKafkaProfileTest.PostgresOnly.class)
class ProdKafkaProfileTest {

	@TestConfiguration(proxyBeanMethods = false)
	static class PostgresOnly {

		@Bean
		@ServiceConnection
		PostgreSQLContainer postgresContainer() {
			return new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
		}
	}

	@Autowired
	ProducerFactory<String, String> producerFactory;

	@Autowired
	ConsumerFactory<String, String> consumerFactory;

	@Test
	void producerAndConsumerUseSaslSslWithScramCredentialsAndAivenCa() {
		for (Map<String, Object> config : new Map[] { producerFactory.getConfigurationProperties(),
				consumerFactory.getConfigurationProperties() }) {
			assertThat(config).containsEntry(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_SSL")
				.containsEntry(SaslConfigs.SASL_MECHANISM, "SCRAM-SHA-256");
			assertThat(config.get(SaslConfigs.SASL_JAAS_CONFIG).toString()).contains("ScramLoginModule")
				.contains("username=\"avnadmin\"")
				.contains("password=\"secret-de-test\"");
			// Le SSL bundle Spring Boot fournit un truststore ou une fabrique de sockets au client Kafka
			assertThat(config.keySet()).anyMatch(key -> key.startsWith("ssl."));
		}
	}
}
