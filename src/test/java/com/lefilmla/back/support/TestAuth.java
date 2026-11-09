package com.lefilmla.back.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.lefilmla.back.entity.AiAccess;
import com.lefilmla.back.entity.Role;
import com.lefilmla.back.entity.User;
import com.lefilmla.back.repository.UserRepository;
import com.lefilmla.back.service.TokenService;
import jakarta.servlet.http.Cookie;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.stereotype.Component;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Crée des comptes en base et rejoue le comportement du front (cookie de session + header CSRF). */
@Component
public class TestAuth {

	private final UserRepository userRepository;

	private final TokenService tokenService;

	public TestAuth(UserRepository userRepository, TokenService tokenService) {
		this.userRepository = userRepository;
		this.tokenService = tokenService;
	}

	public User createUser(Role role, AiAccess aiAccess) {
		String suffix = UUID.randomUUID().toString().substring(0, 12);
		User user = new User("user-" + suffix + "@example.com", "user" + suffix, "hash-inutilise");
		if (role == Role.ADMIN) {
			user.promoteToAdmin();
		}
		if (aiAccess == AiAccess.PENDING) {
			user.requestAiAccess(Instant.now());
		}
		else if (aiAccess != AiAccess.NONE) {
			user.decideAiAccess(aiAccess, null, Instant.now());
		}
		return userRepository.saveAndFlush(user);
	}

	public Cookie sessionCookie(User user) {
		return new Cookie("auth_token", tokenService.issue(user.getId()));
	}

	/** Requête modifiante telle que le front l'envoie : cookie XSRF-TOKEN + header X-XSRF-TOKEN. */
	public static ResultActions performWithCsrf(MockMvc mockMvc, MockHttpServletRequestBuilder request,
			Cookie... cookies) throws Exception {
		Cookie csrf = mockMvc.perform(get("/api/auth/me")).andReturn().getResponse().getCookie("XSRF-TOKEN");
		Cookie[] all = Stream.concat(Stream.of(cookies), Stream.of(csrf)).toArray(Cookie[]::new);
		return mockMvc.perform(request.cookie(all).header("X-XSRF-TOKEN", csrf.getValue()));
	}
}
