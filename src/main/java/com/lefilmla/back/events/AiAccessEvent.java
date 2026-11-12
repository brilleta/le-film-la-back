package com.lefilmla.back.events;

import com.lefilmla.back.entity.AiAccess;
import com.lefilmla.back.entity.User;
import java.time.Instant;
import java.util.UUID;

/**
 * Événement publié sur {@code ai-access.events} à chaque transition d'accès à la recherche IA.
 * {@code schemaVersion} permet aux consommateurs de gérer les évolutions du contrat.
 */
public record AiAccessEvent(int schemaVersion, UUID eventId, AiAccessEventType type, UUID userId,
		AiAccess previousStatus, AiAccess newStatus, Actor decidedBy, Instant occurredAt) {

	public static final int SCHEMA_VERSION = 1;

	/** Admin à l'origine d'une décision ; null pour une demande de l'utilisateur lui-même. */
	public record Actor(UUID id, String username) {

		static Actor of(User admin) {
			return admin == null ? null : new Actor(admin.getId(), admin.getUsername());
		}
	}

	public static AiAccessEvent of(AiAccessEventType type, User user, AiAccess previousStatus, User decidedBy,
			Instant occurredAt) {
		return new AiAccessEvent(SCHEMA_VERSION, UUID.randomUUID(), type, user.getId(), previousStatus,
				user.getAiAccess(), Actor.of(decidedBy), occurredAt);
	}
}
