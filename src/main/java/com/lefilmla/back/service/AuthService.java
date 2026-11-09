package com.lefilmla.back.service;

import com.lefilmla.back.dto.LoginRequest;
import com.lefilmla.back.dto.RegisterRequest;
import com.lefilmla.back.dto.UserResponse;
import com.lefilmla.back.entity.User;
import com.lefilmla.back.exception.ConflictException;
import com.lefilmla.back.exception.InvalidCredentialsException;
import com.lefilmla.back.exception.InvalidRequestException;
import com.lefilmla.back.exception.UnauthenticatedException;
import com.lefilmla.back.repository.UserRepository;
import com.lefilmla.back.security.AuthenticatedUser;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

	// BCrypt ignore tout au-delà de 72 octets (et Spring Security refuse de hacher plus long).
	static final int BCRYPT_MAX_BYTES = 72;

	private final UserRepository userRepository;

	private final PasswordEncoder passwordEncoder;

	private final TokenService tokenService;

	// Hash comparé quand l'email est inconnu : le login prend le même temps que l'email existe ou non.
	private final String dummyHash;

	public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, TokenService tokenService) {
		this.userRepository = userRepository;
		this.passwordEncoder = passwordEncoder;
		this.tokenService = tokenService;
		this.dummyHash = passwordEncoder.encode("le-film-la-dummy-password");
	}

	public record AuthResult(UserResponse user, String token) {
	}

	public AuthResult register(RegisterRequest request) {
		String email = normalizeEmail(request.email());
		if (exceedsBcryptLimit(request.password())) {
			throw new InvalidRequestException("Le mot de passe est trop long");
		}
		if (userRepository.existsByEmail(email)) {
			throw emailTaken();
		}
		if (userRepository.existsByUsernameIgnoreCase(request.username())) {
			throw usernameTaken();
		}
		User user;
		try {
			user = userRepository.saveAndFlush(
					new User(email, request.username(), passwordEncoder.encode(request.password())));
		}
		catch (DataIntegrityViolationException ex) {
			// Deux inscriptions simultanées : l'index unique a tranché, on dit lequel.
			throw userRepository.existsByEmail(email) ? emailTaken() : usernameTaken();
		}
		return toResult(user);
	}

	public AuthResult login(LoginRequest request) {
		if (exceedsBcryptLimit(request.password())) {
			throw new InvalidCredentialsException();
		}
		Optional<User> user = userRepository.findByEmail(normalizeEmail(request.email()));
		String hash = user.map(User::getPasswordHash).orElse(dummyHash);
		boolean passwordMatches = passwordEncoder.matches(request.password(), hash);
		if (user.isEmpty() || !passwordMatches) {
			throw new InvalidCredentialsException();
		}
		return toResult(user.get());
	}

	/** Utilisateur du cookie ; 401 si anonyme ou si le compte n'existe plus. */
	public UserResponse currentUser(AuthenticatedUser principal) {
		if (principal == null) {
			throw new UnauthenticatedException();
		}
		return userRepository.findById(principal.id())
			.map(UserResponse::from)
			.orElseThrow(UnauthenticatedException::new);
	}

	static String normalizeEmail(String email) {
		return email.trim().toLowerCase(Locale.ROOT);
	}

	private static boolean exceedsBcryptLimit(String password) {
		return password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES;
	}

	private AuthResult toResult(User user) {
		return new AuthResult(UserResponse.from(user), tokenService.issue(user.getId()));
	}

	private static ConflictException emailTaken() {
		return new ConflictException("EMAIL_TAKEN", "Cet email est déjà utilisé");
	}

	private static ConflictException usernameTaken() {
		return new ConflictException("USERNAME_TAKEN", "Ce pseudo est déjà pris");
	}
}
