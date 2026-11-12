package com.lefilmla.back.stats;

import com.lefilmla.back.events.search.SearchEvent;
import com.lefilmla.back.events.search.SearchOutcome;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Agrégat d'une fenêtre d'une minute pour un mode de recherche : compteurs, latences et occurrences des requêtes
 * et des titres. Les deux dictionnaires sont bornés à {@link #MAX_DISTINCT} entrées : au-delà, les nouvelles
 * valeurs sont ignorées (les plus fréquentes sont presque toujours vues avant), la taille du state store reste maîtrisée.
 */
public record SearchStatsBucket(long searches, long errors, long cacheHits, long latencySumMs, long latencyMaxMs,
		Map<String, Long> queries, Map<String, Long> titles) {

	public static final int MAX_DISTINCT = 100;

	public static SearchStatsBucket empty() {
		return new SearchStatsBucket(0, 0, 0, 0, 0, Map.of(), Map.of());
	}

	public SearchStatsBucket plus(SearchEvent event) {
		boolean error = event.outcome() == SearchOutcome.ERROR;
		return new SearchStatsBucket(searches + 1, errors + (error ? 1 : 0), cacheHits + (event.cacheHit() ? 1 : 0),
				latencySumMs + event.latencyMs(), Math.max(latencyMaxMs, event.latencyMs()),
				increment(queries, List.of(event.query())), increment(titles, event.titles()));
	}

	/** Fusion de deux fenêtres, pour présenter plusieurs minutes d'un coup. */
	public SearchStatsBucket merge(SearchStatsBucket other) {
		return new SearchStatsBucket(searches + other.searches, errors + other.errors, cacheHits + other.cacheHits,
				latencySumMs + other.latencySumMs, Math.max(latencyMaxMs, other.latencyMaxMs),
				sum(queries, other.queries), sum(titles, other.titles));
	}

	private static Map<String, Long> increment(Map<String, Long> counts, Collection<String> keys) {
		Map<String, Long> copy = new HashMap<>(counts);
		for (String key : keys) {
			if (copy.containsKey(key) || copy.size() < MAX_DISTINCT) {
				copy.merge(key, 1L, Long::sum);
			}
		}
		return copy;
	}

	private static Map<String, Long> sum(Map<String, Long> left, Map<String, Long> right) {
		Map<String, Long> total = new HashMap<>(left);
		right.forEach((key, count) -> total.merge(key, count, Long::sum));
		return total;
	}
}
