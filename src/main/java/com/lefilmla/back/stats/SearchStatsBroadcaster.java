package com.lefilmla.back.stats;

import com.lefilmla.back.dto.ErrorResponse;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Diffusion des statistiques en Server-Sent Events : un snapshot à l'abonnement, puis à chaque tick.
 * Un snapshot est calculé une fois par tick et par profondeur demandée, quel que soit le nombre d'abonnés.
 * Le tick régulier sert aussi de heartbeat : nginx et le proxy Render ne coupent pas une connexion qui parle.
 * Le navigateur ({@code EventSource}) se reconnecte seul après le timeout ou une coupure.
 */
@Component
public class SearchStatsBroadcaster {

	public static final String EVENT_STATS = "stats";

	public static final String EVENT_UNAVAILABLE = "unavailable";

	private static final long RECONNECT_MS = 5_000;

	private final SearchStatsService service;

	private final Duration timeout;

	private final Set<Subscriber> subscribers = ConcurrentHashMap.newKeySet();

	public SearchStatsBroadcaster(SearchStatsService service,
			@Value("${stats.stream.timeout:15m}") Duration timeout) {
		this.service = service;
		this.timeout = timeout;
	}

	public SseEmitter subscribe(int minutes) {
		SseEmitter emitter = new SseEmitter(timeout.toMillis());
		Subscriber subscriber = new Subscriber(emitter, minutes);
		emitter.onCompletion(() -> subscribers.remove(subscriber));
		emitter.onTimeout(() -> {
			subscribers.remove(subscriber);
			emitter.complete();
		});
		emitter.onError(ex -> subscribers.remove(subscriber));
		subscribers.add(subscriber);
		send(subscriber, payload(minutes), true);
		return emitter;
	}

	@Scheduled(fixedRateString = "${stats.stream.interval:2s}")
	public void broadcast() {
		if (subscribers.isEmpty()) {
			return;
		}
		Map<Integer, Payload> byMinutes = new HashMap<>();
		for (Subscriber subscriber : subscribers) {
			send(subscriber, byMinutes.computeIfAbsent(subscriber.minutes(), this::payload), false);
		}
	}

	public int subscriberCount() {
		return subscribers.size();
	}

	/** Ferme les flux à l'arrêt : sinon Tomcat attendrait leur timeout. */
	@PreDestroy
	public void completeAll() {
		subscribers.forEach(subscriber -> subscriber.emitter().complete());
		subscribers.clear();
	}

	Payload payload(int minutes) {
		try {
			return new Payload(EVENT_STATS, service.snapshot(minutes));
		}
		catch (StatsUnavailableException ex) {
			return new Payload(EVENT_UNAVAILABLE, new ErrorResponse("STATS_UNAVAILABLE", ex.getMessage()));
		}
	}

	private void send(Subscriber subscriber, Payload payload, boolean first) {
		var event = SseEmitter.event().name(payload.name()).data(payload.data(), MediaType.APPLICATION_JSON);
		if (first) {
			event.reconnectTime(RECONNECT_MS);
		}
		try {
			subscriber.emitter().send(event);
		}
		catch (IOException | IllegalStateException ex) {
			// Client parti : l'émetteur est déjà inutilisable
			subscribers.remove(subscriber);
		}
	}

	record Payload(String name, Object data) {
	}

	private record Subscriber(SseEmitter emitter, int minutes) {
	}
}
