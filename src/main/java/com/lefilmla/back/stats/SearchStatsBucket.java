package com.lefilmla.back.stats;

import com.lefilmla.back.events.search.SearchEvent;
import com.lefilmla.back.events.search.SearchOutcome;

/**
 * Agrégat technique d'une fenêtre d'une minute pour un mode : une entrée par requête réellement envoyée (y compris
 * les frappes intermédiaires), car chacune a coûté un appel TMDB ou Claude, ou un passage par le cache.
 * Les requêtes et titres populaires, eux, sont calculés sur les intentions ({@link SearchIntent}).
 */
public record SearchStatsBucket(long searches, long errors, long cacheHits, long latencySumMs, long latencyMaxMs) {

	public static SearchStatsBucket empty() {
		return new SearchStatsBucket(0, 0, 0, 0, 0);
	}

	public SearchStatsBucket plus(SearchEvent event) {
		boolean error = event.outcome() == SearchOutcome.ERROR;
		return new SearchStatsBucket(searches + 1, errors + (error ? 1 : 0), cacheHits + (event.cacheHit() ? 1 : 0),
				latencySumMs + event.latencyMs(), Math.max(latencyMaxMs, event.latencyMs()));
	}

	/** Fusion de deux fenêtres, pour présenter plusieurs minutes d'un coup. */
	public SearchStatsBucket merge(SearchStatsBucket other) {
		return new SearchStatsBucket(searches + other.searches, errors + other.errors, cacheHits + other.cacheHits,
				latencySumMs + other.latencySumMs, Math.max(latencyMaxMs, other.latencyMaxMs));
	}
}
