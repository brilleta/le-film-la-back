package com.lefilmla.back.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.lefilmla.back.config.TmdbProperties;
import com.lefilmla.back.dto.TitleType;
import com.lefilmla.back.exception.TmdbNotFoundException;
import com.lefilmla.back.exception.UpstreamException;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class TmdbClientTest {

	private MockRestServiceServer server;

	private TmdbClient client;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder()
			.baseUrl("https://tmdb.test/3")
			.defaultHeader("Authorization", "Bearer secret");
		server = MockRestServiceServer.bindTo(builder).build();
		TmdbProperties properties = new TmdbProperties("https://tmdb.test/3", "secret", "fr-FR", Duration.ofSeconds(1),
				Duration.ofSeconds(1));
		client = new TmdbClient(builder.build(), properties);
	}

	@Test
	void searchEncodesQueryAndSendsLanguageAndToken() {
		server.expect(requestTo("https://tmdb.test/3/search/multi?language=fr-FR&query=l%27%C3%A9t%C3%A9%20%26%20co"))
			.andExpect(header("Authorization", "Bearer secret"))
			.andRespond(withSuccess("{\"results\":[]}", MediaType.APPLICATION_JSON));

		assertThat(client.searchMulti("l'été & co").path("results")).isEmpty();
		server.verify();
	}

	@Test
	void detailsAppendsCreditsAndVideos() {
		server.expect(requestTo("https://tmdb.test/3/tv/42?language=fr-FR&append_to_response=credits,videos"))
			.andRespond(withSuccess("{\"id\":42}", MediaType.APPLICATION_JSON));

		assertThat(client.details(TitleType.TV, 42).path("id").asInt()).isEqualTo(42);
		server.verify();
	}

	@Test
	void notFoundIsTranslated() {
		server.expect(requestTo("https://tmdb.test/3/movie/1?language=fr-FR&append_to_response=credits,videos"))
			.andRespond(withStatus(HttpStatus.NOT_FOUND));

		assertThatThrownBy(() -> client.details(TitleType.MOVIE, 1)).isInstanceOf(TmdbNotFoundException.class);
	}

	@Test
	void serverErrorBecomesUpstreamExceptionWithoutToken() {
		server.expect(requestTo("https://tmdb.test/3/trending/all/week?language=fr-FR"))
			.andRespond(withStatus(HttpStatus.UNAUTHORIZED));

		assertThatThrownBy(() -> client.trending()).isInstanceOf(UpstreamException.class)
			.hasMessage("TMDB a répondu 401");
	}
}
