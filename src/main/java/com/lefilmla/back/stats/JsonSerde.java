package com.lefilmla.back.stats;

import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;
import tools.jackson.databind.json.JsonMapper;

/** Serde JSON minimal pour Kafka Streams, adossé au {@link JsonMapper} de l'application. */
final class JsonSerde<T> implements Serde<T> {

	private final JsonMapper jsonMapper;

	private final Class<T> type;

	JsonSerde(JsonMapper jsonMapper, Class<T> type) {
		this.jsonMapper = jsonMapper;
		this.type = type;
	}

	@Override
	public Serializer<T> serializer() {
		return (topic, data) -> data == null ? null : jsonMapper.writeValueAsBytes(data);
	}

	@Override
	public Deserializer<T> deserializer() {
		return (topic, bytes) -> bytes == null ? null : jsonMapper.readValue(bytes, type);
	}
}
