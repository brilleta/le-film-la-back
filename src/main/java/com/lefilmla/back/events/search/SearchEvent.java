package com.lefilmla.back.events.search;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Événement publié sur {@code search.events} à chaque recherche, classique ou IA. Anonyme : aucun identifiant
 * d'utilisateur, la requête est normalisée et tronquée. Alimente les statistiques temps réel de l'admin.
 *
 * @param titles les premiers titres renvoyés (au plus {@link #MAX_TITLES})
 * @param sessionId identifiant aléatoire de l'onglet (header {@code X-Search-Session}), sans lien avec le compte ;
 * null si absent ou invalide. Sert à regrouper les frappes successives d'une même recherche (v2).
 */
public record SearchEvent(int schemaVersion, UUID eventId, SearchMode mode, String query, int resultCount,
		List<String> titles, long latencyMs, boolean cacheHit, SearchOutcome outcome, Instant occurredAt,
		String sessionId) {

	public static final int SCHEMA_VERSION = 2;

	public static final int MAX_QUERY_LENGTH = 100;

	public static final int MAX_TITLES = 5;
}
