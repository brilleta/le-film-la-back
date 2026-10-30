package com.lefilmla.back.client;

import com.lefilmla.back.config.CacheConfig;
import com.lefilmla.back.config.TmdbProperties;
import com.lefilmla.back.dto.TitleType;
import com.lefilmla.back.exception.TmdbNotFoundException;
import com.lefilmla.back.exception.UpstreamException;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;
import tools.jackson.databind.JsonNode;

@Component
public class TmdbClient {

	private final RestClient restClient;

	private final String language;

	public TmdbClient(RestClient tmdbRestClient, TmdbProperties properties) {
		this.restClient = tmdbRestClient;
		this.language = properties.language();
	}

	public JsonNode searchMulti(String query) {
		return get(uri -> uri.path("/search/multi")
			.queryParam("language", language)
			.queryParam("query", "{query}")
			.build(query));
	}

	public JsonNode details(TitleType type, long id) {
		return get(uri -> uri.path("/{type}/{id}")
			.queryParam("language", language)
			.queryParam("append_to_response", "credits,videos")
			.build(type.tmdbName(), id));
	}

	public JsonNode trending() {
		return get(uri -> uri.path("/trending/all/week").queryParam("language", language).build());
	}

	/** Premier résultat TMDB pour ce titre et cette année, s'il existe. */
	@Cacheable(cacheNames = CacheConfig.TMDB, key = "'find:' + #type + ':' + #year + ':' + #title")
	public Optional<JsonNode> findTitle(TitleType type, String title, int year) {
		String path = type == TitleType.MOVIE ? "/search/movie" : "/search/tv";
		String yearParam = type == TitleType.MOVIE ? "year" : "first_air_date_year";
		JsonNode response = get(uri -> uri.path(path)
			.queryParam("language", language)
			.queryParam("query", "{query}")
			.queryParam(yearParam, year)
			.build(title));
		return response.path("results").isEmpty() ? Optional.empty() : Optional.of(response.path("results").get(0));
	}

	private JsonNode get(Function<UriBuilder, java.net.URI> uri) {
		try {
			return restClient.get().uri(uri).retrieve().body(JsonNode.class);
		}
		catch (RestClientResponseException ex) {
			if (ex.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) {
				throw new TmdbNotFoundException();
			}
			// On ne propage que le statut : ni l'URL ni les en-têtes n'arrivent dans les logs.
			throw new UpstreamException("TMDB a répondu " + ex.getStatusCode().value(), ex);
		}
		catch (RestClientException ex) {
			throw new UpstreamException("TMDB injoignable : " + ex.getMostSpecificCause().getClass().getSimpleName(), ex);
		}
	}
}
