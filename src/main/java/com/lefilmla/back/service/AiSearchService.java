package com.lefilmla.back.service;

import com.lefilmla.back.client.ClaudeClient;
import com.lefilmla.back.client.TmdbClient;
import com.lefilmla.back.config.CacheConfig;
import com.lefilmla.back.dto.AiCandidate;
import com.lefilmla.back.dto.AiSearchResponse;
import com.lefilmla.back.dto.AiSearchResult;
import com.lefilmla.back.dto.TitleType;
import com.lefilmla.back.exception.UpstreamException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
public class AiSearchService {

	private static final Logger log = LoggerFactory.getLogger(AiSearchService.class);

	private static final int MAX_CANDIDATES = 10;

	private final ClaudeClient claudeClient;

	private final TmdbClient tmdbClient;

	public AiSearchService(ClaudeClient claudeClient, TmdbClient tmdbClient) {
		this.claudeClient = claudeClient;
		this.tmdbClient = tmdbClient;
	}

	@Cacheable(cacheNames = CacheConfig.AI, key = "T(com.lefilmla.back.service.AiSearchService).normalize(#description)",
			unless = "#result.results().isEmpty()")
	public AiSearchResponse search(String description) {
		List<AiCandidate> candidates = claudeClient.findCandidates(description)
			.stream()
			.limit(MAX_CANDIDATES)
			.toList();

		List<Future<Optional<AiSearchResult>>> lookups = new ArrayList<>();
		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			for (AiCandidate candidate : candidates) {
				lookups.add(executor.submit(() -> verify(candidate)));
			}
		}

		Map<String, AiSearchResult> results = new LinkedHashMap<>();
		int failures = 0;
		for (Future<Optional<AiSearchResult>> lookup : lookups) {
			try {
				lookup.get().ifPresent(result -> results.putIfAbsent(result.type() + ":" + result.tmdbId(), result));
			}
			catch (ExecutionException ex) {
				failures++;
				log.warn("Vérification TMDB échouée : {}", ex.getCause().getMessage());
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new UpstreamException("Vérification TMDB interrompue", ex);
			}
		}
		// Sans ça, une panne de TMDB se confondrait avec « aucune œuvre trouvée ».
		if (results.isEmpty() && failures > 0) {
			throw new UpstreamException("TMDB indisponible pour la vérification", null);
		}
		return new AiSearchResponse(List.copyOf(results.values()));
	}

	public static String normalize(String description) {
		String withoutAccents = Normalizer.normalize(description, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
		return withoutAccents.toLowerCase(Locale.ROOT).strip().replaceAll("\\s+", " ");
	}

	private Optional<AiSearchResult> verify(AiCandidate candidate) {
		return tmdbClient.findTitle(candidate.type(), candidate.title(), candidate.year())
			.map(match -> toResult(candidate, match));
	}

	private AiSearchResult toResult(AiCandidate candidate, JsonNode match) {
		boolean movie = candidate.type() == TitleType.MOVIE;
		String title = match.path(movie ? "title" : "name").asString(candidate.title());
		String date = match.path(movie ? "release_date" : "first_air_date").asString("");
		Integer year = date.length() >= 4 ? Integer.valueOf(date.substring(0, 4)) : null;
		String posterPath = match.path("poster_path").isString() ? match.path("poster_path").asString() : null;
		Double voteAverage = match.path("vote_average").isNumber() ? match.path("vote_average").asDouble() : null;
		return new AiSearchResult(match.path("id").asLong(), candidate.type().tmdbName(), title, year, posterPath,
				voteAverage, candidate.reason());
	}
}
