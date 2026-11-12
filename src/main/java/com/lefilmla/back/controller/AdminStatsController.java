package com.lefilmla.back.controller;

import com.lefilmla.back.dto.SearchStatsResponse;
import com.lefilmla.back.stats.SearchStatsService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/stats")
public class AdminStatsController {

	private final SearchStatsService searchStatsService;

	public AdminStatsController(SearchStatsService searchStatsService) {
		this.searchStatsService = searchStatsService;
	}

	@GetMapping
	public SearchStatsResponse snapshot(@RequestParam(defaultValue = "15") @Min(1) @Max(60) int minutes) {
		return searchStatsService.snapshot(minutes);
	}
}
