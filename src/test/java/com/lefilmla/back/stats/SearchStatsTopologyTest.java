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
import org.apache.kafka.streams.state.ReadOnlySessionStore;
import org.apache.kafka.streams.state.ReadOnlyWindowStore;
import org.apache.kafka.streams.state.WindowStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Topologie et lecture des stores sans broker, avec {@link TopologyTestDriver} et des horodatages maîtrisés. */
class SearchStatsTopologyTest {

	private static final Instant T0 = Instant.parse("2026-11-12T12:00:00Z");

	private static final String TAB = "onglet-aaaa-1111";

	private static final String OTHER_TAB = "onglet-bbbb-2222";

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
	void requestsAreAggregatedPerModeInOneMinuteWindows() {
		send(event(SearchMode.CLASSIC, "dune", 40, false, SearchOutcome.SUCCESS), T0.plusSeconds(5));
		send(event(SearchMode.CLASSIC, "dune", 2, true, SearchOutcome.SUCCESS), T0.plusSeconds(30));
		send(event(SearchMode.CLASSIC, "alien", 0, false, SearchOutcome.ERROR), T0.plusSeconds(50));
		send(event(SearchMode.AI, "film de science-fiction", 1200, false, SearchOutcome.SUCCESS), T0.plusSeconds(20));
		// Minute suivante : nouvelle fenêtre
		send(event(SearchMode.CLASSIC, "dune", 10, true, SearchOutcome.SUCCESS), T0.plusSeconds(70));

		WindowStore<String, SearchStatsBucket> store = driver.getWindowStore(SearchStatsTopology.STORE);
		assertThat(store.fetch(SearchMode.CLASSIC.name(), T0.toEpochMilli()))
			.isEqualTo(new SearchStatsBucket(3, 1, 1, 42, 40));
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
	void keystrokesOfOneTabCollapseIntoTheirLastQuery() {
		// « bat » puis « batman » ; « inglorious », la faute « inglorious vba » puis la correction : 2 recherches
		typed(TAB, SearchMode.CLASSIC, T0, "bat", "batman");
		typed(TAB, SearchMode.CLASSIC, T0.plusSeconds(20), "inglorious", "inglorious vba", "inglorious bas",
				"inglorious basterds");

		SearchStatsResponse stats = service(T0.plusSeconds(30)).snapshot(5);

		var classic = stats.modes().get(SearchMode.CLASSIC);
		assertThat(classic.searches()).isEqualTo(6);
		assertThat(classic.intents()).isEqualTo(2);
		assertThat(classic.topQueries()).containsExactlyInAnyOrder(new Ranked("batman", 1),
				new Ranked("inglorious basterds", 1));
		// Titres de la dernière requête seulement, pas ceux des frappes intermédiaires
		assertThat(classic.topTitles()).containsExactlyInAnyOrder(new Ranked("batman (titre)", 1),
				new Ranked("inglorious basterds (titre)", 1));
	}

	@Test
	void aPauseLongerThanTheGapStartsANewSearch() {
		typed(TAB, SearchMode.CLASSIC, T0, "dune");
		send(event(TAB, SearchMode.CLASSIC, "dune",
				T0.plus(SearchStatsTopology.INTENT_GAP).plusSeconds(1)), T0.plus(SearchStatsTopology.INTENT_GAP).plusSeconds(1));

		var classic = service(T0.plusSeconds(30)).snapshot(5).modes().get(SearchMode.CLASSIC);

		assertThat(classic.intents()).isEqualTo(2);
		assertThat(classic.topQueries()).containsExactly(new Ranked("dune", 2));
	}

	@Test
	void tabsAndModesAreSeparateSearchesAndEventsWithoutSessionCountAlone() {
		typed(TAB, SearchMode.CLASSIC, T0, "dune");
		typed(OTHER_TAB, SearchMode.CLASSIC, T0, "dune");
		typed(TAB, SearchMode.AI, T0.plusSeconds(1), "un desert et des vers geants");
		typed(null, SearchMode.CLASSIC, T0.plusSeconds(1), "alien", "aliens");

		var modes = service(T0.plusSeconds(30)).snapshot(5).modes();

		assertThat(modes.get(SearchMode.CLASSIC).intents()).isEqualTo(4);
		assertThat(modes.get(SearchMode.CLASSIC).topQueries()).containsExactly(new Ranked("dune", 2),
				new Ranked("alien", 1), new Ranked("aliens", 1));
		assertThat(modes.get(SearchMode.AI).intents()).isEqualTo(1);
	}

	@Test
	void snapshotMergesTheLastMinutesAndFillsTheTimeline() {
		send(event(SearchMode.CLASSIC, "dune", 30, false, SearchOutcome.SUCCESS), T0.plusSeconds(10));
		send(event(SearchMode.CLASSIC, "dune", 10, true, SearchOutcome.SUCCESS), T0.plusSeconds(130));
		send(event(SearchMode.CLASSIC, "alien", 20, false, SearchOutcome.ERROR), T0.plusSeconds(140));
		send(event(SearchMode.AI, "un western", 900, false, SearchOutcome.SUCCESS), T0.plusSeconds(150));
		// Hors fenêtre de 3 minutes (T0 inclus, T0-1min exclu)
		send(event(SearchMode.CLASSIC, "ancienne", 5, false, SearchOutcome.SUCCESS), T0.minusSeconds(30));

		SearchStatsResponse stats = service(T0.plusSeconds(155)).snapshot(3);

		assertThat(stats.windowMinutes()).isEqualTo(3);
		assertThat(stats.timeline()).extracting(SearchStatsResponse.TimelinePoint::start)
			.containsExactly(T0, T0.plusSeconds(60), T0.plusSeconds(120));
		assertThat(stats.timeline()).extracting(SearchStatsResponse.TimelinePoint::classic).containsExactly(1L, 0L, 2L);
		assertThat(stats.timeline()).extracting(SearchStatsResponse.TimelinePoint::ai).containsExactly(0L, 0L, 1L);

		var classic = stats.modes().get(SearchMode.CLASSIC);
		assertThat(classic.searches()).isEqualTo(3);
		assertThat(classic.intents()).isEqualTo(3);
		assertThat(classic.errorRate()).isEqualTo(1.0 / 3);
		assertThat(classic.cacheHitRate()).isEqualTo(1.0 / 3);
		assertThat(classic.avgLatencyMs()).isEqualTo(20);
		assertThat(classic.maxLatencyMs()).isEqualTo(30);
		assertThat(classic.topQueries()).containsExactly(new Ranked("dune", 2), new Ranked("alien", 1));
		assertThat(stats.modes().get(SearchMode.AI).topQueries()).containsExactly(new Ranked("un western", 1));
	}

	@Test
	void emptyStoresGiveZeroStatsForEveryMode() {
		SearchStatsResponse stats = service(T0).snapshot(15);

		assertThat(stats.modes()).containsOnlyKeys(SearchMode.CLASSIC, SearchMode.AI);
		assertThat(stats.modes().get(SearchMode.AI).searches()).isZero();
		assertThat(stats.modes().get(SearchMode.AI).intents()).isZero();
		assertThat(stats.modes().get(SearchMode.AI).cacheHitRate()).isZero();
		assertThat(stats.timeline()).hasSize(15).allMatch(point -> point.classic() == 0 && point.ai() == 0);
	}

	@Test
	void intentMergeKeepsTheLatestQueryAndAddsRequests() {
		SearchIntent bat = SearchIntent.empty().plus(event(TAB, SearchMode.CLASSIC, "bat", T0));
		SearchIntent batman = SearchIntent.empty().plus(event(TAB, SearchMode.CLASSIC, "batman", T0.plusSeconds(1)));

		assertThat(bat.merge(batman).query()).isEqualTo("batman");
		assertThat(batman.merge(bat).query()).isEqualTo("batman");
		assertThat(bat.merge(batman).requests()).isEqualTo(2);
		// Un événement en retard ne remplace pas une requête plus récente
		assertThat(batman.plus(event(TAB, SearchMode.CLASSIC, "ba", T0)).query()).isEqualTo("batman");
	}

	private SearchStatsService service(Instant now) {
		SearchStatsStore store = new SearchStatsStore() {
			@Override
			public ReadOnlyWindowStore<String, SearchStatsBucket> windowStore() {
				return driver.getWindowStore(SearchStatsTopology.STORE);
			}

			@Override
			public ReadOnlySessionStore<String, SearchIntent> intentStore() {
				return driver.getSessionStore(SearchStatsTopology.INTENTS_STORE);
			}
		};
		return new SearchStatsService(store, Clock.fixed(now, ZoneOffset.UTC));
	}

	/** Une frappe par seconde, soit bien moins que la pause qui clôt une recherche. */
	private void typed(String tab, SearchMode mode, Instant start, String... queries) {
		for (int i = 0; i < queries.length; i++) {
			Instant at = start.plusSeconds(i);
			send(event(tab, mode, queries[i], at), at);
		}
	}

	private void send(SearchEvent event, Instant timestamp) {
		input.pipeInput(event.mode().name(), json(event), timestamp);
	}

	private String json(SearchEvent event) {
		return jsonMapper.writeValueAsString(event);
	}

	private static SearchEvent event(String tab, SearchMode mode, String query, Instant at) {
		return new SearchEvent(SearchEvent.SCHEMA_VERSION, UUID.randomUUID(), mode, query, 1,
				List.of(query + " (titre)"), 10, false, SearchOutcome.SUCCESS, at, tab);
	}

	private static SearchEvent event(SearchMode mode, String query, long latencyMs, boolean cacheHit,
			SearchOutcome outcome) {
		return new SearchEvent(SearchEvent.SCHEMA_VERSION, UUID.randomUUID(), mode, query, 0, List.of(), latencyMs,
				cacheHit, outcome, T0, null);
	}
}
