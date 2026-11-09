package com.lefilmla.back.dto;

import com.lefilmla.back.entity.AiAccess;
import com.lefilmla.back.entity.Role;
import com.lefilmla.back.entity.User;
import java.util.UUID;

public record UserResponse(UUID id, String email, String username, Role role, AiAccess aiAccess) {

	public static UserResponse from(User user) {
		return new UserResponse(user.getId(), user.getEmail(), user.getUsername(), user.getRole(), user.getAiAccess());
	}
}
