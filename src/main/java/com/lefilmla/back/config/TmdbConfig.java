package com.lefilmla.back.config;

import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(TmdbProperties.class)
public class TmdbConfig {

	/**
	 * Part du {@link RestClient.Builder} de Spring Boot (et non de {@code RestClient.builder()}) : il porte
	 * l'ObservationRegistry, donc chaque appel TMDB devient un span enfant de la requête HTTP en cours.
	 */
	@Bean
	RestClient tmdbRestClient(RestClient.Builder builder, TmdbProperties properties) {
		HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
		JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
		requestFactory.setReadTimeout(properties.readTimeout());
		return builder
			.baseUrl(properties.baseUrl())
			.requestFactory(requestFactory)
			.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.token())
			.build();
	}
}
