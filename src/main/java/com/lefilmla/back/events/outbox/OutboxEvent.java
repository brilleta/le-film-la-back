package com.lefilmla.back.events.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Ligne de l'outbox : un message Kafka en attente de publication (ou déjà publié, pour l'audit). */
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "event_id", nullable = false, updatable = false)
	private UUID eventId;

	@Column(nullable = false, updatable = false)
	private String topic;

	@Column(name = "message_key", nullable = false, updatable = false)
	private String messageKey;

	@Column(name = "event_type", nullable = false, updatable = false)
	private String eventType;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, updatable = false)
	private String payload;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "published_at")
	private Instant publishedAt;

	@Column(nullable = false)
	private int attempts;

	@Column(name = "last_error")
	private String lastError;

	protected OutboxEvent() {
		// requis par JPA
	}

	public OutboxEvent(UUID eventId, String topic, String messageKey, String eventType, String payload,
			Instant createdAt) {
		this.eventId = eventId;
		this.topic = topic;
		this.messageKey = messageKey;
		this.eventType = eventType;
		this.payload = payload;
		this.createdAt = createdAt;
	}

	public Long getId() {
		return id;
	}

	public UUID getEventId() {
		return eventId;
	}

	public String getTopic() {
		return topic;
	}

	public String getMessageKey() {
		return messageKey;
	}

	public String getEventType() {
		return eventType;
	}

	public String getPayload() {
		return payload;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getPublishedAt() {
		return publishedAt;
	}

	public int getAttempts() {
		return attempts;
	}

	public String getLastError() {
		return lastError;
	}

	public void markPublished(Instant now) {
		this.publishedAt = now;
		this.attempts++;
		this.lastError = null;
	}

	public void markFailed(String error) {
		this.attempts++;
		this.lastError = error;
	}
}
