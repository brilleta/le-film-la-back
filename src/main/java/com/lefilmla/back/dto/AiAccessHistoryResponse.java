package com.lefilmla.back.dto;

import com.lefilmla.back.entity.AiAccess;
import com.lefilmla.back.events.AiAccessEventType;
import com.lefilmla.back.events.history.AiAccessHistoryEntry;
import java.time.Instant;
import java.util.UUID;

/** Entrée de l'historique d'accès IA d'un utilisateur, de la plus récente à la plus ancienne. */
public record AiAccessHistoryResponse(UUID eventId, AiAccessEventType type, AiAccess previousStatus,
		AiAccess newStatus, AdminUserResponse.DecidedBy decidedBy, Instant occurredAt) {

	public static AiAccessHistoryResponse from(AiAccessHistoryEntry entry) {
		var decidedBy = entry.getDecidedById() == null ? null
				: new AdminUserResponse.DecidedBy(entry.getDecidedById(), entry.getDecidedByUsername());
		return new AiAccessHistoryResponse(entry.getEventId(), entry.getEventType(), entry.getPreviousStatus(),
				entry.getNewStatus(), decidedBy, entry.getOccurredAt());
	}
}
