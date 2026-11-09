package com.lefilmla.back.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lefilmla.back.entity.AiAccess;
import com.lefilmla.back.entity.Role;
import com.lefilmla.back.entity.User;
import com.lefilmla.back.repository.UserRepository;
import com.lefilmla.back.service.AdminBootstrapService.Outcome;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AdminBootstrapServiceTest {

	private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");

	private final UserRepository userRepository = mock(UserRepository.class);

	private final AdminBootstrapService service = new AdminBootstrapService(userRepository,
			Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	void disabledWhenVariableIsMissing() {
		assertThat(service.promoteInitialAdmin(null)).isEqualTo(Outcome.DISABLED);
		assertThat(service.promoteInitialAdmin("  ")).isEqualTo(Outcome.DISABLED);
		verify(userRepository, never()).existsByRole(Role.ADMIN);
	}

	@Test
	void skippedWhenAnAdminAlreadyExists() {
		when(userRepository.existsByRole(Role.ADMIN)).thenReturn(true);

		assertThat(service.promoteInitialAdmin("boss@example.com")).isEqualTo(Outcome.ADMIN_ALREADY_EXISTS);
		verify(userRepository, never()).findByEmail(anyString());
	}

	@Test
	void accountNotFound() {
		when(userRepository.findByEmail("boss@example.com")).thenReturn(Optional.empty());

		assertThat(service.promoteInitialAdmin("boss@example.com")).isEqualTo(Outcome.ACCOUNT_NOT_FOUND);
	}

	@Test
	void promotesWithNormalizedEmailAndGrantsAiAccess() {
		User boss = new User("boss@example.com", "boss", "hash");
		when(userRepository.findByEmail("boss@example.com")).thenReturn(Optional.of(boss));

		assertThat(service.promoteInitialAdmin(" Boss@Example.com ")).isEqualTo(Outcome.PROMOTED);
		assertThat(boss.getRole()).isEqualTo(Role.ADMIN);
		assertThat(boss.getAiAccess()).isEqualTo(AiAccess.GRANTED);
		assertThat(boss.getAiAccessDecidedAt()).isEqualTo(NOW);
		assertThat(boss.getAiAccessDecidedBy()).isNull();
	}
}
