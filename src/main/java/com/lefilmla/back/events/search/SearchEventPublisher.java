package com.lefilmla.back.events.search;

import com.lefilmla.back.config.KafkaTopics;
import com.lefilmla.back.events.outbox.OutboxPublisher;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Envoi « fire and forget » des événements de recherche, volontairement sans outbox : ce sont des mesures,
 * pas des faits métier, et en perdre quelques-unes est acceptable. En contrepartie, la recherche ne doit jamais
 * attendre Kafka : l'envoi passe par un thread dédié (le producteur peut bloquer jusqu'à {@code max.block.ms}
 * si le broker est absent) et une file bornée qui abandonne les événements quand elle est pleine.
 */
@Component
public class SearchEventPublisher implements DisposableBean {

	public static final String EVENT_TYPE = "SEARCH";

	private static final Logger log = LoggerFactory.getLogger(SearchEventPublisher.class);

	private final KafkaTemplate<String, String> kafkaTemplate;

	private final JsonMapper jsonMapper;

	private final ThreadPoolExecutor executor;

	private final ContextSnapshotFactory contextSnapshots = ContextSnapshotFactory.builder().build();

	/** Mesures abandonnées : file pleine (Kafka trop lent ou absent) ou envoi en échec. */
	private final Counter droppedQueueFull;

	private final Counter droppedSendFailed;

	public SearchEventPublisher(KafkaTemplate<String, String> kafkaTemplate, JsonMapper jsonMapper,
			@Value("${search-events.queue-capacity:1000}") int queueCapacity, MeterRegistry meters) {
		this.kafkaTemplate = kafkaTemplate;
		this.jsonMapper = jsonMapper;
		this.droppedQueueFull = droppedCounter(meters, "queue_full");
		this.droppedSendFailed = droppedCounter(meters, "send_failed");
		this.executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queueCapacity),
				Thread.ofPlatform().name("search-events").daemon().factory(),
				(task, pool) -> {
					droppedQueueFull.increment();
					log.debug("File des événements de recherche pleine : événement abandonné");
				});
	}

	public void publish(SearchEvent event) {
		try {
			// Le thread dédié ne voit pas la trace de la requête : on la capture ici et on la restaure là-bas,
			// pour que l'envoi Kafka apparaisse dans la trace de la recherche
			executor.execute(contextSnapshots.captureAll().wrap(() -> send(event)));
		}
		catch (RejectedExecutionException ex) {
			// Arrêt en cours : la mesure est perdue, la recherche continue
		}
	}

	private void send(SearchEvent event) {
		try {
			var record = new ProducerRecord<>(KafkaTopics.SEARCH_EVENTS, event.mode().name(),
					jsonMapper.writeValueAsString(event));
			record.headers()
				.add(OutboxPublisher.HEADER_EVENT_ID, event.eventId().toString().getBytes(StandardCharsets.UTF_8))
				.add(OutboxPublisher.HEADER_EVENT_TYPE, EVENT_TYPE.getBytes(StandardCharsets.UTF_8));
			kafkaTemplate.send(record).whenComplete((result, ex) -> {
				if (ex != null) {
					droppedSendFailed.increment();
					log.debug("Événement de recherche non publié : {}", ex.getMessage());
				}
			});
		}
		catch (RuntimeException ex) {
			droppedSendFailed.increment();
			log.debug("Événement de recherche non publié : {}", ex.getMessage());
		}
	}

	private static Counter droppedCounter(MeterRegistry meters, String reason) {
		return Counter.builder("search.events.dropped")
			.description("Événements de recherche abandonnés sans atteindre Kafka")
			.tag("reason", reason)
			.register(meters);
	}

	@Override
	public void destroy() throws InterruptedException {
		executor.shutdown();
		executor.awaitTermination(2, TimeUnit.SECONDS);
	}
}
