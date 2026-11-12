package com.lefilmla.back.events.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.lefilmla.back.config.CacheConfig;
import com.lefilmla.back.dto.AiSearchResponse;
import com.lefilmla.back.dto.AiSearchResult;
import com.lefilmla.back.exception.UpstreamException;
import com.lefilmla.back.service.AiSearchService;
import com.lefilmla.back.service.MovieService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SearchTrackerTest {

	private static final Instant NOW = Instant.parse("2026-11-12T12:00:00Z");

	private final SearchEventPublisher publisher = mock(SearchEventPublisher.class);

	private final ConcurrentMapCacheManager cacheManager = new ConcurrentMapCacheManager(CacheConfig.TMDB,
			CacheConfig.AI);

	private SearchTracker tracker;

	@BeforeEach
	void setUp() {
		tracker = new SearchTracker(publisher, cacheManager, Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@Test
	void classicSearchPublishesResultsAndTitles() {
		JsonNode response = JsonMapper.builder().build().readTree("""
				{"results":[{"id":1,"title":"Dune"},{"id":2,"name":"Dune : Prophecy"},{"id":3}]}
				""");

		assertThat(tracker.classic("Dune", () -> response)).isSameAs(response);

		SearchEvent event = published();
		assertThat(event.mode()).isEqualTo(SearchMode.CLASSIC);
		assertThat(event.query()).isEqualTo("dune");
		assertThat(event.resultCount()).isEqualTo(3);
		assertThat(event.titles()).containsExactly("Dune", "Dune : Prophecy");
		assertThat(event.outcome()).isEqualTo(SearchOutcome.SUCCESS);
		assertThat(event.cacheHit()).isFalse();
		assertThat(event.latencyMs()).isNotNegative();
		assertThat(event.occurredAt()).isEqualTo(NOW);
		assertThat(event.schemaVersion()).isEqualTo(SearchEvent.SCHEMA_VERSION);
	}

	@Test
	void cacheHitIsDetectedWithTheServiceCacheKeys() {
		cacheManager.getCache(CacheConfig.TMDB).put(MovieService.searchCacheKey("Dune"), "cached");
		cacheManager.getCache(CacheConfig.AI).put(AiSearchService.normalize("Un film  ÉPIQUE"), "cached");

		tracker.classic("Dune", () -> JsonMapper.builder().build().readTree("{\"results\":[]}"));
		assertThat(published().cacheHit()).isTrue();

		tracker.ai("un film épique", () -> new AiSearchResponse(List.of()));
		var captor = ArgumentCaptor.forClass(SearchEvent.class);
		verify(publisher, org.mockito.Mockito.times(2)).publish(captor.capture());
		assertThat(captor.getValue().cacheHit()).isTrue();
		assertThat(captor.getValue().mode()).isEqualTo(SearchMode.AI);
	}

	@Test
	void aiSearchKeepsAtMostFiveTitles() {
		List<AiSearchResult> results = java.util.stream.IntStream.rangeClosed(1, 7)
			.mapToObj(i -> new AiSearchResult(i, "movie", "Film " + i, 2000 + i, null, null, "raison"))
			.toList();

		tracker.ai("des films", () -> new AiSearchResponse(results));

		SearchEvent event = published();
		assertThat(event.resultCount()).isEqualTo(7);
		assertThat(event.titles()).hasSize(SearchEvent.MAX_TITLES).first().isEqualTo("Film 1");
	}

	@Test
	void failedSearchIsPublishedAsErrorAndRethrown() {
		UpstreamException failure = new UpstreamException("TMDB indisponible", null);

		assertThatThrownBy(() -> tracker.classic("dune", () -> {
			throw failure;
		})).isSameAs(failure);

		SearchEvent event = published();
		assertThat(event.outcome()).isEqualTo(SearchOutcome.ERROR);
		assertThat(event.resultCount()).isZero();
		assertThat(event.titles()).isEmpty();
	}

	@Test
	void queryIsNormalizedAndTruncated() {
		assertThat(SearchTracker.anonymize("  Le Seigneur   des ANNEAUX ")).isEqualTo("le seigneur des anneaux");
		assertThat(SearchTracker.anonymize("é".repeat(300))).hasSize(SearchEvent.MAX_QUERY_LENGTH)
			.isEqualTo("e".repeat(SearchEvent.MAX_QUERY_LENGTH));
	}

	private SearchEvent published() {
		var captor = ArgumentCaptor.forClass(SearchEvent.class);
		verify(publisher).publish(captor.capture());
		return captor.getValue();
	}
}
