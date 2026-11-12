package com.lefilmla.back.stats;

import org.apache.kafka.streams.state.ReadOnlySessionStore;
import org.apache.kafka.streams.state.ReadOnlyWindowStore;

/** Accès en lecture aux state stores des statistiques (interactive queries). */
public interface SearchStatsStore {

	/** @throws StatsUnavailableException si le flux ne tourne pas encore (démarrage, rééquilibrage) */
	ReadOnlyWindowStore<String, SearchStatsBucket> windowStore();

	/** @throws StatsUnavailableException si le flux ne tourne pas encore (démarrage, rééquilibrage) */
	ReadOnlySessionStore<String, SearchIntent> intentStore();
}
