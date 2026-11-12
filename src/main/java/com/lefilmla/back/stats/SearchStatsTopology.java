package com.lefilmla.back.stats;

import com.lefilmla.back.config.KafkaTopics;
import com.lefilmla.back.events.search.SearchEvent;
import java.time.Duration;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.errors.StreamsUncaughtExceptionHandler.StreamThreadExceptionResponse;
import org.apache.kafka.streams.kstream.Consumed;
import org.apache.kafka.streams.kstream.Grouped;
import org.apache.kafka.streams.kstream.KStream;
import org.apache.kafka.streams.kstream.Materialized;
import org.apache.kafka.streams.kstream.TimeWindows;
import org.apache.kafka.streams.state.Stores;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafkaStreams;
import org.springframework.kafka.config.StreamsBuilderFactoryBeanConfigurer;
import tools.jackson.databind.json.JsonMapper;

/**
 * Statistiques temps réel des recherches : {@code search.events} agrégé par mode (la clé du topic) dans des
 * fenêtres d'une minute. La vue « N dernières minutes » est recomposée à la lecture (voir {@link SearchStatsService}).
 * <ul>
 * <li>{@code groupByKey} sur la clé d'origine : aucun topic de repartition.</li>
 * <li>State store en mémoire sans changelog : aucun topic interne (plan Aiven limité en topics), pas de RocksDB
 * (bibliothèque native incompatible avec l'image alpine). Contrepartie assumée : les stats repartent de zéro à
 * chaque redémarrage.</li>
 * </ul>
 */
@Configuration
@EnableKafkaStreams
public class SearchStatsTopology {

	public static final String STORE = "search-stats";

	public static final Duration BUCKET = Duration.ofMinutes(1);

	/** Profondeur maximale consultable : une heure de fenêtres. */
	public static final Duration RETENTION = Duration.ofHours(1);

	/** Délai accordé aux événements en retard avant de fermer une fenêtre. */
	public static final Duration GRACE = Duration.ofSeconds(10);

	private static final Logger log = LoggerFactory.getLogger(SearchStatsTopology.class);

	@Bean
	KStream<String, SearchEvent> searchStatsStream(StreamsBuilder builder, JsonMapper jsonMapper) {
		return build(builder, jsonMapper);
	}

	/** Un événement illisible ne doit pas arrêter le flux : on remplace le thread plutôt que d'arrêter le client. */
	@Bean
	StreamsBuilderFactoryBeanConfigurer searchStatsExceptionHandler() {
		return factoryBean -> factoryBean.setStreamsUncaughtExceptionHandler(ex -> {
			log.error("Erreur dans le flux des statistiques de recherche, thread remplacé", ex);
			return StreamThreadExceptionResponse.REPLACE_THREAD;
		});
	}

	static KStream<String, SearchEvent> build(StreamsBuilder builder, JsonMapper jsonMapper) {
		var eventSerde = new JsonSerde<>(jsonMapper, SearchEvent.class);
		KStream<String, SearchEvent> events = builder
			.stream(KafkaTopics.SEARCH_EVENTS, Consumed.with(Serdes.String(), Serdes.String()))
			.mapValues(json -> parse(jsonMapper, json))
			// La clé doit rester le mode : un message mal formé ou mal clé est ignoré
			.filter((key, event) -> event != null && event.mode() != null && event.mode().name().equals(key));

		events.groupByKey(Grouped.with(Serdes.String(), eventSerde))
			.windowedBy(TimeWindows.ofSizeAndGrace(BUCKET, GRACE))
			.aggregate(SearchStatsBucket::empty, (mode, event, bucket) -> bucket.plus(event),
					Materialized.<String, SearchStatsBucket>as(Stores.inMemoryWindowStore(STORE, RETENTION, BUCKET, false))
						.withKeySerde(Serdes.String())
						.withValueSerde(new JsonSerde<>(jsonMapper, SearchStatsBucket.class))
						.withLoggingDisabled());
		return events;
	}

	private static SearchEvent parse(JsonMapper jsonMapper, String json) {
		try {
			return json == null ? null : jsonMapper.readValue(json, SearchEvent.class);
		}
		catch (RuntimeException ex) {
			log.debug("Événement de recherche illisible ignoré : {}", ex.getMessage());
			return null;
		}
	}
}
