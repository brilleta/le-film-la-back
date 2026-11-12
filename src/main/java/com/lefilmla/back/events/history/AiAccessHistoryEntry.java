package com.lefilmla.back.events.history;

import com.lefilmla.back.entity.AiAccess;
import com.lefilmla.back.events.AiAccessEventType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Ligne d'historique, en lecture seule : l'insertion passe par {@link AiAccessHistoryRepository#insertIfAbsent}. */
@Entity
@Table(name = "ai_access_history")
public class AiAccessHistoryEntry {

	@Id
	private Long id;

	@Column(name = "event_id", nullable = false)
	private UUID eventId;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	@Enumerated(EnumType.STRING)
	@Column(name = "event_type", nullable = false)
	private AiAccessEventType eventType;

	@Enumerated(EnumType.STRING)
	@Column(name = "previous_status", nullable = false)
	private AiAccess previousStatus;

	@Enumerated(EnumType.STRING)
	@Column(name = "new_status", nullable = false)
	private AiAccess newStatus;

	@Column(name = "decided_by_id")
	private UUID decidedById;

	@Column(name = "decided_by_username")
	private String decidedByUsername;

	@Column(name = "occurred_at", nullable = false)
	private Instant occurredAt;

	@Column(name = "recorded_at", nullable = false)
	private Instant recordedAt;

	protected AiAccessHistoryEntry() {
		// requis par JPA
	}

	public Long getId() {
		return id;
	}

	public UUID getEventId() {
		return eventId;
	}

	public UUID getUserId() {
		return userId;
	}

	public AiAccessEventType getEventType() {
		return eventType;
	}

	public AiAccess getPreviousStatus() {
		return previousStatus;
	}

	public AiAccess getNewStatus() {
		return newStatus;
	}

	public UUID getDecidedById() {
		return decidedById;
	}

	public String getDecidedByUsername() {
		return decidedByUsername;
	}

	public Instant getOccurredAt() {
		return occurredAt;
	}

	public Instant getRecordedAt() {
		return recordedAt;
	}
}
