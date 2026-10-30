package com.lefilmla.back.service;

import com.lefilmla.back.client.TmdbClient;
import com.lefilmla.back.config.CacheConfig;
import com.lefilmla.back.dto.TitleType;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@Service
public class MovieService {

	private final TmdbClient tmdbClient;

	public MovieService(TmdbClient tmdbClient) {
		this.tmdbClient = tmdbClient;
	}

	@Cacheable(cacheNames = CacheConfig.TMDB, key = "'search:' + #query")
	public JsonNode search(String query) {
		return withoutPeople(tmdbClient.searchMulti(query));
	}

	@Cacheable(cacheNames = CacheConfig.TMDB, key = "'details:' + #type + ':' + #id")
	public JsonNode details(TitleType type, long id) {
		return tmdbClient.details(type, id);
	}

	@Cacheable(cacheNames = CacheConfig.TMDB, key = "'trending'")
	public JsonNode trending() {
		return withoutPeople(tmdbClient.trending());
	}

	private JsonNode withoutPeople(JsonNode response) {
		ArrayNode kept = JsonNodeFactory.instance.arrayNode();
		for (JsonNode result : response.path("results")) {
			if (!"person".equals(result.path("media_type").asString(""))) {
				kept.add(result);
			}
		}
		ObjectNode filtered = (ObjectNode) response.deepCopy();
		filtered.set("results", kept);
		return filtered;
	}
}
