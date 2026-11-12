package com.lefilmla.back.service;

import com.lefilmla.back.dto.AdminUserResponse;
import com.lefilmla.back.dto.UserResponse;
import com.lefilmla.back.entity.AiAccess;
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
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cycle de vie de l'accès à la recherche IA.
 * <pre>
 * NONE/REJECTED --demande--> PENDING
 * NONE/PENDING/REJECTED --grant--> GRANTED (déjà GRANTED : sans effet)
 * PENDING --reject--> REJECTED
 * GRANTED --revoke--> NONE
 * </pre>
 */
@Service
public class AiAccessService {

	static final String INVALID_TRANSITION = "INVALID_AI_ACCESS_TRANSITION";

	private static final Set<AiAccess> REQUESTABLE = EnumSet.of(AiAccess.NONE, AiAccess.REJECTED);

	private final UserRepository userRepository;

	private final AiAccessEventPublisher events;

	private final Clock clock;

	@Autowired
	public AiAccessService(UserRepository userRepository, AiAccessEventPublisher events) {
		this(userRepository, events, Clock.systemUTC());
	}

	AiAccessService(UserRepository userRepository, AiAccessEventPublisher events, Clock clock) {
		this.userRepository = userRepository;
		this.events = events;
		this.clock = clock;
	}

	/** 403 AI_ACCESS_REQUIRED si l'accès n'est pas accordé (statut relu en base par le filtre JWT). */
	public void requireGranted(AuthenticatedUser principal) {
		if (principal == null) {
			throw new UnauthenticatedException();
		}
		if (principal.aiAccess() != AiAccess.GRANTED) {
			throw new ForbiddenException("AI_ACCESS_REQUIRED",
					"L'accès à la recherche IA doit être accordé par un administrateur");
		}
	}

	@Transactional
	public UserResponse request(AuthenticatedUser principal) {
		if (principal == null) {
			throw new UnauthenticatedException();
		}
		User user = userRepository.findById(principal.id()).orElseThrow(UnauthenticatedException::new);
		if (!REQUESTABLE.contains(user.getAiAccess())) {
			throw new ConflictException(INVALID_TRANSITION, switch (user.getAiAccess()) {
				case PENDING -> "Une demande d'accès est déjà en attente";
				case GRANTED -> "Vous avez déjà accès à la recherche IA";
				default -> "Demande d'accès impossible dans l'état actuel";
			});
		}
		AiAccess previous = user.getAiAccess();
		Instant now = clock.instant();
		user.requestAiAccess(now);
		events.publish(AiAccessEventType.REQUESTED, user, previous, null, now);
		return UserResponse.from(user);
	}

	@Transactional
	public AdminUserResponse grant(AuthenticatedUser admin, UUID userId) {
		User user = findUser(userId);
		if (user.getAiAccess() != AiAccess.GRANTED) {
			decide(user, AiAccess.GRANTED, AiAccessEventType.GRANTED, admin);
		}
		return AdminUserResponse.from(user);
	}

	@Transactional
	public AdminUserResponse reject(AuthenticatedUser admin, UUID userId) {
		User user = findUser(userId);
		forbidSelf(admin, user);
		if (user.getAiAccess() != AiAccess.PENDING) {
			throw new ConflictException(INVALID_TRANSITION,
					"Seule une demande en attente peut être refusée (statut actuel : " + user.getAiAccess() + ")");
		}
		decide(user, AiAccess.REJECTED, AiAccessEventType.REJECTED, admin);
		return AdminUserResponse.from(user);
	}

	@Transactional
	public AdminUserResponse revoke(AuthenticatedUser admin, UUID userId) {
		User user = findUser(userId);
		forbidSelf(admin, user);
		if (user.getAiAccess() != AiAccess.GRANTED) {
			throw new ConflictException(INVALID_TRANSITION,
					"Seul un accès accordé peut être retiré (statut actuel : " + user.getAiAccess() + ")");
		}
		decide(user, AiAccess.NONE, AiAccessEventType.REVOKED, admin);
		return AdminUserResponse.from(user);
	}

	/** Applique la décision et dépose l'événement correspondant dans l'outbox (même transaction). */
	private void decide(User user, AiAccess status, AiAccessEventType type, AuthenticatedUser admin) {
		AiAccess previous = user.getAiAccess();
		Instant now = clock.instant();
		User decidedBy = userRepository.getReferenceById(admin.id());
		user.decideAiAccess(status, decidedBy, now);
		events.publish(type, user, previous, decidedBy, now);
	}

	private User findUser(UUID userId) {
		return userRepository.findById(userId).orElseThrow(UserNotFoundException::new);
	}

	private static void forbidSelf(AuthenticatedUser admin, User user) {
		if (admin.id().equals(user.getId())) {
			throw new ForbiddenException("SELF_ACCESS_CHANGE", "Vous ne pouvez pas retirer votre propre accès");
		}
	}
}
