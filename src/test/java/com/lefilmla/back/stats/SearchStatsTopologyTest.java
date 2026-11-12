package com.lefilmla.back.stats;

import static org.assertj.core.api.Assertions.assertThat;

import com.lefilmla.back.config.KafkaTopics;
import com.lefilmla.back.dto.SearchStatsResponse;
import com.lefilmla.back.dto.SearchStatsResponse.Ranked;
import com.lefilmla.back.events.search.SearchEvent;
import com.lefilmla.back.events.search.SearchMode;
import com.lefilmla.back.events.search.SearchOutcome;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.StreamsConfig;
import org.apache.kafka.streams.TestInputTopic;
import org.apache.kafka.streams.TopologyTestDriver;
import org.apache.kafka.streams.state.WindowStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Topologie et lecture du store sans broker, avec {@link TopologyTestDriver} et des horodatages maîtrisés. */
class SearchStatsTopologyTest {

	private static final Instant T0 = Instant.parse("2026-11-12T12:00:00Z");

	private final JsonMapper jsonMapper = JsonMapper.builder().build();

	private TopologyTestDriver driver;

	private TestInputTopic<String, String> input;

	@BeforeEach
	void setUp() {
		StreamsBuilder builder = new StreamsBuilder();
		SearchStatsTopology.build(builder, jsonMapper);
		Properties props = new Properties();
		props.put(StreamsConfig.APPLICATION_ID_CONFIG, "search-stats-test");
		props.put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, "unused:9092");
		driver = new TopologyTestDriver(builder.build(), props);
		input = driver.createInputTopic(KafkaTopics.SEARCH_EVENTS, new StringSerializer(), new StringSerializer());
	}

	@AfterEach
	void tearDown() {
		driver.close();
	}

	@Test
	void eventsAreAggregatedPerModeInOneMinuteWindows() {
		send(event(SearchMode.CLASSIC, "dune", 40, false, SearchOutcome.SUCCESS, "Dune"), T0.plusSeconds(5));
		send(event(SearchMode.CLASSIC, "dune", 2, true, SearchOutcome.SUCCESS, "Dune"), T0.plusSeconds(30));
		send(event(SearchMode.CLASSIC, "alien", 0, false, SearchOutcome.ERROR), T0.plusSeconds(50));
		send(event(SearchMode.AI, "film de science-fiction", 1200, false, SearchOutcome.SUCCESS, "Dune", "Alien"),
				T0.plusSeconds(20));
		// Minute suivante : nouvelle fenêtre
		send(event(SearchMode.CLASSIC, "dune", 10, true, SearchOutcome.SUCCESS, "Dune"), T0.plusSeconds(70));

		WindowStore<String, SearchStatsBucket> store = driver.getWindowStore(SearchStatsTopology.STORE);
		SearchStatsBucket classic = store.fetch(SearchMode.CLASSIC.name(), T0.toEpochMilli());
		assertThat(classic.searches()).isEqualTo(3);
		assertThat(classic.errors()).isEqualTo(1);
		assertThat(classic.cacheHits()).isEqualTo(1);
		assertThat(classic.latencySumMs()).isEqualTo(42);
		assertThat(classic.latencyMaxMs()).isEqualTo(40);
		assertThat(classic.queries()).containsEntry("dune", 2L).containsEntry("alien", 1L);
		assertThat(classic.titles()).containsEntry("Dune", 2L);

		assertThat(store.fetch(SearchMode.AI.name(), T0.toEpochMilli()).searches()).isEqualTo(1);
		assertThat(store.fetch(SearchMode.CLASSIC.name(), T0.plusSeconds(60).toEpochMilli()).searches()).isEqualTo(1);
	}

	@Test
	void malformedOrMiskeyedMessagesAreIgnored() {
		input.pipeInput(SearchMode.CLASSIC.name(), "pas du json", T0);
		input.pipeInput(SearchMode.AI.name(), json(event(SearchMode.CLASSIC, "dune", 5, false, SearchOutcome.SUCCESS)),
				T0);
		send(event(SearchMode.CLASSIC, "dune", 5, false, SearchOutcome.SUCCESS), T0.plusSeconds(1));

		WindowStore<String, SearchStatsBucket> store = driver.getWindowStore(SearchStatsTopology.STORE);
		assertThat(store.fetch(SearchMode.CLASSIC.name(), T0.toEpochMilli()).searches()).isEqualTo(1);
		assertThat(store.fetch(SearchMode.AI.name(), T0.toEpochMilli())).isNull();
	}

	@Test
	void snapshotMergesTheLastMinutesAndFillsTheTimeline() {
		send(event(SearchMode.CLASSIC, "dune", 30, false, SearchOutcome.SUCCESS, "Dune"), T0.plusSeconds(10));
		send(event(SearchMode.CLASSIC, "dune", 10, true, SearchOutcome.SUCCESS, "Dune"), T0.plusSeconds(130));
		send(event(SearchMode.CLASSIC, "alien", 20, false, SearchOutcome.ERROR), T0.plusSeconds(140));
		send(event(SearchMode.AI, "un western", 900, false, SearchOutcome.SUCCESS, "Django"), T0.plusSeconds(150));
		// Hors fenêtre de 3 minutes (T0+2min-2min = T0 inclus, T0-1min exclu)
		send(event(SearchMode.CLASSIC, "ancienne", 5, false, SearchOutcome.SUCCESS), T0.minusSeconds(30));

		Clock clock = Clock.fixed(T0.plusSeconds(155), ZoneOffset.UTC);
		SearchStatsService service = new SearchStatsService(() -> driver.getWindowStore(SearchStatsTopology.STORE),
				clock);
		SearchStatsResponse stats = service.snapshot(3);

		assertThat(stats.windowMinutes()).isEqualTo(3);
		assertThat(stats.timeline()).extracting(SearchStatsResponse.TimelinePoint::start)
			.containsExactly(T0, T0.plusSeconds(60), T0.plusSeconds(120));
		assertThat(stats.timeline()).extracting(SearchStatsResponse.TimelinePoint::classic).containsExactly(1L, 0L, 2L);
		assertThat(stats.timeline()).extracting(SearchStatsResponse.TimelinePoint::ai).containsExactly(0L, 0L, 1L);

		var classic = stats.modes().get(SearchMode.CLASSIC);
		assertThat(classic.searches()).isEqualTo(3);
		assertThat(classic.errorRate()).isEqualTo(1.0 / 3);
		assertThat(classic.cacheHitRate()).isEqualTo(1.0 / 3);
		assertThat(classic.avgLatencyMs()).isEqualTo(20);
		assertThat(classic.maxLatencyMs()).isEqualTo(30);
		assertThat(classic.topQueries()).containsExactly(new Ranked("dune", 2), new Ranked("alien", 1));
		assertThat(stats.modes().get(SearchMode.AI).topTitles()).containsExactly(new Ranked("Django", 1));
	}

	@Test
	void emptyStoreGivesZeroStatsForEveryMode() {
		SearchStatsService service = new SearchStatsService(() -> driver.getWindowStore(SearchStatsTopology.STORE),
				Clock.fixed(T0, ZoneOffset.UTC));

		SearchStatsResponse stats = service.snapshot(15);

		assertThat(stats.modes()).containsOnlyKeys(SearchMode.CLASSIC, SearchMode.AI);
		assertThat(stats.modes().get(SearchMode.AI).searches()).isZero();
		assertThat(stats.modes().get(SearchMode.AI).cacheHitRate()).isZero();
		assertThat(stats.timeline()).hasSize(15).allMatch(point -> point.classic() == 0 && point.ai() == 0);
	}

	@Test
	void distinctQueriesAreBoundedPerWindow() {
		SearchStatsBucket bucket = SearchStatsBucket.empty();
		for (int i = 0; i < SearchStatsBucket.MAX_DISTINCT + 20; i++) {
			bucket = bucket.plus(event(SearchMode.CLASSIC, "q" + i, 1, false, SearchOutcome.SUCCESS));
		}
		bucket = bucket.plus(event(SearchMode.CLASSIC, "q0", 1, false, SearchOutcome.SUCCESS));

		assertThat(bucket.searches()).isEqualTo(SearchStatsBucket.MAX_DISTINCT + 21);
		assertThat(bucket.queries()).hasSize(SearchStatsBucket.MAX_DISTINCT).containsEntry("q0", 2L);
	}

	private void send(SearchEvent event, Instant timestamp) {
		input.pipeInput(event.mode().name(), json(event), timestamp);
	}

	private String json(SearchEvent event) {
		return jsonMapper.writeValueAsString(event);
	}

	private static SearchEvent event(SearchMode mode, String query, long latencyMs, boolean cacheHit,
			SearchOutcome outcome, String... titles) {
		return new SearchEvent(SearchEvent.SCHEMA_VERSION, UUID.randomUUID(), mode, query, titles.length,
				List.of(titles), latencyMs, cacheHit, outcome, T0);
	}
}
