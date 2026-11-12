package com.lefilmla.back.events.history;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiAccessHistoryRepository extends JpaRepository<AiAccessHistoryEntry, Long> {

	/**
	 * Insertion idempotente : un événement déjà enregistré (même {@code event_id}) est ignoré sans erreur,
	 * y compris si deux consommateurs le traitent en même temps.
	 * @return 1 si la ligne a été insérée, 0 si elle existait déjà
	 */
	@Modifying
	@Query(value = """
			insert into ai_access_history (event_id, user_id, event_type, previous_status, new_status,
			                               decided_by_id, decided_by_username, occurred_at, recorded_at)
			values (:eventId, :userId, :eventType, :previousStatus, :newStatus,
			        :decidedById, :decidedByUsername, :occurredAt, :recordedAt)
			on conflict (event_id) do nothing
			""", nativeQuery = true)
	int insertIfAbsent(@Param("eventId") UUID eventId, @Param("userId") UUID userId,
			@Param("eventType") String eventType, @Param("previousStatus") String previousStatus,
			@Param("newStatus") String newStatus, @Param("decidedById") UUID decidedById,
			@Param("decidedByUsername") String decidedByUsername, @Param("occurredAt") Instant occurredAt,
			@Param("recordedAt") Instant recordedAt);

	List<AiAccessHistoryEntry> findByUserIdOrderByOccurredAtDescIdDesc(UUID userId);
}
