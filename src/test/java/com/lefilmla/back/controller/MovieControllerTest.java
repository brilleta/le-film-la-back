package com.lefilmla.back.controller;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lefilmla.back.TestcontainersConfiguration;
import com.lefilmla.back.client.TmdbClient;
import com.lefilmla.back.dto.TitleType;
import com.lefilmla.back.exception.TmdbNotFoundException;
import com.lefilmla.back.exception.UpstreamException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
class MovieControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private TmdbClient tmdbClient;

	private static JsonNode json(String content) {
		return JsonMapper.builder().build().readTree(content);
	}

	@Test
	void searchReturnsResultsWithoutPeople() throws Exception {
		when(tmdbClient.searchMulti("dune")).thenReturn(json("""
				{"results":[{"id":1,"media_type":"movie","poster_path":"/a.jpg"},{"id":2,"media_type":"person"}]}
				"""));

		mockMvc.perform(get("/api/movies/search").param("q", "dune"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.results.length()").value(1))
			.andExpect(jsonPath("$.results[0].poster_path").value("/a.jpg"));
	}

	@Test
	void blankOrMissingQueryIsRejected() throws Exception {
		mockMvc.perform(get("/api/movies/search").param("q", " "))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.message").isNotEmpty());
		mockMvc.perform(get("/api/movies/search"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
	}

	@Test
	void detailsAcceptsMovieAndTv() throws Exception {
		when(tmdbClient.details(TitleType.TV, 42)).thenReturn(json("{\"id\":42,\"credits\":{},\"videos\":{}}"));

		mockMvc.perform(get("/api/movies/tv/42")).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(42));
	}

	@Test
	void invalidTypeOrIdIsRejected() throws Exception {
		mockMvc.perform(get("/api/movies/person/42"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
		mockMvc.perform(get("/api/movies/movie/abc")).andExpect(status().isBadRequest());
	}

	@Test
	void unknownTitleReturns404() throws Exception {
		when(tmdbClient.details(TitleType.MOVIE, 1)).thenThrow(new TmdbNotFoundException());

		mockMvc.perform(get("/api/movies/movie/1"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error").value("NOT_FOUND"));
	}

	@Test
	void tmdbFailureReturns502WithoutDetails() throws Exception {
		when(tmdbClient.trending()).thenThrow(new UpstreamException("TMDB a répondu 401", null));

		mockMvc.perform(get("/api/trending"))
			.andExpect(status().isBadGateway())
			.andExpect(jsonPath("$.error").value("UPSTREAM_ERROR"))
			.andExpect(jsonPath("$.message").value("Service externe indisponible"));
	}

	@Test
	void unknownRouteUsesErrorFormat() throws Exception {
		// Authentifié : un anonyme reçoit 401 sur toute route non publique, même inexistante.
		mockMvc.perform(get("/api/nope").with(user("test")))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error").value("NOT_FOUND"));
	}
}
