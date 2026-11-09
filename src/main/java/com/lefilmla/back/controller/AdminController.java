package com.lefilmla.back.controller;

import com.lefilmla.back.dto.AdminUserResponse;
import com.lefilmla.back.dto.PageResponse;
import com.lefilmla.back.entity.AiAccess;
import com.lefilmla.back.security.AuthenticatedUser;
import com.lefilmla.back.service.AdminUserService;
import com.lefilmla.back.service.AiAccessService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Réservé au rôle ADMIN (voir SecurityConfig). */
@RestController
@RequestMapping("/api/admin/users")
public class AdminController {

	private final AdminUserService adminUserService;

	private final AiAccessService aiAccessService;

	public AdminController(AdminUserService adminUserService, AiAccessService aiAccessService) {
		this.adminUserService = adminUserService;
		this.aiAccessService = aiAccessService;
	}

	@GetMapping
	PageResponse<AdminUserResponse> list(@RequestParam(required = false) AiAccess aiAccess,
			@RequestParam(required = false) @Size(max = 100) String q,
			@RequestParam(defaultValue = "0") @Min(0) int page,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
		return adminUserService.list(aiAccess, q, page, size);
	}

	@PostMapping("/{id}/ai-access/grant")
	AdminUserResponse grant(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable UUID id) {
		return aiAccessService.grant(admin, id);
	}

	@PostMapping("/{id}/ai-access/reject")
	AdminUserResponse reject(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable UUID id) {
		return aiAccessService.reject(admin, id);
	}

	@PostMapping("/{id}/ai-access/revoke")
	AdminUserResponse revoke(@AuthenticationPrincipal AuthenticatedUser admin, @PathVariable UUID id) {
		return aiAccessService.revoke(admin, id);
	}
}
