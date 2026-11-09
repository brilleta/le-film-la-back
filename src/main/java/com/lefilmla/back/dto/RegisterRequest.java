package com.lefilmla.back.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
		@NotBlank(message = "L'email est obligatoire")
		@Email(message = "L'email n'est pas valide")
		@Size(max = 254, message = "L'email est trop long") String email,
		@NotBlank(message = "Le pseudo est obligatoire")
		@Size(min = 3, max = 30, message = "Le pseudo doit faire entre 3 et 30 caractères") String username,
		@NotBlank(message = "Le mot de passe est obligatoire")
		@Size(min = 8, max = 72, message = "Le mot de passe doit faire entre 8 et 72 caractères") String password) {
}
