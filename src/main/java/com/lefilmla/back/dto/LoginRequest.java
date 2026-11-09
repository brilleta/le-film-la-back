package com.lefilmla.back.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
		@NotBlank(message = "L'email est obligatoire")
		@Size(max = 254, message = "L'email est trop long") String email,
		@NotBlank(message = "Le mot de passe est obligatoire")
		@Size(max = 72, message = "Le mot de passe est trop long") String password) {
}
