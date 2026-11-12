package com.lefilmla.back.stats;

import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StoreQueryParameters;
import org.apache.kafka.streams.errors.InvalidStateStoreException;
import org.apache.kafka.streams.state.QueryableStoreType;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlySessionStore;
import org.apache.kafka.streams.state.ReadOnlyWindowStore;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.stereotype.Component;

/**
 * Interactive queries sur l'instance locale. Une seule instance tourne (Render) : elle possède toutes les partitions,
 * les stores composites couvrent donc tout le topic. Avec plusieurs instances, il faudrait interroger les autres
 * ({@code KafkaStreams#queryMetadataForKey}) ou passer par une table partagée.
 */
@Component
class KafkaStreamsSearchStatsStore implements SearchStatsStore {

	private final StreamsBuilderFactoryBean factoryBean;

	KafkaStreamsSearchStatsStore(StreamsBuilderFactoryBean factoryBean) {
		this.factoryBean = factoryBean;
	}

	@Override
	public ReadOnlyWindowStore<String, SearchStatsBucket> windowStore() {
		return store(SearchStatsTopology.STORE, QueryableStoreTypes.windowStore());
	}

	@Override
	public ReadOnlySessionStore<String, SearchIntent> intentStore() {
		return store(SearchStatsTopology.INTENTS_STORE, QueryableStoreTypes.sessionStore());
	}

	private <T> T store(String name, QueryableStoreType<T> type) {
		KafkaStreams streams = factoryBean.getKafkaStreams();
		if (streams == null || streams.state() != KafkaStreams.State.RUNNING) {
			throw new StatsUnavailableException();
		}
		try {
			return streams.store(StoreQueryParameters.fromNameAndType(name, type));
		}
		catch (InvalidStateStoreException ex) {
			throw new StatsUnavailableException();
		}
	}
}
