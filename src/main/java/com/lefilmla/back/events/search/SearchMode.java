package com.lefilmla.back.events.search;

/** Recherche classique (TMDB) ou recherche IA (Claude + vérification TMDB). Sert aussi de clé Kafka. */
public enum SearchMode {
	CLASSIC, AI
}
