package com.lefilmla.back.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.lefilmla.back.entity.AiAccess;
import com.lefilmla.back.entity.Role;
import com.lefilmla.back.entity.User;
import com.lefilmla.back.events.AiAccessEventPublisher;
import com.lefilmla.back.events.AiAccessEventType;
import com.lefilmla.back.exception.ConflictException;
import com.lefilmla.back.exception.ForbiddenException;
import com.lefilmla.back.exception.UnauthenticatedException;
import com.lefilmla.back.exception.UserNotFoundException;
import com.lefilmla.back.repository.UserRepository;
import com.lefilmla.back.security.AuthenticatedUser;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

class AiAccessServiceTest {

	private static final Instant NOW = Instant.parse("2026-11-09T10:00:00Z");

	private final UserRepository userRepository = mock(UserRepository.class);

	private final AiAccessEventPublisher events = mock(AiAccessEventPublisher.class);

	private final AiAccessService service = new AiAccessService(userRepository, events,
			Clock.fixed(NOW, ZoneOffset.UTC));

	private User adminEntity;

	private AuthenticatedUser admin;

	@BeforeEach
	void setUp() {
		adminEntity = user(AiAccess.GRANTED);
		adminEntity.promoteToAdmin();
		admin = new AuthenticatedUser(adminEntity.getId(), Role.ADMIN, AiAccess.GRANTED);
		when(userRepository.getReferenceById(admin.id())).thenReturn(adminEntity);
		when(userRepository.findById(admin.id())).thenReturn(Optional.of(adminEntity));
	}

	@Test
	void requireGrantedAcceptsOnlyGranted() {
		assertThatCode(() -> service.requireGranted(principal(AiAccess.GRANTED))).doesNotThrowAnyException();
		assertThatThrownBy(() -> service.requireGranted(null)).isInstanceOf(UnauthenticatedException.class);
		for (AiAccess status : new AiAccess[] { AiAccess.NONE, AiAccess.PENDING, AiAccess.REJECTED }) {
			assertThatThrownBy(() -> service.requireGranted(principal(status)))
				.isInstanceOfSatisfying(ForbiddenException.class,
						ex -> assertThat(ex.code()).isEqualTo("AI_ACCESS_REQUIRED"));
		}
	}

	@ParameterizedTest
	@EnumSource(value = AiAccess.class, names = { "NONE", "REJECTED" })
	void requestMovesToPendingAndClearsPreviousDecision(AiAccess from) {
		User user = stored(from);

		assertThat(service.request(principalOf(user)).aiAccess()).isEqualTo(AiAccess.PENDING);
		assertThat(user.getAiAccessRequestedAt()).isEqualTo(NOW);
		assertThat(user.getAiAccessDecidedAt()).isNull();
		assertThat(user.getAiAccessDecidedBy()).isNull();
		verify(events).publish(AiAccessEventType.REQUESTED, user, from, null, NOW);
	}

	@ParameterizedTest
	@EnumSource(value = AiAccess.class, names = { "PENDING", "GRANTED" })
	void requestFromPendingOrGrantedIsRejected(AiAccess from) {
		User user = stored(from);

		assertThatThrownBy(() -> service.request(principalOf(user)))
			.isInstanceOfSatisfying(ConflictException.class,
					ex -> assertThat(ex.code()).isEqualTo("INVALID_AI_ACCESS_TRANSITION"));
		assertThat(user.getAiAccess()).isEqualTo(from);
		verifyNoInteractions(events);
	}

	@ParameterizedTest
	@EnumSource(value = AiAccess.class, names = { "NONE", "PENDING", "REJECTED" })
	void grantRecordsDecisionFromAnyNonGrantedStatus(AiAccess from) {
		User user = stored(from);

		var response = service.grant(admin, user.getId());

		assertThat(response.aiAccess()).isEqualTo(AiAccess.GRANTED);
		assertThat(user.getAiAccessDecidedAt()).isEqualTo(NOW);
		assertThat(user.getAiAccessDecidedBy()).isSameAs(adminEntity);
		assertThat(response.aiAccessDecidedBy().id()).isEqualTo(admin.id());
		verify(events).publish(AiAccessEventType.GRANTED, user, from, adminEntity, NOW);
	}

	@Test
	void grantIsIdempotentAndKeepsTheOriginalDecision() {
		User user = stored(AiAccess.GRANTED);

		service.grant(admin, user.getId());

		assertThat(user.getAiAccessDecidedBy()).isNull();
		verifyNoInteractions(events);
	}

	@Test
	void rejectOnlyFromPending() {
		User pending = stored(AiAccess.PENDING);
		assertThat(service.reject(admin, pending.getId()).aiAccess()).isEqualTo(AiAccess.REJECTED);
		verify(events).publish(AiAccessEventType.REJECTED, pending, AiAccess.PENDING, adminEntity, NOW);

		for (AiAccess from : new AiAccess[] { AiAccess.NONE, AiAccess.GRANTED, AiAccess.REJECTED }) {
			User user = stored(from);
			assertThatThrownBy(() -> service.reject(admin, user.getId()))
				.isInstanceOf(ConflictException.class)
				.hasMessageContaining("statut actuel : " + from);
		}
	}

	@Test
	void revokeOnlyFromGrantedAndBackToNone() {
		User granted = stored(AiAccess.GRANTED);
		assertThat(service.revoke(admin, granted.getId()).aiAccess()).isEqualTo(AiAccess.NONE);
		assertThat(granted.getAiAccessDecidedBy()).isSameAs(adminEntity);
		verify(events).publish(AiAccessEventType.REVOKED, granted, AiAccess.GRANTED, adminEntity, NOW);

		for (AiAccess from : new AiAccess[] { AiAccess.NONE, AiAccess.PENDING, AiAccess.REJECTED }) {
			User user = stored(from);
			assertThatThrownBy(() -> service.revoke(admin, user.getId())).isInstanceOf(ConflictException.class);
		}
		verify(events).publish(eq(AiAccessEventType.REVOKED), any(), any(), any(), any());
	}

	@Test
	void adminCannotRevokeOrRejectThemself() {
		assertThatThrownBy(() -> service.revoke(admin, admin.id()))
			.isInstanceOfSatisfying(ForbiddenException.class,
					ex -> assertThat(ex.code()).isEqualTo("SELF_ACCESS_CHANGE"));
		assertThatThrownBy(() -> service.reject(admin, admin.id())).isInstanceOf(ForbiddenException.class);
		assertThat(adminEntity.getAiAccess()).isEqualTo(AiAccess.GRANTED);
	}

	@Test
	void unknownUserIsNotFound() {
		UUID unknown = UUID.randomUUID();
		when(userRepository.findById(unknown)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.grant(admin, unknown)).isInstanceOf(UserNotFoundException.class);
	}

	private User stored(AiAccess status) {
		User user = user(status);
		when(userRepository.findById(user.getId())).thenReturn(Optional.of(user));
		return user;
	}

	private static User user(AiAccess status) {
		User user = new User(UUID.randomUUID() + "@example.com", "user", "hash");
		ReflectionTestUtils.setField(user, "id", UUID.randomUUID());
		ReflectionTestUtils.setField(user, "aiAccess", status);
		return user;
	}

	private static AuthenticatedUser principalOf(User user) {
		return new AuthenticatedUser(user.getId(), user.getRole(), user.getAiAccess());
	}

	private static AuthenticatedUser principal(AiAccess status) {
		return new AuthenticatedUser(UUID.randomUUID(), Role.USER, status);
	}
}
