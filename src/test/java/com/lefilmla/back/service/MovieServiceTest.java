package com.lefilmla.back.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lefilmla.back.client.TmdbClient;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class MovieServiceTest {

	private static final JsonNode RESPONSE = JsonMapper.builder().build().readTree("""
			{"page":1,"results":[
			  {"id":1,"media_type":"movie","title":"Inception"},
			  {"id":2,"media_type":"person","name":"Nolan"},
			  {"id":3,"media_type":"tv","name":"Dark"}
			]}
			""");

	private final TmdbClient tmdbClient = mock(TmdbClient.class);

	private final MovieService service = new MovieService(tmdbClient);

	@Test
	void searchRemovesPeopleAndKeepsTmdbFormat() {
		when(tmdbClient.searchMulti("nolan")).thenReturn(RESPONSE);

		JsonNode result = service.search("nolan");

		assertThat(result.path("page").asInt()).isEqualTo(1);
		assertThat(result.path("results")).hasSize(2);
		assertThat(result.path("results").get(1).path("name").asString()).isEqualTo("Dark");
	}

	@Test
	void trendingRemovesPeople() {
		when(tmdbClient.trending()).thenReturn(RESPONSE);

		assertThat(service.trending().path("results")).hasSize(2);
	}
}
