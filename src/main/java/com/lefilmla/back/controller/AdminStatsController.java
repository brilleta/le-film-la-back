package com.lefilmla.back.controller;

import com.lefilmla.back.dto.SearchStatsResponse;
import com.lefilmla.back.stats.SearchStatsBroadcaster;
import com.lefilmla.back.stats.SearchStatsService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/admin/stats")
public class AdminStatsController {

	private final SearchStatsService searchStatsService;

	private final SearchStatsBroadcaster searchStatsBroadcaster;

	public AdminStatsController(SearchStatsService searchStatsService,
			SearchStatsBroadcaster searchStatsBroadcaster) {
		this.searchStatsService = searchStatsService;
		this.searchStatsBroadcaster = searchStatsBroadcaster;
	}

	@GetMapping
	public SearchStatsResponse snapshot(@RequestParam(defaultValue = "15") @Min(1) @Max(60) int minutes) {
		return searchStatsService.snapshot(minutes);
	}

	@GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter stream(@RequestParam(defaultValue = "15") @Min(1) @Max(60) int minutes,
			HttpServletResponse response) {
		// nginx (proxy du front) ne doit pas mettre le flux en tampon
		response.setHeader("X-Accel-Buffering", "no");
		response.setHeader("Cache-Control", "no-cache");
		return searchStatsBroadcaster.subscribe(minutes);
	}
}
