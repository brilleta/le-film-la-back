package com.lefilmla.back.controller;

import com.lefilmla.back.dto.TitleType;
import com.lefilmla.back.service.MovieService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
@RequestMapping("/api")
public class MovieController {

	private final MovieService movieService;

	public MovieController(MovieService movieService) {
		this.movieService = movieService;
	}

	@GetMapping("/movies/search")
	public JsonNode search(@RequestParam @NotBlank @Size(max = 200) String q) {
		return movieService.search(q);
	}

	@GetMapping("/movies/{type}/{id}")
	public JsonNode details(@PathVariable TitleType type, @PathVariable long id) {
		return movieService.details(type, id);
	}

	@GetMapping("/trending")
	public JsonNode trending() {
		return movieService.trending();
	}
}
