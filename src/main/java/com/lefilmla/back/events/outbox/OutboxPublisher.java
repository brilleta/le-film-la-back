package com.lefilmla.back.events.outbox;

import com.lefilmla.back.observability.TraceParents;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publie un lot d'événements en attente vers Kafka et marque le résultat en base.
 * Envoi séquentiel et arrêt au premier échec : l'ordre d'insertion est respecté, même entre deux passages.
 * Livraison « au moins une fois » : un envoi réussi dont l'acquittement se perd sera rejoué, le consommateur
 * dédoublonne sur {@code eventId}.
 */
@Component
public class OutboxPublisher {

	public static final String HEADER_EVENT_ID = "event-id";

	public static final String HEADER_EVENT_TYPE = "event-type";

	/** Nom du span qui relie la requête d'origine à l'envoi Kafka, publié plus tard par le relais. */
	public static final String PUBLISH_SPAN = "outbox publish";

	private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

	private final OutboxEventRepository repository;

	private final KafkaTemplate<String, String> kafkaTemplate;

	private final Clock clock;

	private final int batchSize;

	private final Duration sendTimeout;

	private final TraceParents traceParents;

	public OutboxPublisher(OutboxEventRepository repository, KafkaTemplate<String, String> kafkaTemplate, Clock clock,
			@Value("${outbox.batch-size:100}") int batchSize,
			@Value("${outbox.send-timeout:10s}") Duration sendTimeout, TraceParents traceParents) {
		this.traceParents = traceParents;
		this.repository = repository;
		this.kafkaTemplate = kafkaTemplate;
		this.clock = clock;
		this.batchSize = batchSize;
		this.sendTimeout = sendTimeout;
	}

	/** @return le nombre d'événements publiés ; moins que la taille du lot signifie qu'il n'y a plus rien (ou un échec) */
	@Transactional
	public int publishBatch() {
		List<OutboxEvent> pending = repository.lockNextPending(batchSize);
		int published = 0;
		for (OutboxEvent event : pending) {
			// Reprend la trace de la requête qui a écrit l'événement : l'envoi Kafka en devient un descendant
			if (!traceParents.inSpan(PUBLISH_SPAN, event.getTraceparent(), () -> send(event))) {
				break;
			}
			published++;
		}
		return published;
	}

	public int batchSize() {
		return batchSize;
	}

	private boolean send(OutboxEvent event) {
		var record = new ProducerRecord<>(event.getTopic(), event.getMessageKey(), event.getPayload());
		record.headers()
			.add(HEADER_EVENT_ID, event.getEventId().toString().getBytes(StandardCharsets.UTF_8))
			.add(HEADER_EVENT_TYPE, event.getEventType().getBytes(StandardCharsets.UTF_8));
		try {
			kafkaTemplate.send(record).get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
			event.markPublished(clock.instant());
			return true;
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			event.markFailed("interrompu");
			return false;
		}
		catch (ExecutionException | TimeoutException ex) {
			Throwable cause = ex instanceof ExecutionException && ex.getCause() != null ? ex.getCause() : ex;
			event.markFailed(cause.getClass().getSimpleName() + ": " + cause.getMessage());
			log.warn("Publication Kafka échouée (événement {}, tentative {}) : {}", event.getEventId(),
					event.getAttempts(), cause.getMessage());
			return false;
		}
	}
}
