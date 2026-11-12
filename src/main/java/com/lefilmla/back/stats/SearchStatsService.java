package com.lefilmla.back.stats;

import com.lefilmla.back.dto.SearchStatsResponse;
import com.lefilmla.back.dto.SearchStatsResponse.ModeStats;
import com.lefilmla.back.dto.SearchStatsResponse.Ranked;
import com.lefilmla.back.dto.SearchStatsResponse.TimelinePoint;
import com.lefilmla.back.events.search.SearchMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.streams.errors.InvalidStateStoreException;
import org.apache.kafka.streams.state.ReadOnlyWindowStore;
import org.apache.kafka.streams.state.WindowStoreIterator;
import org.springframework.stereotype.Service;

/** Recompose les N dernières minutes à partir des fenêtres d'une minute du state store. */
@Service
public class SearchStatsService {

	public static final int TOP = 10;

	private final SearchStatsStore store;

	private final Clock clock;

	public SearchStatsService(SearchStatsStore store, Clock clock) {
		this.store = store;
		this.clock = clock;
	}

	public SearchStatsResponse snapshot(int minutes) {
		Instant now = clock.instant();
		long bucketMs = SearchStatsTopology.BUCKET.toMillis();
		Instant current = Instant.ofEpochMilli(now.toEpochMilli() / bucketMs * bucketMs);
		Instant from = current.minusMillis(bucketMs * (minutes - 1));

		Map<Instant, long[]> timeline = new LinkedHashMap<>();
		for (int i = 0; i < minutes; i++) {
			timeline.put(from.plusMillis(bucketMs * i), new long[SearchMode.values().length]);
		}

		Map<SearchMode, ModeStats> modes = new EnumMap<>(SearchMode.class);
		try {
			ReadOnlyWindowStore<String, SearchStatsBucket> windows = store.windowStore();
			for (SearchMode mode : SearchMode.values()) {
				SearchStatsBucket total = SearchStatsBucket.empty();
				try (WindowStoreIterator<SearchStatsBucket> it = windows.fetch(mode.name(), from, current)) {
					while (it.hasNext()) {
						var window = it.next();
						total = total.merge(window.value);
						long[] counts = timeline.get(Instant.ofEpochMilli(window.key));
						if (counts != null) {
							counts[mode.ordinal()] += window.value.searches();
						}
					}
				}
				modes.put(mode, toModeStats(total));
			}
		}
		catch (InvalidStateStoreException ex) {
			// Rééquilibrage pendant la lecture
			throw new StatsUnavailableException();
		}

		List<TimelinePoint> points = new ArrayList<>();
		timeline.forEach((start, counts) -> points
			.add(new TimelinePoint(start, counts[SearchMode.CLASSIC.ordinal()], counts[SearchMode.AI.ordinal()])));
		return new SearchStatsResponse(now, minutes, modes, points);
	}

	private static ModeStats toModeStats(SearchStatsBucket bucket) {
		long searches = bucket.searches();
		return new ModeStats(searches, bucket.errors(), ratio(bucket.errors(), searches),
				ratio(bucket.cacheHits(), searches), searches == 0 ? 0 : bucket.latencySumMs() / searches,
				bucket.latencyMaxMs(), top(bucket.queries()), top(bucket.titles()));
	}

	private static double ratio(long part, long total) {
		return total == 0 ? 0 : (double) part / total;
	}

	private static List<Ranked> top(Map<String, Long> counts) {
		return counts.entrySet()
			.stream()
			.sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder())
				.thenComparing(Map.Entry.comparingByKey()))
			.limit(TOP)
			.map(entry -> new Ranked(entry.getKey(), entry.getValue()))
			.toList();
	}
}
