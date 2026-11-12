package com.lefilmla.back.stats;

import com.lefilmla.back.events.search.SearchEvent;
import com.lefilmla.back.events.search.SearchMode;
import java.time.Instant;
import java.util.List;

/**
 * Une intention de recherche : les frappes successives d'un même onglet, dans un même mode, sans pause de plus de
 * {@link SearchStatsTopology#INTENT_GAP}. Seule la dernière requête compte (« batman », pas « bat ») ; les fautes de
 * frappe corrigées dans l'élan disparaissent aussi.
 *
 * @param requests nombre de requêtes réellement envoyées pendant la frappe
 */
public record SearchIntent(SearchMode mode, String query, List<String> titles, long requests, Instant lastAt) {

	public static SearchIntent empty() {
		return new SearchIntent(null, null, List.of(), 0, null);
	}

	/** La requête la plus récente l'emporte (les événements d'une session peuvent arriver dans le désordre). */
	public SearchIntent plus(SearchEvent event) {
		boolean newer = lastAt == null || !event.occurredAt().isBefore(lastAt);
		return newer ? new SearchIntent(event.mode(), event.query(), event.titles(), requests + 1, event.occurredAt())
				: new SearchIntent(mode, query, titles, requests + 1, lastAt);
	}

	/** Deux sessions qui se rejoignent (événement arrivé entre les deux) : on garde la plus récente. */
	public SearchIntent merge(SearchIntent other) {
		SearchIntent latest = other.lastAt != null && (lastAt == null || other.lastAt.isAfter(lastAt)) ? other : this;
		return new SearchIntent(latest.mode, latest.query, latest.titles, requests + other.requests, latest.lastAt);
	}
}
