package com.lefilmla.back.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lefilmla.back.TestcontainersConfiguration;
import com.lefilmla.back.entity.AiAccess;
import com.lefilmla.back.entity.Role;
import com.lefilmla.back.entity.User;
import com.lefilmla.back.support.TestAuth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AiAccessControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TestAuth testAuth;

	private ResultActions requestAccess(User user) throws Exception {
		return TestAuth.performWithCsrf(mockMvc, post("/api/ai/access-request"), testAuth.sessionCookie(user));
	}

	@Test
	void meExposesRoleAndAiAccess() throws Exception {
		User user = testAuth.createUser(Role.USER, AiAccess.NONE);

		mockMvc.perform(get("/api/auth/me").cookie(testAuth.sessionCookie(user)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.role").value("USER"))
			.andExpect(jsonPath("$.aiAccess").value("NONE"));
	}

	@Test
	void requestFromNoneOrRejectedBecomesPending() throws Exception {
		for (AiAccess from : new AiAccess[] { AiAccess.NONE, AiAccess.REJECTED }) {
			User user = testAuth.createUser(Role.USER, from);

			requestAccess(user)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(user.getId().toString()))
				.andExpect(jsonPath("$.aiAccess").value("PENDING"));
			mockMvc.perform(get("/api/auth/me").cookie(testAuth.sessionCookie(user)))
				.andExpect(jsonPath("$.aiAccess").value("PENDING"));
		}
	}

	@Test
	void requestFromPendingOrGrantedIsAConflict() throws Exception {
		requestAccess(testAuth.createUser(Role.USER, AiAccess.PENDING))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error").value("INVALID_AI_ACCESS_TRANSITION"))
			.andExpect(jsonPath("$.message").value("Une demande d'accès est déjà en attente"));
		requestAccess(testAuth.createUser(Role.USER, AiAccess.GRANTED))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.message").value("Vous avez déjà accès à la recherche IA"));
	}

	@Test
	void requestRequiresAuthenticationAndCsrf() throws Exception {
		TestAuth.performWithCsrf(mockMvc, post("/api/ai/access-request"))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(post("/api/ai/access-request")
				.cookie(testAuth.sessionCookie(testAuth.createUser(Role.USER, AiAccess.NONE))))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error").value("CSRF_INVALID"));
	}
}
