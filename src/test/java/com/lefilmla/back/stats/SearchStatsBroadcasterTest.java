package com.lefilmla.back.stats;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lefilmla.back.dto.ErrorResponse;
import com.lefilmla.back.dto.SearchStatsResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SearchStatsBroadcasterTest {

	private final SearchStatsService service = mock(SearchStatsService.class);

	private final SearchStatsBroadcaster broadcaster = new SearchStatsBroadcaster(service, Duration.ofMinutes(1));

	@Test
	void payloadIsTheSnapshotWhenTheStreamRuns() {
		var snapshot = new SearchStatsResponse(Instant.EPOCH, 15, Map.of(), List.of());
		when(service.snapshot(15)).thenReturn(snapshot);

		var payload = broadcaster.payload(15);

		assertThat(payload.name()).isEqualTo(SearchStatsBroadcaster.EVENT_STATS);
		assertThat(payload.data()).isSameAs(snapshot);
	}

	@Test
	void payloadSignalsUnavailabilityInsteadOfFailing() {
		when(service.snapshot(15)).thenThrow(new StatsUnavailableException());

		var payload = broadcaster.payload(15);

		assertThat(payload.name()).isEqualTo(SearchStatsBroadcaster.EVENT_UNAVAILABLE);
		assertThat(payload.data()).isEqualTo(new ErrorResponse("STATS_UNAVAILABLE",
				"Statistiques temporairement indisponibles"));
	}

	@Test
	void noSnapshotIsComputedWithoutSubscribers() {
		broadcaster.broadcast();

		verify(service, never()).snapshot(anyInt());
	}

	@Test
	void oneSnapshotPerTickAndDepthWhateverTheNumberOfSubscribers() {
		when(service.snapshot(anyInt())).thenAnswer(
				call -> new SearchStatsResponse(Instant.EPOCH, call.getArgument(0), Map.of(), List.of()));
		broadcaster.subscribe(15);
		broadcaster.subscribe(15);
		broadcaster.subscribe(60);

		broadcaster.broadcast();

		// 3 snapshots à l'abonnement, puis 1 par profondeur au tick
		verify(service, times(2 + 1)).snapshot(15);
		verify(service, times(1 + 1)).snapshot(60);
		assertThat(broadcaster.subscriberCount()).isEqualTo(3);

		broadcaster.completeAll();
		assertThat(broadcaster.subscriberCount()).isZero();
	}
}
