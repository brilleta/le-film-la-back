package com.lefilmla.back.dto;

import com.lefilmla.back.events.search.SearchMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Statistiques des recherches sur les {@code windowMinutes} dernières minutes (minute en cours incluse).
 * Les taux sont entre 0 et 1 ; {@code timeline} contient une entrée par minute, zéros compris.
 */
public record SearchStatsResponse(Instant generatedAt, int windowMinutes, Map<SearchMode, ModeStats> modes,
		List<TimelinePoint> timeline) {

	public record ModeStats(long searches, long errors, double errorRate, double cacheHitRate, long avgLatencyMs,
			long maxLatencyMs, List<Ranked> topQueries, List<Ranked> topTitles) {
	}

	public record TimelinePoint(Instant start, long classic, long ai) {
	}

	public record Ranked(String value, long count) {
	}
}
