package com.lefilmla.back.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lefilmla.back.dto.LoginRequest;
import com.lefilmla.back.dto.RegisterRequest;
import com.lefilmla.back.entity.AiAccess;
import com.lefilmla.back.entity.Role;
import com.lefilmla.back.entity.User;
import com.lefilmla.back.exception.ConflictException;
import com.lefilmla.back.exception.InvalidCredentialsException;
import com.lefilmla.back.exception.InvalidRequestException;
import com.lefilmla.back.exception.UnauthenticatedException;
import com.lefilmla.back.repository.UserRepository;
import com.lefilmla.back.security.AuthenticatedUser;
import com.lefilmla.back.service.AuthService.AuthResult;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

class AuthServiceTest {

	private final UserRepository userRepository = mock(UserRepository.class);

	private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);

	private final TokenService tokenService = mock(TokenService.class);

	private AuthService authService;

	@BeforeEach
	void setUp() {
		when(passwordEncoder.encode("le-film-la-dummy-password")).thenReturn("dummy-hash");
		authService = new AuthService(userRepository, passwordEncoder, tokenService);
	}

	@Test
	void registerNormalizesEmailHashesPasswordAndIssuesToken() {
		when(passwordEncoder.encode("motdepasse")).thenReturn("hash");
		when(userRepository.saveAndFlush(any())).thenAnswer(inv -> withId(inv.getArgument(0)));
		when(tokenService.issue(any())).thenReturn("jwt");

		AuthResult result = authService.register(new RegisterRequest("  Alice@Example.COM ", "alice", "motdepasse"));

		ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
		verify(userRepository).saveAndFlush(saved.capture());
		assertThat(saved.getValue().getEmail()).isEqualTo("alice@example.com");
		assertThat(saved.getValue().getPasswordHash()).isEqualTo("hash");
		assertThat(result.user().email()).isEqualTo("alice@example.com");
		assertThat(result.token()).isEqualTo("jwt");
	}

	@Test
	void registerRejectsTakenEmail() {
		when(userRepository.existsByEmail("alice@example.com")).thenReturn(true);

		assertThatThrownBy(() -> authService.register(new RegisterRequest("alice@example.com", "alice", "motdepasse")))
			.isInstanceOfSatisfying(ConflictException.class, ex -> assertThat(ex.code()).isEqualTo("EMAIL_TAKEN"));
		verify(userRepository, never()).saveAndFlush(any());
	}

	@Test
	void registerRejectsTakenUsername() {
		when(userRepository.existsByUsernameIgnoreCase("Alice")).thenReturn(true);

		assertThatThrownBy(() -> authService.register(new RegisterRequest("alice@example.com", "Alice", "motdepasse")))
			.isInstanceOfSatisfying(ConflictException.class, ex -> assertThat(ex.code()).isEqualTo("USERNAME_TAKEN"));
	}

	@Test
	void registerReportsConflictWhenUniqueIndexWinsARace() {
		when(userRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate"));

		assertThatThrownBy(() -> authService.register(new RegisterRequest("alice@example.com", "alice", "motdepasse")))
			.isInstanceOfSatisfying(ConflictException.class, ex -> assertThat(ex.code()).isEqualTo("USERNAME_TAKEN"));
	}

	@Test
	void registerRejectsPasswordBeyondBcryptByteLimit() {
		// 40 caractères mais 80 octets en UTF-8 : passe @Size, pas BCrypt.
		String password = "é".repeat(40);

		assertThatThrownBy(() -> authService.register(new RegisterRequest("alice@example.com", "alice", password)))
			.isInstanceOf(InvalidRequestException.class);
		verify(userRepository, never()).saveAndFlush(any());
	}

	@Test
	void loginSucceedsWithMatchingPassword() {
		User user = withId(new User("alice@example.com", "alice", "hash"));
		when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
		when(passwordEncoder.matches("motdepasse", "hash")).thenReturn(true);
		when(tokenService.issue(user.getId())).thenReturn("jwt");

		AuthResult result = authService.login(new LoginRequest("Alice@example.com", "motdepasse"));

		assertThat(result.user().id()).isEqualTo(user.getId());
		assertThat(result.token()).isEqualTo("jwt");
	}

	@Test
	void loginRejectsWrongPassword() {
		when(userRepository.findByEmail("alice@example.com"))
			.thenReturn(Optional.of(withId(new User("alice@example.com", "alice", "hash"))));

		assertThatThrownBy(() -> authService.login(new LoginRequest("alice@example.com", "mauvais")))
			.isInstanceOf(InvalidCredentialsException.class);
	}

	@Test
	void loginWithUnknownEmailStillChecksAPasswordToHideTiming() {
		when(userRepository.findByEmail("inconnu@example.com")).thenReturn(Optional.empty());

		assertThatThrownBy(() -> authService.login(new LoginRequest("inconnu@example.com", "motdepasse")))
			.isInstanceOf(InvalidCredentialsException.class);
		verify(passwordEncoder).matches("motdepasse", "dummy-hash");
	}

	@Test
	void currentUserRequiresAuthenticationAndAnExistingAccount() {
		UUID id = UUID.randomUUID();
		when(userRepository.findById(id)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> authService.currentUser(null)).isInstanceOf(UnauthenticatedException.class);
		assertThatThrownBy(() -> authService.currentUser(new AuthenticatedUser(id, Role.USER, AiAccess.NONE)))
			.isInstanceOf(UnauthenticatedException.class);
	}

	private static User withId(User user) {
		ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
		return user;
	}
}
