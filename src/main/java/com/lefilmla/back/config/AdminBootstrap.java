package com.lefilmla.back.config;

import com.lefilmla.back.service.AdminBootstrapService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Lancé une fois le contexte démarré, donc après les migrations Flyway. */
@Component
public class AdminBootstrap implements ApplicationRunner {

	private final AdminBootstrapService adminBootstrapService;

	private final String adminEmail;

	public AdminBootstrap(AdminBootstrapService adminBootstrapService, @Value("${admin.email:}") String adminEmail) {
		this.adminBootstrapService = adminBootstrapService;
		this.adminEmail = adminEmail;
	}

	@Override
	public void run(ApplicationArguments args) {
		adminBootstrapService.promoteInitialAdmin(adminEmail);
	}
}
