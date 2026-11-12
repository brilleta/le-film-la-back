package com.lefilmla.back.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.lefilmla.back.TestcontainersConfiguration;
import com.lefilmla.back.entity.AiAccess;
import com.lefilmla.back.entity.Role;
import com.lefilmla.back.entity.User;
import com.lefilmla.back.repository.UserRepository;
import com.lefilmla.back.support.TestAuth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;

// Contexte (et base) dédiés : ces tests vident la table users.
@SpringBootTest(properties = "admin.email=Boss@Example.com")
@Import(TestcontainersConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class AdminBootstrapTest {

	@Autowired
	private AdminBootstrap adminBootstrap;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private TestAuth testAuth;

	@BeforeEach
	void cleanDatabase() {
		userRepository.deleteAll();
	}

	@Test
	void promotesAccountMatchingAdminEmailWhenNoAdminExists(CapturedOutput output) {
		User boss = userRepository.saveAndFlush(new User("boss@example.com", "boss", "hash"));

		adminBootstrap.run(null);

		User promoted = userRepository.findById(boss.getId()).orElseThrow();
		assertThat(promoted.getRole()).isEqualTo(Role.ADMIN);
		assertThat(promoted.getAiAccess()).isEqualTo(AiAccess.GRANTED);
		assertThat(promoted.getAiAccessDecidedAt()).isNotNull();
		assertThat(output).contains("promu administrateur").doesNotContain("boss@example.com");
	}

	@Test
	void doesNothingWhenAnAdminAlreadyExists(CapturedOutput output) {
		testAuth.createUser(Role.ADMIN, AiAccess.GRANTED);
		User boss = userRepository.saveAndFlush(new User("boss@example.com", "boss", "hash"));

		adminBootstrap.run(null);

		User unchanged = userRepository.findById(boss.getId()).orElseThrow();
		assertThat(unchanged.getRole()).isEqualTo(Role.USER);
		assertThat(unchanged.getAiAccess()).isEqualTo(AiAccess.NONE);
		assertThat(output).contains("un administrateur existe déjà");
	}
}
