package com.lefilmla.back.controller;

import com.lefilmla.back.dto.AiSearchRequest;
import com.lefilmla.back.dto.AiSearchResponse;
import com.lefilmla.back.service.AiSearchService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ai")
public class AiSearchController {

	private final AiSearchService aiSearchService;

	public AiSearchController(AiSearchService aiSearchService) {
		this.aiSearchService = aiSearchService;
	}

	@PostMapping("/search")
	public AiSearchResponse search(@Valid @RequestBody AiSearchRequest request) {
		return aiSearchService.search(request.description());
	}
}
