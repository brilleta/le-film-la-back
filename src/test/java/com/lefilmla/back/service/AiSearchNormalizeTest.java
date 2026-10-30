package com.lefilmla.back.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AiSearchNormalizeTest {

	@Test
	void ignoresCaseAccentsAndExtraSpaces() {
		assertThat(AiSearchService.normalize("  Un  FILM\tsur l'Été ")).isEqualTo("un film sur l'ete");
	}
}
