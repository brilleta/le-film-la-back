package com.lefilmla.back.events.history;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lefilmla.back.entity.AiAccess;
import com.lefilmla.back.events.AiAccessEvent;
import com.lefilmla.back.events.AiAccessEventType;
import com.lefilmla.back.exception.UserNotFoundException;
import com.lefilmla.back.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AiAccessHistoryServiceTest {

	private static final Instant NOW = Instant.parse("2026-11-12T10:00:00Z");

	private final AiAccessHistoryRepository repository = mock(AiAccessHistoryRepository.class);

	private final UserRepository userRepository = mock(UserRepository.class);

	private final AiAccessHistoryService service = new AiAccessHistoryService(repository, userRepository,
			Clock.fixed(NOW, ZoneOffset.UTC));

	@Test
	void recordsEveryFieldAndTheConsumptionTime() {
		UUID userId = UUID.randomUUID();
		UUID adminId = UUID.randomUUID();
		AiAccessEvent event = new AiAccessEvent(1, UUID.randomUUID(), AiAccessEventType.GRANTED, userId,
				AiAccess.PENDING, AiAccess.GRANTED, new AiAccessEvent.Actor(adminId, "alice"),
				NOW.minusSeconds(5));
		when(repository.insertIfAbsent(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);

		assertThat(service.record(event)).isTrue();

		verify(repository).insertIfAbsent(event.eventId(), userId, "GRANTED", "PENDING", "GRANTED", adminId, "alice",
				NOW.minusSeconds(5), NOW);
	}

	@Test
	void duplicateEventIsReportedNotInserted() {
		AiAccessEvent event = new AiAccessEvent(1, UUID.randomUUID(), AiAccessEventType.REQUESTED, UUID.randomUUID(),
				AiAccess.NONE, AiAccess.PENDING, null, NOW);
		when(repository.insertIfAbsent(any(), any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(0);

		assertThat(service.record(event)).isFalse();

		verify(repository).insertIfAbsent(eq(event.eventId()), any(), eq("REQUESTED"), any(), any(), isNull(),
				isNull(), any(), any());
	}

	@Test
	void historyOfUnknownUserIsNotFound() {
		UUID unknown = UUID.randomUUID();
		when(userRepository.existsById(unknown)).thenReturn(false);

		assertThatThrownBy(() -> service.history(unknown)).isInstanceOf(UserNotFoundException.class);
	}
}
