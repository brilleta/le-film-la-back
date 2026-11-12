package com.lefilmla.back.events.outbox;

import com.lefilmla.back.observability.TraceParents;
import java.time.Instant;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Écriture dans l'outbox. Exige une transaction en cours ({@code MANDATORY}) : l'événement est validé ou annulé
 * avec la donnée métier, jamais l'un sans l'autre.
 */
@Component
public class Outbox {

	private final OutboxEventRepository repository;

	private final JsonMapper jsonMapper;

	private final ApplicationEventPublisher applicationEvents;

	private final TraceParents traceParents;

	public Outbox(OutboxEventRepository repository, JsonMapper jsonMapper,
			ApplicationEventPublisher applicationEvents, TraceParents traceParents) {
		this.repository = repository;
		this.jsonMapper = jsonMapper;
		this.applicationEvents = applicationEvents;
		this.traceParents = traceParents;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void append(String topic, String key, UUID eventId, String eventType, Object payload, Instant now) {
		String json = jsonMapper.writeValueAsString(payload);
		repository.save(new OutboxEvent(eventId, topic, key, eventType, json, now, traceParents.current()));
		// Réveille le relais dès le commit, sans attendre le prochain passage planifié
		applicationEvents.publishEvent(new OutboxAppended(eventId));
	}

	/** Signal interne émis à chaque ajout ; consommé après commit par {@link OutboxRelay}. */
	public record OutboxAppended(UUID eventId) {
	}
}
