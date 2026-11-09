package com.lefilmla.back.repository;

import com.lefilmla.back.entity.Role;
import com.lefilmla.back.entity.User;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends JpaRepository<User, UUID>, JpaSpecificationExecutor<User> {

	// Les emails sont stockés en minuscules : comparaison directe.
	Optional<User> findByEmail(String email);

	boolean existsByEmail(String email);

	// lower() pour s'appuyer sur l'index unique users_username_key.
	@Query("select count(u) > 0 from User u where lower(u.username) = lower(:username)")
	boolean existsByUsernameIgnoreCase(String username);

	boolean existsByRole(Role role);

	// Charge l'admin décideur dans la même requête (pas de N+1 sur la liste admin).
	@Override
	@EntityGraph(attributePaths = "aiAccessDecidedBy")
	Page<User> findAll(Specification<User> spec, Pageable pageable);
}
