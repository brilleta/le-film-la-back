package com.lefilmla.back.events.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lefilmla.back.TestcontainersConfiguration;
import com.lefilmla.back.client.TmdbClient;
import com.lefilmla.back.config.KafkaTopics;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/** De la requête HTTP au message Kafka : la première recherche manque le cache, la seconde le touche. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SearchEventsIntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	ConsumerFactory<String, String> consumerFactory;

	@Autowired
	JsonMapper jsonMapper;

	@MockitoBean
	TmdbClient tmdbClient;

	@Test
	void classicSearchesArePublishedWithCacheHitOnTheModeKey() throws Exception {
		// Requête unique : le cache est partagé par tous les tests du même contexte
		String query = "dune " + UUID.randomUUID();
		when(tmdbClient.searchMulti(query)).thenReturn(JsonMapper.builder().build().readTree("""
				{"results":[{"id":1,"media_type":"movie","title":"Dune"}]}
				"""));

		try (var consumer = consumerFactory.createConsumer("search-it-" + UUID.randomUUID(), null)) {
			consumer.subscribe(List.of(KafkaTopics.SEARCH_EVENTS));

			String tab = UUID.randomUUID().toString();
			mockMvc.perform(get("/api/movies/search").param("q", query).header(SearchTracker.SESSION_HEADER, tab))
				.andExpect(status().isOk());
			mockMvc.perform(get("/api/movies/search").param("q", query)).andExpect(status().isOk());

			List<SearchEvent> received = new ArrayList<>();
			await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
				consumer.poll(Duration.ofMillis(500)).forEach(record -> {
					SearchEvent event = jsonMapper.readValue(record.value(), SearchEvent.class);
					if (event.query().equals(query)) {
						assertThat(record.key()).isEqualTo(SearchMode.CLASSIC.name());
						received.add(event);
					}
				});
				assertThat(received).hasSize(2);
			});

			assertThat(received).extracting(SearchEvent::cacheHit).containsExactly(false, true);
			assertThat(received.get(0).resultCount()).isEqualTo(1);
			assertThat(received.get(0).titles()).containsExactly("Dune");
			assertThat(received).extracting(SearchEvent::outcome).containsOnly(SearchOutcome.SUCCESS);
			// Header facultatif : la première requête porte l'onglet, la seconde n'en a pas
			assertThat(received).extracting(SearchEvent::sessionId).containsExactly(tab, null);
		}
	}
}
