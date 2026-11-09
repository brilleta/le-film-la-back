package com.lefilmla.back;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import com.lefilmla.back.support.TestAuth;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Postgres jetable pour tout test qui charge le contexte complet (la datasource est obligatoire). */
@TestConfiguration(proxyBeanMethods = false)
@Import(TestAuth.class)
public class PostgresTestConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));
	}
}
