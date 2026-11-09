package com.lefilmla.back.security;

import java.util.UUID;

/** Principal posé dans le SecurityContext quand le cookie JWT est valide. */
public record AuthenticatedUser(UUID id) {
}
