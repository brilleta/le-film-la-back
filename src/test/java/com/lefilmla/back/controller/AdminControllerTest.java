package com.lefilmla.back.controller;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lefilmla.back.TestcontainersConfiguration;
import com.lefilmla.back.entity.AiAccess;
import com.lefilmla.back.entity.Role;
import com.lefilmla.back.entity.User;
import com.lefilmla.back.support.TestAuth;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
class AdminControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private TestAuth testAuth;

	private User admin;

	private Cookie adminSession;

	@BeforeEach
	void setUp() {
		admin = testAuth.createUser(Role.ADMIN, AiAccess.GRANTED);
		adminSession = testAuth.sessionCookie(admin);
	}

	private ResultActions decide(Cookie session, UUID userId, String action) throws Exception {
		return TestAuth.performWithCsrf(mockMvc, post("/api/admin/users/{id}/ai-access/{action}", userId, action),
				session);
	}

	@Test
	void adminEndpointsAreForbiddenToRegularUsers() throws Exception {
		User user = testAuth.createUser(Role.USER, AiAccess.GRANTED);
		Cookie userSession = testAuth.sessionCookie(user);

		mockMvc.perform(get("/api/admin/users").cookie(userSession))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error").value("FORBIDDEN"));
		decide(userSession, user.getId(), "grant")
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error").value("FORBIDDEN"));
		mockMvc.perform(get("/api/admin/users"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void listFiltersByStatusAndSearchesEmailOrUsername() throws Exception {
		User pending = testAuth.createUser(Role.USER, AiAccess.PENDING);
		User none = testAuth.createUser(Role.USER, AiAccess.NONE);

		mockMvc.perform(get("/api/admin/users").cookie(adminSession)
				.param("aiAccess", "PENDING").param("size", "100"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content[*].id", hasItem(pending.getId().toString())))
			.andExpect(jsonPath("$.content[*].id", not(hasItem(none.getId().toString()))))
			.andExpect(jsonPath("$.content[*].aiAccess", not(hasItem("NONE"))));

		// Recherche insensible à la casse, sur le pseudo...
		mockMvc.perform(get("/api/admin/users").cookie(adminSession).param("q", pending.getUsername().toUpperCase()))
			.andExpect(jsonPath("$.content[*].id", contains(pending.getId().toString())))
			.andExpect(jsonPath("$.content[0].email").value(pending.getEmail()))
			.andExpect(jsonPath("$.content[0].role").value("USER"))
			.andExpect(jsonPath("$.content[0].createdAt").isNotEmpty())
			.andExpect(jsonPath("$.content[0].aiAccessRequestedAt").isNotEmpty())
			.andExpect(jsonPath("$.totalElements").value(1));
		// ... et sur l'email ; les jokers SQL sont cherchés littéralement.
		mockMvc.perform(get("/api/admin/users").cookie(adminSession).param("q", none.getEmail()))
			.andExpect(jsonPath("$.content[*].id", contains(none.getId().toString())));
		mockMvc.perform(get("/api/admin/users").cookie(adminSession).param("q", "%"))
			.andExpect(jsonPath("$.totalElements").value(0));
	}

	@Test
	void listIsPaginatedAndValidatesParameters() throws Exception {
		testAuth.createUser(Role.USER, AiAccess.NONE);
		testAuth.createUser(Role.USER, AiAccess.NONE);

		mockMvc.perform(get("/api/admin/users").cookie(adminSession).param("page", "0").param("size", "1"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.content.length()").value(1))
			.andExpect(jsonPath("$.page").value(0))
			.andExpect(jsonPath("$.size").value(1))
			.andExpect(jsonPath("$.totalPages").isNumber());
		mockMvc.perform(get("/api/admin/users").cookie(adminSession).param("size", "101"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
		mockMvc.perform(get("/api/admin/users").cookie(adminSession).param("aiAccess", "INCONNU"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void grantRejectAndRevokeFollowTheLifecycle() throws Exception {
		User pending = testAuth.createUser(Role.USER, AiAccess.PENDING);

		decide(adminSession, pending.getId(), "grant")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.aiAccess").value("GRANTED"))
			.andExpect(jsonPath("$.aiAccessDecidedAt").isNotEmpty())
			.andExpect(jsonPath("$.aiAccessDecidedBy.id").value(admin.getId().toString()))
			.andExpect(jsonPath("$.aiAccessDecidedBy.username").value(admin.getUsername()));
		// Grant idempotent.
		decide(adminSession, pending.getId(), "grant").andExpect(status().isOk());
		decide(adminSession, pending.getId(), "revoke")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.aiAccess").value("NONE"));

		User other = testAuth.createUser(Role.USER, AiAccess.PENDING);
		decide(adminSession, other.getId(), "reject")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.aiAccess").value("REJECTED"));
	}

	@Test
	void invalidTransitionsAreConflictsWithExplicitMessage() throws Exception {
		User none = testAuth.createUser(Role.USER, AiAccess.NONE);

		decide(adminSession, none.getId(), "reject")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.error").value("INVALID_AI_ACCESS_TRANSITION"))
			.andExpect(jsonPath("$.message")
				.value("Seule une demande en attente peut être refusée (statut actuel : NONE)"));
		decide(adminSession, none.getId(), "revoke")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.message").value("Seul un accès accordé peut être retiré (statut actuel : NONE)"));
	}

	@Test
	void adminCannotRemoveOwnAccess() throws Exception {
		decide(adminSession, admin.getId(), "revoke")
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.error").value("SELF_ACCESS_CHANGE"));
	}

	@Test
	void unknownUserIs404() throws Exception {
		decide(adminSession, UUID.randomUUID(), "grant")
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.error").value("NOT_FOUND"));
	}
}
