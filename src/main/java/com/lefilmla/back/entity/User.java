package com.lefilmla.back.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "users")
public class User {

	@Id
	@GeneratedValue(strategy = GenerationType.UUID)
	private UUID id;

	@Column(nullable = false)
	private String email;

	@Column(nullable = false)
	private String username;

	@Column(name = "password_hash", nullable = false)
	private String passwordHash;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private Role role = Role.USER;

	@Enumerated(EnumType.STRING)
	@Column(name = "ai_access", nullable = false)
	private AiAccess aiAccess = AiAccess.NONE;

	@Column(name = "ai_access_requested_at")
	private Instant aiAccessRequestedAt;

	@Column(name = "ai_access_decided_at")
	private Instant aiAccessDecidedAt;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "ai_access_decided_by")
	private User aiAccessDecidedBy;

	@Version
	private long version;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected User() {
		// requis par JPA
	}

	public User(String email, String username, String passwordHash) {
		this.email = email;
		this.username = username;
		this.passwordHash = passwordHash;
	}

	public UUID getId() {
		return id;
	}

	public String getEmail() {
		return email;
	}

	public String getUsername() {
		return username;
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getUpdatedAt() {
		return updatedAt;
	}

	public Role getRole() {
		return role;
	}

	public AiAccess getAiAccess() {
		return aiAccess;
	}

	public Instant getAiAccessRequestedAt() {
		return aiAccessRequestedAt;
	}

	public Instant getAiAccessDecidedAt() {
		return aiAccessDecidedAt;
	}

	public User getAiAccessDecidedBy() {
		return aiAccessDecidedBy;
	}

	/** Nouvelle demande : l'éventuelle décision précédente est effacée. */
	public void requestAiAccess(Instant now) {
		this.aiAccess = AiAccess.PENDING;
		this.aiAccessRequestedAt = now;
		this.aiAccessDecidedAt = null;
		this.aiAccessDecidedBy = null;
	}

	/** Décision d'un admin ({@code decidedBy} null pour une décision système). Les règles de transition sont dans le service. */
	public void decideAiAccess(AiAccess status, User decidedBy, Instant now) {
		this.aiAccess = status;
		this.aiAccessDecidedBy = decidedBy;
		this.aiAccessDecidedAt = now;
	}

	public void promoteToAdmin() {
		this.role = Role.ADMIN;
	}
}
