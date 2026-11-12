package com.lefilmla.back.stats;

import org.apache.kafka.streams.state.ReadOnlyWindowStore;

/** Accès en lecture au state store des statistiques (interactive query). */
@FunctionalInterface
public interface SearchStatsStore {

	/** @throws StatsUnavailableException si le flux ne tourne pas encore (démarrage, rééquilibrage) */
	ReadOnlyWindowStore<String, SearchStatsBucket> windowStore();
}
