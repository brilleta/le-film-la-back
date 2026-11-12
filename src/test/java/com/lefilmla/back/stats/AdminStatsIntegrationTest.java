package com.lefilmla.back.stats;

import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lefilmla.back.TestcontainersConfiguration;
import com.lefilmla.back.client.TmdbClient;
import com.lefilmla.back.entity.AiAccess;
import com.lefilmla.back.entity.Role;
import com.lefilmla.back.support.TestAuth;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
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

	@Autowired
	SearchStatsBroadcaster broadcaster;

	@MockitoBean
	TmdbClient tmdbClient;

	@AfterEach
	void closeStreams() {
		broadcaster.completeAll();
	}

	@Test
	void streamSendsASnapshotRightAwayThenAtEachTick() throws Exception {
		Cookie admin = testAuth.sessionCookie(testAuth.createUser(Role.ADMIN, AiAccess.GRANTED));

		var result = mockMvc
			.perform(get("/api/admin/stats/stream").param("minutes", "5")
				.accept(MediaType.TEXT_EVENT_STREAM)
				.cookie(admin))
			.andExpect(request().asyncStarted())
			.andExpect(header().string("X-Accel-Buffering", "no"))
			.andReturn();

		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
			String body = result.getResponse().getContentAsString();
			// Snapshot immédiat + au moins un tick (toutes les 2 s) ; « unavailable » tant que le flux démarre
			assertThat(body.split("event:", -1).length - 1).isGreaterThanOrEqualTo(2);
			assertThat(body).containsPattern("event:(stats|unavailable)");
		});
		await().atMost(Duration.ofSeconds(45)).untilAsserted(() -> assertThat(result.getResponse().getContentAsString())
			.contains("event:stats").contains("\"windowMinutes\":5"));
		// Délai de reconnexion annoncé une seule fois, dans le premier événement
		String firstEvent = result.getResponse().getContentAsString().split("\n\n", 2)[0];
		assertThat(firstEvent).contains("retry:5000");
	}

	@Test
	void streamIsReservedToAdmins() throws Exception {
		Cookie user = testAuth.sessionCookie(testAuth.createUser(Role.USER, AiAccess.GRANTED));

		mockMvc.perform(get("/api/admin/stats/stream").accept(MediaType.TEXT_EVENT_STREAM).cookie(user))
			.andExpect(status().isForbidden());
		assertThat(broadcaster.subscriberCount()).isZero();
	}

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
