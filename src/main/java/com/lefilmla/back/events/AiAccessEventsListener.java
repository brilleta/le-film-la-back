package com.lefilmla.back.events;

import com.lefilmla.back.config.KafkaTopics;
import com.lefilmla.back.events.history.AiAccessHistoryService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Consomme {@code ai-access.events} pour alimenter l'historique. Les erreurs transitoires (base indisponible)
 * sont réessayées par le {@code CommonErrorHandler} ; un message invalide part en DLT sans nouvelle tentative.
 */
@Component
public class AiAccessEventsListener {

	public static final String GROUP_ID = "le-film-la-back.ai-access-history";

	private static final Logger log = LoggerFactory.getLogger(AiAccessEventsListener.class);

	private final JsonMapper jsonMapper;

	private final AiAccessHistoryService historyService;

	public AiAccessEventsListener(JsonMapper jsonMapper, AiAccessHistoryService historyService) {
		this.jsonMapper = jsonMapper;
		this.historyService = historyService;
	}

	@KafkaListener(topics = KafkaTopics.AI_ACCESS_EVENTS, groupId = GROUP_ID)
	public void onEvent(ConsumerRecord<String, String> record) {
		AiAccessEvent event = parse(record);
		boolean recorded = historyService.record(event);
		log.info("Historique des accès IA : événement {} ({}) pour {} {}", event.eventId(), event.type(), event.userId(),
				recorded ? "enregistré" : "doublon");
	}

	private AiAccessEvent parse(ConsumerRecord<String, String> record) {
		AiAccessEvent event;
		try {
			event = jsonMapper.readValue(record.value(), AiAccessEvent.class);
		}
		catch (JacksonException ex) {
			throw new InvalidEventException("Payload illisible (offset " + record.offset() + ")", ex);
		}
		if (event == null || event.eventId() == null || event.userId() == null || event.type() == null
				|| event.previousStatus() == null || event.newStatus() == null || event.occurredAt() == null) {
			throw new InvalidEventException("Événement incomplet (offset " + record.offset() + ")");
		}
		return event;
	}
}
