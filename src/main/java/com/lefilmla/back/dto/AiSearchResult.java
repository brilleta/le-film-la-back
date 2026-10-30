package com.lefilmla.back.dto;

public record AiSearchResult(long tmdbId, String type, String title, Integer year, String posterPath,
		Double voteAverage, String reason) {
}
