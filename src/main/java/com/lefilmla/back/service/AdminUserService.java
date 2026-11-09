package com.lefilmla.back.service;

import com.lefilmla.back.dto.AdminUserResponse;
import com.lefilmla.back.dto.PageResponse;
import com.lefilmla.back.entity.AiAccess;
import com.lefilmla.back.entity.User;
import com.lefilmla.back.repository.UserRepository;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminUserService {

	// Demandes les plus récentes d'abord ; comptes sans demande ensuite, par date d'inscription.
	private static final Sort SORT = Sort.by(Sort.Order.desc("aiAccessRequestedAt").nullsLast(),
			Sort.Order.desc("createdAt"));

	private final UserRepository userRepository;

	public AdminUserService(UserRepository userRepository) {
		this.userRepository = userRepository;
	}

	/** {@code aiAccess} et {@code query} sont facultatifs (null = pas de filtre). */
	@Transactional(readOnly = true)
	public PageResponse<AdminUserResponse> list(AiAccess aiAccess, String query, int page, int size) {
		return PageResponse.from(userRepository.findAll(filter(aiAccess, query), PageRequest.of(page, size, SORT)),
				AdminUserResponse::from);
	}

	static Specification<User> filter(AiAccess aiAccess, String query) {
		return (root, criteriaQuery, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			if (aiAccess != null) {
				predicates.add(cb.equal(root.get("aiAccess"), aiAccess));
			}
			if (query != null && !query.isBlank()) {
				String pattern = "%" + escapeLike(query.trim().toLowerCase(Locale.ROOT)) + "%";
				predicates.add(cb.or(cb.like(cb.lower(root.get("email")), pattern, '\\'),
						cb.like(cb.lower(root.get("username")), pattern, '\\')));
			}
			return cb.and(predicates.toArray(Predicate[]::new));
		};
	}

	// « 100% » ou « a_b » sont cherchés littéralement, pas comme jokers.
	static String escapeLike(String value) {
		return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}
}
