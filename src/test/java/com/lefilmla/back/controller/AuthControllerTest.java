package com.lefilmla.back.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lefilmla.back.PostgresTestConfiguration;
import com.lefilmla.back.client.TmdbClient;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockCookie;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

// Limite de débit relevée : ces tests enchaînent bien plus de logins/inscriptions que 10 par minute.
@SpringBootTest(properties = "rate-limit.auth.capacity=1000")
@AutoConfigureMockMvc
@Import(PostgresTestConfiguration.class)
class AuthControllerTest {

	private static final String AUTH_COOKIE = "auth_token";

	private static final String CSRF_COOKIE = "XSRF-TOKEN";

	private static final String CSRF_HEADER = "X-XSRF-TOKEN";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private TmdbClient tmdbClient;

	@Test
	void firstVisitSetsCsrfCookieReadableByJavascript() throws Exception {
		MvcResult result = mockMvc.perform(get("/api/auth/me")).andReturn();

		Cookie csrf = result.getResponse().getCookie(CSRF_COOKIE);
		assertThat(csrf).isNotNull();
		assertThat(csrf.getValue()).isNotBlank();
		assertThat(csrf.isHttpOnly()).isFalse();
		assertThat(csrf.getPath()).isEqualTo("/");
	}

	@Test
	void registerCreatesAccountAndSetsSessionCookie() throws Exception {
		String email = uniqueEmail();

		MvcResult result = register(email.toUpperCase(), uniqueUsername(), "motdepasse")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.id").isNotEmpty())
			.andExpect(jsonPath("$.email").value(email))
			.andReturn();

		MockCookie cookie = (MockCookie) result.getResponse().getCookie(AUTH_COOKIE);
		assertThat(cookie).isNotNull();
		assertThat(cookie.getValue()).isNotBlank();
		assertThat(cookie.isHttpOnly()).isTrue();
		assertThat(cookie.getSecure()).isTrue();
		assertThat(cookie.getSameSite()).isEqualTo("Lax");
		assertThat(cookie.getPath()).isEqualTo("/");
		assertThat(cookie.getMaxAge()).isEqualTo(24 * 3600);
	}

	@Test
	void registerRejectsDuplicateEmailWhateverTheCase() throws Exception {
		String email = uniqueEmail();
		register(email, uniqueUsername(), "motdepasse").andExpect(status().isCreated());

		register(email.toUpperCase(), uniqueUsername(), "motdepasse")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error").value("EMAIL_TAKEN"));
	}

	@Test
	void registerRejectsDuplicateUsernameWhateverTheCase() throws Exception {
		String username = uniqueUsername();
		register(uniqueEmail(), username, "motdepasse").andExpect(status().isCreated());

		register(uniqueEmail(), username.toUpperCase(), "motdepasse")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error").value("USERNAME_TAKEN"));
	}

	@Test
	void registerValidatesInput() throws Exception {
		register(uniqueEmail(), uniqueUsername(), "court")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.message").value("Le mot de passe doit faire entre 8 et 72 caractères"));
		register("pas-un-email", uniqueUsername(), "motdepasse")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value("L'email n'est pas valide"));
		register(uniqueEmail(), "ab", "motdepasse")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.message").value("Le pseudo doit faire entre 3 et 30 caractères"));
	}

	@Test
	void loginWithValidCredentialsSetsSessionCookie() throws Exception {
		String email = uniqueEmail();
		register(email, uniqueUsername(), "motdepasse").andExpect(status().isCreated());

		MvcResult result = login(email, "motdepasse")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.email").value(email))
			.andReturn();

		assertThat(result.getResponse().getCookie(AUTH_COOKIE).getValue()).isNotBlank();
	}

	@Test
	void loginFailureDoesNotRevealWhetherEmailExists() throws Exception {
		String email = uniqueEmail();
		register(email, uniqueUsername(), "motdepasse").andExpect(status().isCreated());

		String wrongPassword = login(email, "mauvais-mdp")
			.andExpect(status().isUnauthorized())
			.andReturn().getResponse().getContentAsString();
		String unknownEmail = login(uniqueEmail(), "motdepasse")
			.andExpect(status().isUnauthorized())
			.andReturn().getResponse().getContentAsString();

		assertThat(wrongPassword).isEqualTo(unknownEmail).contains("INVALID_CREDENTIALS");
	}

	@Test
	void meReturnsCurrentUserWithSessionCookie() throws Exception {
		String email = uniqueEmail();
		String username = uniqueUsername();
		Cookie session = register(email, username, "motdepasse").andReturn().getResponse().getCookie(AUTH_COOKIE);

		mockMvc.perform(get("/api/auth/me").cookie(session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.id").isNotEmpty())
			.andExpect(jsonPath("$.email").value(email))
			.andExpect(jsonPath("$.username").value(username))
			.andExpect(jsonPath("$.password").doesNotExist())
			.andExpect(jsonPath("$.passwordHash").doesNotExist());
	}

	@Test
	void meWithoutValidCookieReturns401() throws Exception {
		mockMvc.perform(get("/api/auth/me"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
		mockMvc.perform(get("/api/auth/me").cookie(new Cookie(AUTH_COOKIE, "jeton.bidon.invalide")))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
	}

	@Test
	void logoutClearsSessionCookie() throws Exception {
		Cookie session = register(uniqueEmail(), uniqueUsername(), "motdepasse")
			.andReturn().getResponse().getCookie(AUTH_COOKIE);

		MvcResult result = withCsrf(post("/api/auth/logout"), session)
			.andExpect(status().isNoContent())
			.andReturn();

		Cookie cleared = result.getResponse().getCookie(AUTH_COOKIE);
		assertThat(cleared.getValue()).isEmpty();
		assertThat(cleared.getMaxAge()).isZero();
	}

	@Test
	void mutatingRequestWithoutCsrfHeaderIsForbidden() throws Exception {
		Cookie csrf = fetchCsrfCookie();
		String body = registerBody(uniqueEmail(), uniqueUsername(), "motdepasse");

		// Cookie présent mais header absent : c'est précisément ce que peut faire un site tiers.
		mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content(body).cookie(csrf))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error").value("CSRF_INVALID"));
		mockMvc.perform(post("/api/auth/logout"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error").value("CSRF_INVALID"));
		mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
				.content("{\"email\":\"a@b.fr\",\"password\":\"motdepasse\"}")
				.cookie(csrf).header(CSRF_HEADER, "mauvais-jeton"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error").value("CSRF_INVALID"));
	}

	@Test
	void newEndpointsAreProtectedByDefault() throws Exception {
		mockMvc.perform(get("/api/watchlist"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.error").value("UNAUTHENTICATED"));
	}

	@Test
	void publicEndpointStillWorksWithAnInvalidSessionCookie() throws Exception {
		when(tmdbClient.searchMulti("dune")).thenReturn(JsonMapper.builder().build().readTree("{\"results\":[]}"));

		mockMvc.perform(get("/api/movies/search").param("q", "dune")
				.cookie(new Cookie(AUTH_COOKIE, "jeton.expire.invalide")))
			.andExpect(status().isOk());
	}

	private ResultActions register(String email, String username, String password) throws Exception {
		return withCsrf(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
			.content(registerBody(email, username, password)));
	}

	private ResultActions login(String email, String password) throws Exception {
		return withCsrf(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{\"email\":\"%s\",\"password\":\"%s\"}".formatted(email, password)));
	}

	/** Reproduit le front : lit le cookie XSRF-TOKEN puis le renvoie dans le header X-XSRF-TOKEN. */
	private ResultActions withCsrf(
			org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, Cookie... cookies)
			throws Exception {
		Cookie csrf = fetchCsrfCookie();
		Cookie[] all = Stream.concat(Stream.of(cookies), Stream.of(csrf)).toArray(Cookie[]::new);
		return mockMvc.perform(request.cookie(all).header(CSRF_HEADER, csrf.getValue()));
	}

	private Cookie fetchCsrfCookie() throws Exception {
		return mockMvc.perform(get("/api/auth/me")).andReturn().getResponse().getCookie(CSRF_COOKIE);
	}

	private static String registerBody(String email, String username, String password) {
		return "{\"email\":\"%s\",\"username\":\"%s\",\"password\":\"%s\"}".formatted(email, username, password);
	}

	private static String uniqueEmail() {
		return "user-" + UUID.randomUUID() + "@example.com";
	}

	private static String uniqueUsername() {
		return "user" + UUID.randomUUID().toString().substring(0, 12);
	}
}
