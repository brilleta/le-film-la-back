package com.lefilmla.back.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AdminUserServiceTest {

	@Test
	void likeWildcardsAreEscaped() {
		assertThat(AdminUserService.escapeLike("100%_a\\b")).isEqualTo("100\\%\\_a\\\\b");
	}
}
