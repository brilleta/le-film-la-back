package com.lefilmla.back.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lefilmla.back.PostgresTestConfiguration;
import com.lefilmla.back.client.ClaudeClient;
import com.lefilmla.back.client.TmdbClient;
import com.lefilmla.back.dto.AiCandidate;
import com.lefilmla.back.dto.TitleType;
import com.lefilmla.back.exception.UpstreamException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
@AutoConfigureMockMvc
class AiSearchControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private ClaudeClient claudeClient;

	@MockitoBean
	private TmdbClient tmdbClient;

	private ResultActions search(String ip, String description) throws Exception {
		String body = JsonMapper.builder().build().writeValueAsString(java.util.Map.of("description", description));
		return mockMvc.perform(post("/api/ai/search").contentType(MediaType.APPLICATION_JSON)
			.content(body)
			.with(request -> {
				request.setRemoteAddr(ip);
				return request;
			}));
	}

	@Test
	void returnsEnrichedResultsMatchingContract() throws Exception {
		when(claudeClient.findCandidates("rêves")).thenReturn(
				List.of(new AiCandidate("Inception", 2010, TitleType.MOVIE, "Des rêves imbriqués")));
		when(tmdbClient.findTitle(TitleType.MOVIE, "Inception", 2010)).thenReturn(Optional.of(JsonMapper.builder().build().readTree("""
				{"id":27205,"title":"Inception","release_date":"2010-07-16","poster_path":"/i.jpg","vote_average":8.4}
				""")));

		search("10.0.0.1", "rêves").andExpect(status().isOk())
			.andExpect(jsonPath("$.results.length()").value(1))
			.andExpect(jsonPath("$.results[0].tmdbId").value(27205))
			.andExpect(jsonPath("$.results[0].type").value("movie"))
			.andExpect(jsonPath("$.results[0].title").value("Inception"))
			.andExpect(jsonPath("$.results[0].year").value(2010))
			.andExpect(jsonPath("$.results[0].posterPath").value("/i.jpg"))
			.andExpect(jsonPath("$.results[0].voteAverage").value(8.4))
			.andExpect(jsonPath("$.results[0].reason").value("Des rêves imbriqués"));
	}

	@Test
	void acceptsThreeHundredCharactersAndRejectsMore() throws Exception {
		when(claudeClient.findCandidates(anyString())).thenReturn(List.of());

		search("10.0.0.2", "a".repeat(300)).andExpect(status().isOk());
		search("10.0.0.2", "a".repeat(301)).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.message").value("La description ne doit pas dépasser 300 caractères"));
		search("10.0.0.2", "   ").andExpect(status().isBadRequest());
	}

	@Test
	void equivalentDescriptionsUseTheCache() throws Exception {
		when(claudeClient.findCandidates(anyString()))
			.thenReturn(List.of(new AiCandidate("Heat", 1995, TitleType.MOVIE, "Un duel")));
		when(tmdbClient.findTitle(TitleType.MOVIE, "Heat", 1995))
			.thenReturn(Optional.of(JsonMapper.builder().build().readTree("{\"id\":949,\"title\":\"Heat\"}")));

		search("10.0.0.3", "Un Polar à Los Angeles").andExpect(status().isOk());
		search("10.0.0.3", "  un polar a  los angeles ").andExpect(status().isOk())
			.andExpect(jsonPath("$.results[0].tmdbId").value(949));

		verify(claudeClient, times(1)).findCandidates(anyString());
	}

	@Test
	void claudeFailureIsNotCachedAndReturns502() throws Exception {
		when(claudeClient.findCandidates("panne")).thenThrow(new UpstreamException("Échec", null))
			.thenReturn(List.of());

		search("10.0.0.4", "panne").andExpect(status().isBadGateway())
			.andExpect(jsonPath("$.error").value("UPSTREAM_ERROR"));
		search("10.0.0.4", "panne").andExpect(status().isOk());
	}

	@Test
	void limitsRequestsPerIp() throws Exception {
		when(claudeClient.findCandidates(anyString())).thenReturn(List.of());

		for (int i = 0; i < 10; i++) {
			search("10.0.0.5", "requête " + i).andExpect(status().isOk());
		}
		search("10.0.0.5", "une de trop").andExpect(status().isTooManyRequests())
			.andExpect(header().exists("Retry-After"))
			.andExpect(jsonPath("$.error").value("RATE_LIMITED"));
		search("10.0.0.6", "autre ip").andExpect(status().isOk());
	}
}
