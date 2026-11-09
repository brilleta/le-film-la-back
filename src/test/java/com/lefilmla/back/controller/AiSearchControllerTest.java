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
import com.lefilmla.back.entity.AiAccess;
import com.lefilmla.back.entity.Role;
import com.lefilmla.back.entity.User;
import com.lefilmla.back.exception.UpstreamException;
import com.lefilmla.back.repository.UserRepository;
import com.lefilmla.back.support.TestAuth;
import jakarta.servlet.http.Cookie;
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

	@Autowired
	private TestAuth testAuth;

	@Autowired
	private UserRepository userRepository;

	private static String body(String description) {
		return JsonMapper.builder().build().writeValueAsString(java.util.Map.of("description", description));
	}

	/** Recherche envoyée par l'utilisateur donné (session + CSRF). */
	private ResultActions search(User user, String description) throws Exception {
		return TestAuth.performWithCsrf(mockMvc,
				post("/api/ai/search").contentType(MediaType.APPLICATION_JSON).content(body(description)),
				testAuth.sessionCookie(user));
	}

	private User granted() {
		return testAuth.createUser(Role.USER, AiAccess.GRANTED);
	}

	@Test
	void returnsEnrichedResultsMatchingContract() throws Exception {
		User user = granted();
		when(claudeClient.findCandidates("rêves")).thenReturn(
				List.of(new AiCandidate("Inception", 2010, TitleType.MOVIE, "Des rêves imbriqués")));
		when(tmdbClient.findTitle(TitleType.MOVIE, "Inception", 2010)).thenReturn(Optional.of(JsonMapper.builder().build().readTree("""
				{"id":27205,"title":"Inception","release_date":"2010-07-16","poster_path":"/i.jpg","vote_average":8.4}
				""")));

		search(user, "rêves").andExpect(status().isOk())
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
		User user = granted();
		when(claudeClient.findCandidates(anyString())).thenReturn(List.of());

		search(user, "a".repeat(300)).andExpect(status().isOk());
		search(user, "a".repeat(301)).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.message").value("La description ne doit pas dépasser 300 caractères"));
		search(user, "   ").andExpect(status().isBadRequest());
	}

	@Test
	void equivalentDescriptionsUseTheCache() throws Exception {
		User user = granted();
		when(claudeClient.findCandidates(anyString()))
			.thenReturn(List.of(new AiCandidate("Heat", 1995, TitleType.MOVIE, "Un duel")));
		when(tmdbClient.findTitle(TitleType.MOVIE, "Heat", 1995))
			.thenReturn(Optional.of(JsonMapper.builder().build().readTree("{\"id\":949,\"title\":\"Heat\"}")));

		search(user, "Un Polar à Los Angeles").andExpect(status().isOk());
		search(user, "  un polar a  los angeles ").andExpect(status().isOk())
			.andExpect(jsonPath("$.results[0].tmdbId").value(949));

		verify(claudeClient, times(1)).findCandidates(anyString());
	}

	@Test
	void claudeFailureIsNotCachedAndReturns502() throws Exception {
		User user = granted();
		when(claudeClient.findCandidates("panne")).thenThrow(new UpstreamException("Échec", null))
			.thenReturn(List.of());

		search(user, "panne").andExpect(status().isBadGateway())
			.andExpect(jsonPath("$.error").value("UPSTREAM_ERROR"));
		search(user, "panne").andExpect(status().isOk());
	}

	@Test
	void limitsRequestsPerUserNotPerIp() throws Exception {
		when(claudeClient.findCandidates(anyString())).thenReturn(List.of());
		User user = granted();

		for (int i = 0; i < 10; i++) {
			search(user, "requête " + i).andExpect(status().isOk());
		}
		search(user, "une de trop").andExpect(status().isTooManyRequests())
			.andExpect(header().exists("Retry-After"))
			.andExpect(jsonPath("$.error").value("RATE_LIMITED"));
		// Même IP (MockMvc), autre compte : quota distinct.
		search(granted(), "autre utilisateur").andExpect(status().isOk());
	}

	@Test
	void anonymousSearchIsRejectedWith401() throws Exception {
		TestAuth.performWithCsrf(mockMvc,
				post("/api/ai/search").contentType(MediaType.APPLICATION_JSON).content(body("rêves")))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
	}

	@Test
	void userWithoutGrantedAccessGets403EvenForACachedDescription() throws Exception {
		when(claudeClient.findCandidates(anyString())).thenReturn(List.of());
		search(granted(), "déjà en cache").andExpect(status().isOk());

		for (AiAccess status : List.of(AiAccess.NONE, AiAccess.PENDING, AiAccess.REJECTED)) {
			search(testAuth.createUser(Role.USER, status), "déjà en cache")
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error").value("AI_ACCESS_REQUIRED"));
		}
	}

	@Test
	void revokedAccessIsEnforcedImmediatelyWithTheSameCookie() throws Exception {
		when(claudeClient.findCandidates(anyString())).thenReturn(List.of());
		User user = granted();
		Cookie session = testAuth.sessionCookie(user);
		TestAuth.performWithCsrf(mockMvc,
				post("/api/ai/search").contentType(MediaType.APPLICATION_JSON).content(body("avant retrait")), session)
			.andExpect(status().isOk());

		User reloaded = userRepository.findById(user.getId()).orElseThrow();
		reloaded.decideAiAccess(AiAccess.NONE, null, java.time.Instant.now());
		userRepository.saveAndFlush(reloaded);

		TestAuth.performWithCsrf(mockMvc,
				post("/api/ai/search").contentType(MediaType.APPLICATION_JSON).content(body("après retrait")), session)
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error").value("AI_ACCESS_REQUIRED"));
	}

	@Test
	void searchWithoutCsrfHeaderIsForbidden() throws Exception {
		mockMvc.perform(post("/api/ai/search").contentType(MediaType.APPLICATION_JSON).content(body("rêves"))
				.cookie(testAuth.sessionCookie(granted())))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error").value("CSRF_INVALID"));
	}
}
