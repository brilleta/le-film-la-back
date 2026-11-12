package com.lefilmla.back.stats;

import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StoreQueryParameters;
import org.apache.kafka.streams.errors.InvalidStateStoreException;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyWindowStore;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.stereotype.Component;

/**
 * Interactive query sur l'instance locale. Une seule instance tourne (Render) : elle possède toutes les partitions,
 * le store composite couvre donc tout le topic. Avec plusieurs instances, il faudrait interroger les autres
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
		KafkaStreams streams = factoryBean.getKafkaStreams();
		if (streams == null || streams.state() != KafkaStreams.State.RUNNING) {
			throw new StatsUnavailableException();
		}
		try {
			return streams.store(StoreQueryParameters.fromNameAndType(SearchStatsTopology.STORE,
					QueryableStoreTypes.windowStore()));
		}
		catch (InvalidStateStoreException ex) {
			throw new StatsUnavailableException();
		}
	}
}
