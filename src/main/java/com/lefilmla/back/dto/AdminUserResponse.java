package com.lefilmla.back.dto;

import com.lefilmla.back.entity.AiAccess;
import com.lefilmla.back.entity.Role;
import com.lefilmla.back.entity.User;
import java.time.Instant;
import java.util.UUID;

/** Ligne de la liste admin. À construire dans une transaction (le décideur est une relation paresseuse). */
public record AdminUserResponse(UUID id, String email, String username, Role role, AiAccess aiAccess,
		Instant createdAt, Instant aiAccessRequestedAt, Instant aiAccessDecidedAt, DecidedBy aiAccessDecidedBy) {

	public record DecidedBy(UUID id, String username) {
	}

	public static AdminUserResponse from(User user) {
		User admin = user.getAiAccessDecidedBy();
		return new AdminUserResponse(user.getId(), user.getEmail(), user.getUsername(), user.getRole(),
				user.getAiAccess(), user.getCreatedAt(), user.getAiAccessRequestedAt(), user.getAiAccessDecidedAt(),
				admin == null ? null : new DecidedBy(admin.getId(), admin.getUsername()));
	}
}
