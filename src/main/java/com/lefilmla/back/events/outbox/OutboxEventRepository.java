package com.lefilmla.back.events.outbox;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

	/**
	 * Prochains événements à publier, dans l'ordre d'insertion. {@code FOR UPDATE SKIP LOCKED} :
	 * deux relais concurrents ne se bloquent pas et ne publient pas deux fois la même ligne.
	 */
	@Query(value = "select * from outbox_events where published_at is null order by id limit :limit for update skip locked",
			nativeQuery = true)
	List<OutboxEvent> lockNextPending(@Param("limit") int limit);

	Optional<OutboxEvent> findByEventId(UUID eventId);

	long countByPublishedAtIsNull();

	/** Date d'écriture du plus ancien événement pas encore publié, {@code null} si l'outbox est à jour. */
	@Query("select min(e.createdAt) from OutboxEvent e where e.publishedAt is null")
	Instant oldestPendingCreatedAt();
}
