package com.lefilmla.back.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AiSearchRequest(
		@NotBlank(message = "La description est obligatoire")
		@Size(max = 300, message = "La description ne doit pas dépasser 300 caractères") String description) {
}
