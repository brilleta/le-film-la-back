package com.lefilmla.back.controller;

import com.lefilmla.back.dto.UserResponse;
import com.lefilmla.back.security.AuthenticatedUser;
import com.lefilmla.back.service.AiAccessService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai")
public class AiAccessController {

	private final AiAccessService aiAccessService;

	public AiAccessController(AiAccessService aiAccessService) {
		this.aiAccessService = aiAccessService;
	}

	@PostMapping("/access-request")
	UserResponse requestAccess(@AuthenticationPrincipal AuthenticatedUser principal) {
		return aiAccessService.request(principal);
	}
}
