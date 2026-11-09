package com.lefilmla.back.security;

import com.lefilmla.back.entity.AiAccess;
import com.lefilmla.back.entity.Role;
import java.util.UUID;

/**
 * Principal posé dans le SecurityContext quand le cookie JWT est valide. Rôle et accès IA sont relus en base
 * à chaque requête : un retrait prend effet immédiatement, sans attendre l'expiration du JWT.
 */
public record AuthenticatedUser(UUID id, Role role, AiAccess aiAccess) {
}
