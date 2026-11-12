package com.lefilmla.back.events.outbox;

import java.util.concurrent.locks.ReentrantLock;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Déclenche la publication : juste après chaque commit (latence minimale) et périodiquement (filet de sécurité
 * après un échec ou un redémarrage). Un verrou garantit un seul relais à la fois dans l'instance, ce qui préserve
 * l'ordre de publication ; avec plusieurs instances, {@code SKIP LOCKED} évite les doublons mais l'ordre entre
 * instances n'est plus garanti.
 */
@Component
public class OutboxRelay {

	private final OutboxPublisher publisher;

	private final ReentrantLock running = new ReentrantLock();

	public OutboxRelay(OutboxPublisher publisher) {
		this.publisher = publisher;
	}

	@Async
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onAppended(Outbox.OutboxAppended appended) {
		relay();
	}

	@Scheduled(fixedDelayString = "${outbox.poll-interval:2s}", initialDelayString = "${outbox.poll-interval:2s}")
	public void poll() {
		relay();
	}

	/** Vide la file tant que des lots complets sont publiés ; sans effet si un relais est déjà en cours. */
	public void relay() {
		if (!running.tryLock()) {
			return;
		}
		try {
			while (publisher.publishBatch() == publisher.batchSize()) {
				// un lot plein : il en reste peut-être
			}
		}
		finally {
			running.unlock();
		}
	}
}
