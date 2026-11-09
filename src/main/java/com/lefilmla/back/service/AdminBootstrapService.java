package com.lefilmla.back.service;

import com.lefilmla.back.entity.AiAccess;
import com.lefilmla.back.entity.Role;
import com.lefilmla.back.entity.User;
import com.lefilmla.back.repository.UserRepository;
import java.time.Clock;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Promotion du premier admin désigné par ADMIN_EMAIL. Uniquement au démarrage et uniquement tant qu'aucun admin
 * n'existe : sans vérification d'email, quelqu'un pourrait s'inscrire avec cette adresse et la variable ne doit
 * pas lui donner les droits une fois un vrai admin en place.
 */
@Service
public class AdminBootstrapService {

	private static final Logger log = LoggerFactory.getLogger(AdminBootstrapService.class);

	public enum Outcome {
		DISABLED, ADMIN_ALREADY_EXISTS, ACCOUNT_NOT_FOUND, PROMOTED
	}

	private final UserRepository userRepository;

	private final Clock clock;

	@Autowired
	public AdminBootstrapService(UserRepository userRepository) {
		this(userRepository, Clock.systemUTC());
	}

	AdminBootstrapService(UserRepository userRepository, Clock clock) {
		this.userRepository = userRepository;
		this.clock = clock;
	}

	// L'email n'est jamais logué (donnée personnelle).
	@Transactional
	public Outcome promoteInitialAdmin(String adminEmail) {
		if (adminEmail == null || adminEmail.isBlank()) {
			return Outcome.DISABLED;
		}
		if (userRepository.existsByRole(Role.ADMIN)) {
			log.info("ADMIN_EMAIL ignorée : un administrateur existe déjà en base, aucune promotion effectuée");
			return Outcome.ADMIN_ALREADY_EXISTS;
		}
		Optional<User> user = userRepository.findByEmail(AuthService.normalizeEmail(adminEmail));
		if (user.isEmpty()) {
			log.warn("ADMIN_EMAIL : aucun compte avec cet email, promotion impossible. "
					+ "Créez le compte puis redémarrez l'application");
			return Outcome.ACCOUNT_NOT_FOUND;
		}
		user.get().promoteToAdmin();
		user.get().decideAiAccess(AiAccess.GRANTED, null, clock.instant());
		log.info("ADMIN_EMAIL : compte {} promu administrateur avec accès à la recherche IA", user.get().getId());
		return Outcome.PROMOTED;
	}
}
