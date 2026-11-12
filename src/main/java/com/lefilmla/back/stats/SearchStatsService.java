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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.streams.errors.InvalidStateStoreException;
import org.apache.kafka.streams.kstream.Windowed;
import org.apache.kafka.streams.state.KeyValueIterator;
import org.apache.kafka.streams.state.ReadOnlySessionStore;
import org.apache.kafka.streams.state.ReadOnlyWindowStore;
import org.apache.kafka.streams.state.WindowStoreIterator;
import org.springframework.stereotype.Service;

/**
 * Recompose les N dernières minutes : mesures techniques depuis les fenêtres d'une minute, intentions depuis les
 * sessions de frappe terminées ou en cours sur la période.
 */
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
			Map<SearchMode, Intents> intents = intents(store.intentStore(), from, now);
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
				modes.put(mode, toModeStats(total, intents.getOrDefault(mode, new Intents())));
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

	/** Sessions qui se terminent dans la période (en cours comprises) : une session = une recherche. */
	private static Map<SearchMode, Intents> intents(ReadOnlySessionStore<String, SearchIntent> sessions, Instant from,
			Instant now) {
		Map<SearchMode, Intents> byMode = new EnumMap<>(SearchMode.class);
		// Bornes de clé nulles : toutes les sessions, tous onglets confondus
		try (KeyValueIterator<Windowed<String>, SearchIntent> it = sessions.findSessions(null, null,
				from.toEpochMilli(), now.toEpochMilli())) {
			while (it.hasNext()) {
				SearchIntent intent = it.next().value;
				if (intent.mode() != null) {
					byMode.computeIfAbsent(intent.mode(), mode -> new Intents()).add(intent);
				}
			}
		}
		return byMode;
	}

	private static ModeStats toModeStats(SearchStatsBucket bucket, Intents intents) {
		long searches = bucket.searches();
		return new ModeStats(searches, intents.count, bucket.errors(), ratio(bucket.errors(), searches),
				ratio(bucket.cacheHits(), searches), searches == 0 ? 0 : bucket.latencySumMs() / searches,
				bucket.latencyMaxMs(), top(intents.queries), top(intents.titles));
	}

	private static final class Intents {

		private long count;

		private final Map<String, Long> queries = new HashMap<>();

		private final Map<String, Long> titles = new HashMap<>();

		void add(SearchIntent intent) {
			count++;
			queries.merge(intent.query(), 1L, Long::sum);
			intent.titles().forEach(title -> titles.merge(title, 1L, Long::sum));
		}
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
