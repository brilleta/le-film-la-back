package com.lefilmla.back.controller;

import com.lefilmla.back.dto.LoginRequest;
import com.lefilmla.back.dto.RegisterRequest;
import com.lefilmla.back.dto.UserResponse;
import com.lefilmla.back.security.AuthCookies;
import com.lefilmla.back.security.AuthenticatedUser;
import com.lefilmla.back.service.AuthService;
import com.lefilmla.back.service.AuthService.AuthResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

	private final AuthService authService;

	private final AuthCookies authCookies;

	public AuthController(AuthService authService, AuthCookies authCookies) {
		this.authService = authService;
		this.authCookies = authCookies;
	}

	@PostMapping("/register")
	ResponseEntity<UserResponse> register(@Valid @RequestBody RegisterRequest body, HttpServletRequest request,
			HttpServletResponse response) {
		AuthResult result = authService.register(body);
		authCookies.signIn(result.token(), request, response);
		return ResponseEntity.status(HttpStatus.CREATED).body(result.user());
	}

	@PostMapping("/login")
	UserResponse login(@Valid @RequestBody LoginRequest body, HttpServletRequest request,
			HttpServletResponse response) {
		AuthResult result = authService.login(body);
		authCookies.signIn(result.token(), request, response);
		return result.user();
	}

	@PostMapping("/logout")
	ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
		authCookies.signOut(request, response);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/me")
	UserResponse me(@AuthenticationPrincipal AuthenticatedUser principal) {
		return authService.currentUser(principal);
	}
}
