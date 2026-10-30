package com.lefilmla.back.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lefilmla.back.client.ClaudeClient;
import com.lefilmla.back.client.TmdbClient;
import com.lefilmla.back.dto.AiCandidate;
import com.lefilmla.back.dto.AiSearchResult;
import com.lefilmla.back.dto.TitleType;
import com.lefilmla.back.exception.UpstreamException;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AiSearchServiceTest {

	private final ClaudeClient claudeClient = mock(ClaudeClient.class);

	private final TmdbClient tmdbClient = mock(TmdbClient.class);

	private final AiSearchService service = new AiSearchService(claudeClient, tmdbClient);

	private static JsonNode json(String content) {
		return JsonMapper.builder().build().readTree(content);
	}

	@Test
	void enrichesMoviesAndSeriesWithTmdbDataAndAiReason() {
		when(claudeClient.findCandidates("rêves")).thenReturn(
				List.of(new AiCandidate("Inception", 2010, TitleType.MOVIE, "Des rêves imbriqués"),
						new AiCandidate("Dark", 2017, TitleType.TV, "Une énigme temporelle")));
		when(tmdbClient.findTitle(TitleType.MOVIE, "Inception", 2010)).thenReturn(Optional.of(json("""
				{"id":27205,"title":"Inception FR","release_date":"2010-07-16","poster_path":"/i.jpg","vote_average":8.4}
				""")));
		when(tmdbClient.findTitle(TitleType.TV, "Dark", 2017)).thenReturn(Optional.of(json("""
				{"id":70523,"name":"Dark FR","first_air_date":"2017-12-01","poster_path":null,"vote_average":8.3}
				""")));

		assertThat(service.search("rêves").results()).containsExactly(
				new AiSearchResult(27205, "movie", "Inception FR", 2010, "/i.jpg", 8.4, "Des rêves imbriqués"),
				new AiSearchResult(70523, "tv", "Dark FR", 2017, null, 8.3, "Une énigme temporelle"));
	}

	@Test
	void removesCandidatesNotFoundOnTmdb() {
		when(claudeClient.findCandidates("x")).thenReturn(List.of(new AiCandidate("Inventé", 1999, TitleType.MOVIE, "r"),
				new AiCandidate("Réel", 2000, TitleType.MOVIE, "r")));
		when(tmdbClient.findTitle(TitleType.MOVIE, "Inventé", 1999)).thenReturn(Optional.empty());
		when(tmdbClient.findTitle(TitleType.MOVIE, "Réel", 2000)).thenReturn(Optional.of(json("{\"id\":1,\"title\":\"Réel\"}")));

		assertThat(service.search("x").results()).extracting(AiSearchResult::tmdbId).containsExactly(1L);
	}

	@Test
	void returnsEmptyListWhenNothingIsFound() {
		when(claudeClient.findCandidates("x")).thenReturn(List.of(new AiCandidate("Inventé", 1999, TitleType.MOVIE, "r")));
		when(tmdbClient.findTitle(any(), anyString(), anyInt())).thenReturn(Optional.empty());

		assertThat(service.search("x").results()).isEmpty();
	}

	@Test
	void removesDuplicatesKeepingModelOrder() {
		when(claudeClient.findCandidates("x")).thenReturn(List.of(new AiCandidate("A", 2000, TitleType.MOVIE, "première"),
				new AiCandidate("A bis", 2000, TitleType.MOVIE, "seconde")));
		when(tmdbClient.findTitle(any(), anyString(), anyInt())).thenReturn(Optional.of(json("{\"id\":1,\"title\":\"A\"}")));

		assertThat(service.search("x").results()).extracting(AiSearchResult::reason).containsExactly("première");
	}

	@Test
	void verifiesAtMostTenCandidates() {
		when(claudeClient.findCandidates("x")).thenReturn(IntStream.range(0, 15)
			.mapToObj(i -> new AiCandidate("T" + i, 2000, TitleType.MOVIE, "r"))
			.toList());
		when(tmdbClient.findTitle(any(), anyString(), anyInt())).thenReturn(Optional.empty());

		service.search("x");

		verify(tmdbClient, times(10)).findTitle(any(), anyString(), anyInt());
	}

	@Test
	void keepsResultsWhenOneVerificationFails() {
		when(claudeClient.findCandidates("x")).thenReturn(List.of(new AiCandidate("KO", 2000, TitleType.MOVIE, "r"),
				new AiCandidate("OK", 2000, TitleType.MOVIE, "r")));
		when(tmdbClient.findTitle(TitleType.MOVIE, "KO", 2000)).thenThrow(new UpstreamException("TMDB a répondu 500", null));
		when(tmdbClient.findTitle(TitleType.MOVIE, "OK", 2000)).thenReturn(Optional.of(json("{\"id\":2,\"title\":\"OK\"}")));

		assertThat(service.search("x").results()).extracting(AiSearchResult::tmdbId).containsExactly(2L);
	}

	@Test
	void failsWhenEveryVerificationFails() {
		when(claudeClient.findCandidates("x")).thenReturn(List.of(new AiCandidate("KO", 2000, TitleType.MOVIE, "r")));
		when(tmdbClient.findTitle(any(), anyString(), anyInt())).thenThrow(new UpstreamException("TMDB a répondu 500", null));

		assertThatThrownBy(() -> service.search("x")).isInstanceOf(UpstreamException.class);
	}
}
