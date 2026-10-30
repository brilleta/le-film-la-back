package com.lefilmla.back.dto;

public enum TitleType {
	MOVIE("movie"),
	TV("tv");

	private final String tmdbName;

	TitleType(String tmdbName) {
		this.tmdbName = tmdbName;
	}

	public String tmdbName() {
		return tmdbName;
	}

	public static TitleType fromTmdbName(String name) {
		for (TitleType type : values()) {
			if (type.tmdbName.equals(name)) {
				return type;
			}
		}
		throw new IllegalArgumentException("Type inconnu : " + name);
	}
}
