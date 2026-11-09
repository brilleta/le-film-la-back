package com.lefilmla.back.config;

import com.lefilmla.back.repository.UserRepository;
import com.lefilmla.back.security.CsrfCookieFilter;
import com.lefilmla.back.security.JwtCookieAuthenticationFilter;
import com.lefilmla.back.security.SecurityErrorHandler;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(AuthProperties.class)
public class SecurityConfig {

	@Bean
	PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

	// Cookie XSRF-TOKEN lisible par le JS (httpOnly=false) ; le front le renvoie dans le header X-XSRF-TOKEN.
	@Bean
	CookieCsrfTokenRepository csrfTokenRepository(AuthProperties properties) {
		CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
		repository.setCookiePath("/");
		repository.setCookieCustomizer(cookie -> cookie.secure(properties.cookie().secure()).sameSite("Lax"));
		return repository;
	}

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder, AuthProperties properties,
			CookieCsrfTokenRepository csrfTokenRepository, SecurityErrorHandler errorHandler,
			UserRepository userRepository) throws Exception {
		http
			// Config SPA officielle (spa()), avec notre dépôt pour partager les réglages du cookie. Aucune exemption.
			.csrf(csrf -> csrf.spa().csrfTokenRepository(csrfTokenRepository))
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.httpBasic(basic -> basic.disable())
			.formLogin(form -> form.disable())
			.logout(logout -> logout.disable())
			.requestCache(cache -> cache.disable())
			.authorizeHttpRequests(auth -> auth
				.requestMatchers("/api/movies/**", "/api/trending", "/api/auth/**", "/actuator/health", "/error")
				.permitAll()
				.requestMatchers("/api/admin/**").hasRole("ADMIN")
				.anyRequest().authenticated())
			.exceptionHandling(exceptions -> exceptions
				.authenticationEntryPoint(errorHandler)
				.accessDeniedHandler(errorHandler))
			.addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
			.addFilterBefore(new JwtCookieAuthenticationFilter(jwtDecoder, properties.cookie().name(), userRepository),
					AnonymousAuthenticationFilter.class);
		return http.build();
	}
}
