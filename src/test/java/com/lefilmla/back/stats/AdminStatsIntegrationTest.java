package com.lefilmla.back.stats;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lefilmla.back.TestcontainersConfiguration;
import com.lefilmla.back.client.TmdbClient;
import com.lefilmla.back.entity.AiAccess;
import com.lefilmla.back.entity.Role;
import com.lefilmla.back.support.TestAuth;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

/** Recherche HTTP → search.events → Kafka Streams → state store → GET /api/admin/stats. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AdminStatsIntegrationTest {

	@Autowired
	MockMvc mockMvc;

	@Autowired
	TestAuth testAuth;

	@MockitoBean
	TmdbClient tmdbClient;

	@Test
	void aSearchShowsUpInTheAdminStats() throws Exception {
		String query = "stats " + UUID.randomUUID();
		when(tmdbClient.searchMulti(query)).thenReturn(JsonMapper.builder().build().readTree("""
				{"results":[{"id":1,"media_type":"movie","title":"Dune"}]}
				"""));
		Cookie admin = testAuth.sessionCookie(testAuth.createUser(Role.ADMIN, AiAccess.GRANTED));

		mockMvc.perform(get("/api/movies/search").param("q", query)).andExpect(status().isOk());

		await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> mockMvc
			.perform(get("/api/admin/stats").cookie(admin))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.windowMinutes").value(15))
			.andExpect(jsonPath("$.timeline.length()").value(15))
			.andExpect(jsonPath("$.modes.CLASSIC.topQueries[*].value", hasItem(query)))
			.andExpect(jsonPath("$.modes.AI.searches").isNumber()));
	}

	@Test
	void statsAreReservedToAdmins() throws Exception {
		Cookie user = testAuth.sessionCookie(testAuth.createUser(Role.USER, AiAccess.GRANTED));

		mockMvc.perform(get("/api/admin/stats").cookie(user)).andExpect(status().isForbidden());
		mockMvc.perform(get("/api/admin/stats")).andExpect(status().isUnauthorized());
	}

	@Test
	void windowIsLimitedToTheStoreRetention() throws Exception {
		Cookie admin = testAuth.sessionCookie(testAuth.createUser(Role.ADMIN, AiAccess.GRANTED));

		mockMvc.perform(get("/api/admin/stats").param("minutes", "61").cookie(admin))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
		mockMvc.perform(get("/api/admin/stats").param("minutes", "0").cookie(admin))
			.andExpect(status().isBadRequest());
	}
}
