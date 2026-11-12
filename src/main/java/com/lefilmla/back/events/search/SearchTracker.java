package com.lefilmla.back.events.search;

import com.lefilmla.back.config.CacheConfig;
import com.lefilmla.back.dto.AiSearchResponse;
import com.lefilmla.back.dto.AiSearchResult;
import com.lefilmla.back.service.AiSearchService;
import com.lefilmla.back.service.MovieService;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Mesure une recherche (latence, succès, présence en cache, résultats) et publie un {@link SearchEvent}.
 * Appelé par les controllers, donc à l'extérieur du proxy {@code @Cacheable} : la latence mesurée est celle
 * que voit l'utilisateur, cache compris. Le cache est consulté juste avant l'appel, avec la même clé que le service.
 */
@Component
public class SearchTracker {

	/** Header envoyé par le front : identifiant aléatoire par onglet, pour regrouper les frappes d'une recherche. */
	public static final String SESSION_HEADER = "X-Search-Session";

	/** Valeur fournie par le client : on n'accepte qu'un identifiant court et inoffensif (UUID en pratique). */
	private static final Pattern SESSION_PATTERN = Pattern.compile("[A-Za-z0-9-]{8,64}");

	private final SearchEventPublisher publisher;

	private final CacheManager cacheManager;

	private final Clock clock;

	public SearchTracker(SearchEventPublisher publisher, CacheManager cacheManager, Clock clock) {
		this.publisher = publisher;
		this.cacheManager = cacheManager;
		this.clock = clock;
	}

	public JsonNode classic(String query, String sessionId, Supplier<JsonNode> search) {
		boolean cached = isCached(CacheConfig.TMDB, MovieService.searchCacheKey(query));
		return track(SearchMode.CLASSIC, query, sessionId, cached, search, SearchTracker::summarizeClassic);
	}

	public AiSearchResponse ai(String description, String sessionId, Supplier<AiSearchResponse> search) {
		boolean cached = isCached(CacheConfig.AI, AiSearchService.normalize(description));
		return track(SearchMode.AI, description, sessionId, cached, search,
				response -> summary(response.results().size(),
						response.results().stream().map(AiSearchResult::title).toList()));
	}

	private <T> T track(SearchMode mode, String query, String sessionId, boolean cacheHit, Supplier<T> search,
			Function<T, Summary> summarize) {
		String session = validSession(sessionId);
		long start = System.nanoTime();
		try {
			T result = search.get();
			Summary summary = summarize.apply(result);
			publish(mode, query, session, summary.resultCount(), summary.titles(), start, cacheHit,
					SearchOutcome.SUCCESS);
			return result;
		}
		catch (RuntimeException ex) {
			publish(mode, query, session, 0, List.of(), start, false, SearchOutcome.ERROR);
			throw ex;
		}
	}

	private void publish(SearchMode mode, String query, String sessionId, int resultCount, List<String> titles,
			long start, boolean cacheHit, SearchOutcome outcome) {
		long latencyMs = (System.nanoTime() - start) / 1_000_000;
		publisher.publish(new SearchEvent(SearchEvent.SCHEMA_VERSION, UUID.randomUUID(), mode, anonymize(query),
				resultCount, titles, latencyMs, cacheHit, outcome, clock.instant(), sessionId));
	}

	static String validSession(String sessionId) {
		return sessionId != null && SESSION_PATTERN.matcher(sessionId).matches() ? sessionId : null;
	}

	private boolean isCached(String cacheName, Object key) {
		Cache cache = cacheManager.getCache(cacheName);
		return cache != null && cache.get(key) != null;
	}

	static String anonymize(String query) {
		String normalized = AiSearchService.normalize(query);
		return normalized.length() <= SearchEvent.MAX_QUERY_LENGTH ? normalized
				: normalized.substring(0, SearchEvent.MAX_QUERY_LENGTH);
	}

	private static Summary summarizeClassic(JsonNode response) {
		JsonNode results = response.path("results");
		List<String> titles = new ArrayList<>();
		for (JsonNode result : results) {
			String title = result.path("title").asString(result.path("name").asString(""));
			if (!title.isBlank()) {
				titles.add(title);
			}
		}
		return summary(results.size(), titles);
	}

	private static Summary summary(int resultCount, List<String> titles) {
		return new Summary(resultCount, List.copyOf(titles.subList(0, Math.min(titles.size(), SearchEvent.MAX_TITLES))));
	}

	private record Summary(int resultCount, List<String> titles) {
	}
}
