package com.lefilmla.back.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Tâches planifiées et asynchrones (relais de l'outbox) ; exécutées sur threads virtuels. */
@Configuration
@EnableScheduling
@EnableAsync
public class SchedulingConfig {

	@Bean
	Clock clock() {
		return Clock.systemUTC();
	}
}
